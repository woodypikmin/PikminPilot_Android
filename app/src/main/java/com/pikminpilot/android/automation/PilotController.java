package com.pikminpilot.android.automation;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.pikminpilot.android.detection.CargoDetector;
import com.pikminpilot.android.detection.Detector;
import com.pikminpilot.android.model.PilotConfig;
import com.pikminpilot.android.model.SelectionPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Android automation loop matching the iOS Stage 11.5.4.31 gameplay flow. */
public final class PilotController {
    public interface Listener { void onStatus(String status); void onLog(String line); }
    private static final PilotController INSTANCE=new PilotController();
    public static PilotController get(){return INSTANCE;}

    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AtomicBoolean running=new AtomicBoolean(false);
    private volatile PilotAccessibilityService service;
    private final Object serviceLock=new Object();
    private volatile long serviceLostUptime=0L;
    private volatile Listener listener;
    private volatile int completed=0;
    private volatile String currentStage="IDLE";
    private volatile long runStartUptime=0L;
    private final java.util.ArrayDeque<RecentDispatch> recentDispatches=new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<RecentDispatch> failedSelectionSkips=new java.util.ArrayDeque<>();
    // Monotonic within one START session. Once a fallback plan is reached, later
    // cargo starts from that plan instead of wasting time retrying an exhausted
    // earlier colour. A fresh START resets this floor back to PRIMARY.
    private volatile int stickySelectionPlanFloor=0;

    private static final class RecentDispatch {
        final CargoDetector.Kind kind; final String key; final float nx,ny; final int round;
        RecentDispatch(CargoDetector.Kind kind,String key,float nx,float ny,int round){
            this.kind=kind;this.key=key;this.nx=nx;this.ny=ny;this.round=round;
        }
    }

    public void setListener(Listener l){listener=l;}
    public boolean isRunning(){return running.get();}
    public int completed(){return completed;}

    public void onServiceReady(PilotAccessibilityService s){
        boolean wasLost=serviceLostUptime>0L;
        long lostFor=wasLost?Math.max(0L,SystemClock.elapsedRealtime()-serviceLostUptime):0L;
        service=s; serviceLostUptime=0L;
        synchronized(serviceLock){serviceLock.notifyAll();}
        if(running.get()&&wasLost){
            emit("ACCESSIBILITY RECONNECTED ✅ • paused="+String.format(java.util.Locale.US,"%.1fs",lostFor/1000.0)+
                    " • resumeStage="+currentStage);
            status("輔助使用已恢復 • 繼續執行");
        }else emit("Accessibility service ready ✅");
    }

    public void onServiceDisconnected(PilotAccessibilityService s,String reason){
        boolean changed=false;
        if(service==s || PilotAccessibilityService.get()==null){
            if(service!=null){service=null;changed=true;}
            if(serviceLostUptime==0L)serviceLostUptime=SystemClock.elapsedRealtime();
        }
        synchronized(serviceLock){serviceLock.notifyAll();}
        if(changed&&running.get()){
            emit("ACCESSIBILITY LOST ⚠️ • reason="+reason+" • stage="+currentStage+
                    " • automation PAUSED; waiting for Android to reconnect service");
            status("輔助使用暫時中斷 • 等待系統重新連線");
        }
    }

    public void onServiceInterrupted(PilotAccessibilityService s){
        if(running.get()) emit("ACCESSIBILITY INTERRUPT ⚠️ • stage="+currentStage+" • service still bound");
    }

    public void start(PilotConfig cfg){
        if(running.getAndSet(true)) return;
        completed=0; recentDispatches.clear(); failedSelectionSkips.clear(); stickySelectionPlanFloor=0;
        runStartUptime=SystemClock.elapsedRealtime(); currentStage="START";
        worker.execute(()->run(cfg));
    }

    public void stop(String reason){
        if(running.getAndSet(false))emit("STOP • "+reason);
        synchronized(serviceLock){serviceLock.notifyAll();}
    }
    public void stop(){stop("user requested");}

    private void run(PilotConfig cfg){
        try{
            requireService();
            stage("START","啟動 • 開始掃描探險列表");
            emit("BUILD 0.4.8-alpha35 • persistent loading GO-watch • fruit color filter • single-tap filter • hard GO lock • second-frame BUSY veto • adaptive screenshot throttle");
            emit("ANDROID PILOT START • target="+(cfg.dispatchTarget==0?"∞":cfg.dispatchTarget)+
                    " • cargo="+PilotConfig.cargoName(cfg.cargoMode)+
                    " • type="+PilotConfig.pikminName(cfg.type)+" • count="+cfg.pikminCount+
                    " • speed="+(cfg.fast?"FAST":"STABLE")+
                    " • handoffDelay=3000ms");

            // START intentionally does not perform an Expedition-page precheck.
            // The expected workflow is: keep Pikmin Bloom already open on the
            // Expedition list, return to Pilot, press START, wait 3 seconds, then
            // begin the normal cargo scan immediately.
            while(running.get()&&(cfg.dispatchTarget==0||completed<cfg.dispatchTarget)){
                int round=completed+1;
                stage("SCAN_LIST","第 "+round+" 輪：尋找"+PilotConfig.cargoName(cfg.cargoMode));
                CargoAndBitmap choice=findCargo(cfg,round);
                if(choice==null){
                    emit("ROUND "+round+" • full scan found no safe AVAILABLE item • TARGET-LATCH=HOLD");
                    sleep(cfg.fast?350:600);
                    continue;
                }

                stage("OPEN_CARGO","第 "+round+" 輪：點擊"+(choice.item.kind==CargoDetector.Kind.SEEDLING?"花苗":"水果"));
                emit("ROUND "+round+" • tap AVAILABLE • kind="+choice.item.kind+" • label="+choice.item.label);
                tapMapped(choice.b,choice.item.center.x,choice.item.center.y,55,"CARGO");
                sleep(cfg.fast?550:850);

                stage("CTA","第 "+round+" 輪：前往探險");
                enterExpeditionSelectionPage(choice.item.kind,round,cfg);

                SelectionCommit commit=runSelectionPlans(cfg,round,choice.item.kind);
                if(!commit.sent){
                    emit("ROUND "+round+" • all selection plans insufficient • cargo safely skipped");
                    temporarilySkip(choice,round);
                    continue;
                }
                sleep(cfg.fast?420:560);

                stage("GREEN_X","第 "+round+" 輪：關閉傳送頁面綠色 X");
                PointAndBitmap close=waitPoint("綠色 X",10,cfg.fast?90:130,Detector::detectCarryingClose);
                if(close==null) throw new RuntimeException("傳送頁面綠色 X 未辨識到");
                if(!closeGreenX(close,cfg)) throw new RuntimeException("GREEN X 已辨識，但點擊後未確認回到探險列表");

                rememberDispatch(choice,round);
                completed++;
                emit("ROUND "+round+" COMPLETED ✅ • total="+completed);
                status("完成 "+completed+(cfg.dispatchTarget>0?" / "+cfg.dispatchTarget:"")+" 次");
                currentStage="ROUND_GAP";
                sleep(cfg.fast?60:100);
            }
            if(running.get()){emit("COMPLETED ✅ • total="+completed);status("已完成 • "+completed+" 次");}
        }catch(InterruptedException stopped){
            emit("STOPPED • completed="+completed); status("已停止 • 完成 "+completed+" 次");
        }catch(Throwable t){
            String code=classifyFailure(t);
            emit("ERROR["+code+"] • stage="+currentStage+" • completed="+completed+" • "+t.getMessage());
            status("執行失敗 ["+code+"]："+t.getMessage());
        }finally{running.set(false);}
    }

    private CargoAndBitmap findCargo(PilotConfig cfg,int round)throws Exception{
        boolean down=true,reversed=false;int swipes=0,guardMisses=0;
        while(running.get()){
            status("第 "+round+" 輪：掃描探險列表 • "+(down?"往下":"往上")+" "+(swipes+1)+"/13");
            emit("ROUND "+round+" • capture → OCR/list detector • direction="+(down?"DOWN":"UP")+" • swipe="+swipes);
            Bitmap b=shot();
            CargoDetector.Result result=CargoDetector.scan(b);
            emit("NAV-GUARD • proven="+result.navGuardProven+" • source="+result.navGuardSource+
                    " • contentTopY="+Math.round(result.contentTopY)+"/"+b.getHeight());
            if(!result.navGuardProven){
                guardMisses++;
                emit("SCAN-DIAG SKIP[NAV_GUARD_UNPROVEN] • refusing cargo tap/swipe • retry="+guardMisses+"/5");
                if(guardMisses>=5) throw new RuntimeException("探險導覽列未穩定辨識；為避免誤點上方『花苗』分頁已停止");
                sleep(3000);
                continue;
            }
            guardMisses=0;
            List<CargoDetector.Candidate> available=result.matching(cfg.cargoMode);
            int beforeFruitFilter=available.size();
            available.removeIf(c->c.kind==CargoDetector.Kind.FRUIT && !cfg.acceptsFruitGroup(c.fruitGroup));
            if(beforeFruitFilter!=available.size())
                emit("SCAN-DIAG SKIP[FRUIT_COLOR_FILTER] count="+(beforeFruitFilter-available.size())+
                        " • selected="+cfg.fruitGroups);
            int beforeNav=available.size();
            available.removeIf(c->c.center.y<result.contentTopY);
            if(beforeNav!=available.size()) emit("SCAN-DIAG SKIP[NAV_GUARD] count="+(beforeNav-available.size()));
            int beforeRecent=available.size();
            available.removeIf(c->isExactRecentDispatch(c,b,round));
            if(beforeRecent!=available.size())
                emit("SCAN-DIAG SKIP[EXACT_RECENT_DISPATCH] count="+(beforeRecent-available.size()));
            int beforeFailed=available.size();
            available.removeIf(c->isFailedSelectionSkip(c,b,round));
            if(beforeFailed!=available.size())
                emit("SCAN-DIAG SKIP[SELECTION_FAILED_RECENT] count="+(beforeFailed-available.size()));
            // This latch is intentionally very narrow. It only blocks the same
            // normalized label at essentially the same screen slot; neighbouring
            // seedlings are left to the current-frame card detector.
            int busy=0,complete=0,statusBlocked=0;for(CargoDetector.StatusCard c:result.cards){if(c.state==CargoDetector.CardState.BUSY)busy++;else if(c.state==CargoDetector.CardState.COMPLETE)complete++;else statusBlocked++;}
            emit("ROUND "+round+" SCAN • FRUIT="+result.fruits.size()+" • SEEDLING="+result.seedlings.size()+
                    " • MATCH="+available.size()+" • BUSY="+busy+" • COMPLETE="+complete+" • STATUS_BLOCKED="+statusBlocked+
                    " • UNKNOWN_OBJECT="+result.blocked.size());
            if(!result.diagnostics.isEmpty()) {
                int shown=Math.min(8,result.diagnostics.size());
                for(int di=0;di<shown;di++) emit("SCAN-DIAG "+result.diagnostics.get(di));
                if(result.diagnostics.size()>shown) emit("SCAN-DIAG ... +"+(result.diagnostics.size()-shown)+" more");
            }
            if(!result.seedlings.isEmpty()) {
                StringBuilder labels=new StringBuilder();
                for(int si=0;si<result.seedlings.size() && si<6;si++) {
                    CargoDetector.Candidate c=result.seedlings.get(si);
                    if(labels.length()>0) labels.append(" | ");
                    labels.append(c.label).append(" @(").append(Math.round(c.center.x)).append(',').append(Math.round(c.center.y)).append(')');
                }
                emit("SEEDLING OCR ✅ • "+labels);
            }
            if(!available.isEmpty()) {
                for(CargoDetector.Candidate candidate:new ArrayList<>(available)){
                    CargoAndBitmap verified=verifyEdgeCargoIfNeeded(cfg,round,candidate,b,result);
                    if(verified!=null) return verified;
                }
                emit("SCAN-DIAG • all edge-risk candidates failed second-frame AVAILABLE confirmation");
            }

            if(swipes>=12){
                if(!reversed){
                    reversed=true;down=false;swipes=0;
                    status("第 "+round+" 輪：下方找不到，改往上掃描");
                    emit("ROUND "+round+" • no matching AVAILABLE below; reversing list scan");
                } else {
                    status("第 "+round+" 輪：整份列表找不到符合條件的項目");
                    return null;
                }
            }
            RectF r=Detector.activeContentRect(b);float x=(float)(r.left+r.width()*0.52);
            // Smaller overlapping list motion: about 24% of the active content.
            // The overlap is intentional so partially-visible fruit/seedling rows are
            // not skipped, and we wait 3s after the gesture before the next capture.
            float sy=(float)(r.top+r.height()*(down?0.64:0.40)),ey=(float)(r.top+r.height()*(down?0.40:0.64));
            emit("EXPEDITION LIST SWIPE • amplitude=0.24H • direction="+(down?"DOWN":"UP")+
                    " • ("+Math.round(x)+","+Math.round(sy)+") → ("+Math.round(x)+","+Math.round(ey)+")");
            swipeMapped(b,x,sy,x,ey,420,"EXPEDITION LIST");
            swipes++;
            emit("EXPEDITION LIST SETTLE ⏳ • 3000ms • no screenshot / OCR / detection");
            sleep(3000);
        }
        return null;
    }

    /**
     * AVAILABLE is a positive action, so candidates touching the top/bottom edge
     * need two stable frames before we tap them. BUSY/COMPLETE remains one-vote
     * veto. This is intentionally limited to edge-risk items so the normal list
     * path does not pay a second OCR pass on every cargo.
     */
    private CargoAndBitmap verifyEdgeCargoIfNeeded(PilotConfig cfg,int round,CargoDetector.Candidate candidate,
                                                   Bitmap first,CargoDetector.Result firstResult)throws Exception{
        float h=first.getHeight(),w=first.getWidth();
        boolean lower=candidate.center.y>=h*0.78f;
        boolean upper=candidate.center.y<=firstResult.contentTopY+h*0.12f;

        // Fruit gets a cheap OCR-free second-frame BUSY veto even away from the
        // viewport edges.  Many real-phone misses happen while the pale border /
        // progress rail is still settling after returning to the list.  A second
        // screenshot is much cheaper than a second ML Kit pass and prevents an
        // animated BUSY card from becoming a positive tap.
        if(candidate.kind==CargoDetector.Kind.FRUIT && !lower && !upper){
            emit("CARGO BUSY PREFLIGHT ⏳ • second-frame visual veto • wait=700ms");
            sleep(700);
            Bitmap second=shot();
            float sx=second.getWidth()/Math.max(1f,first.getWidth());
            float sy=second.getHeight()/Math.max(1f,first.getHeight());
            PointF mapped=new PointF(candidate.center.x*sx,candidate.center.y*sy);
            float contentTop=firstResult.contentTopY*sy;
            String reason=CargoDetector.visualBusyReasonAt(second,mapped,contentTop);
            if(reason!=null){
                emit("CARGO BUSY PREFLIGHT REJECTED ✅ • "+reason+
                        " • object=("+Math.round(mapped.x)+","+Math.round(mapped.y)+") • no tap");
                return null;
            }
            RectF rr=new RectF(candidate.rect.left*sx,candidate.rect.top*sy,candidate.rect.right*sx,candidate.rect.bottom*sy);
            CargoDetector.Candidate mappedCandidate=new CargoDetector.Candidate(mapped,rr,candidate.kind,candidate.label);
            emit("CARGO BUSY PREFLIGHT CLEAR ✅ • second frame has no visual BUSY evidence");
            return new CargoAndBitmap(mappedCandidate,second);
        }

        if(!lower&&!upper) return new CargoAndBitmap(candidate,first);

        emit("CARGO EDGE VERIFY ⏳ • risk="+(lower?"LOWER":"UPPER")+
                " • first=("+Math.round(candidate.center.x)+","+Math.round(candidate.center.y)+") • wait=850ms");
        sleep(850);
        Bitmap second=shot();
        CargoDetector.Result again=CargoDetector.scan(second);
        if(!again.navGuardProven){
            emit("CARGO EDGE VERIFY REJECTED ⚠️ • NAV-GUARD unproven on second frame");
            return null;
        }
        List<CargoDetector.Candidate> secondAvailable=again.matching(cfg.cargoMode);
        secondAvailable.removeIf(c->c.kind==CargoDetector.Kind.FRUIT && !cfg.acceptsFruitGroup(c.fruitGroup));
        secondAvailable.removeIf(c->c.center.y<again.contentTopY);
        secondAvailable.removeIf(c->isExactRecentDispatch(c,second,round));
        secondAvailable.removeIf(c->isFailedSelectionSkip(c,second,round));

        CargoDetector.Candidate best=null;double bestD=Double.MAX_VALUE;
        float sx=second.getWidth()/Math.max(1f,first.getWidth());
        float sy=second.getHeight()/Math.max(1f,first.getHeight());
        float ex=candidate.center.x*sx,ey=candidate.center.y*sy;
        for(CargoDetector.Candidate c:secondAvailable){
            if(c.kind!=candidate.kind) continue;
            double dx=(c.center.x-ex)/Math.max(1f,second.getWidth());
            double dy=(c.center.y-ey)/Math.max(1f,second.getHeight());
            double d=dx*dx+dy*dy;
            if(Math.abs(dx)>0.09||Math.abs(dy)>0.09) continue;
            if(d<bestD){bestD=d;best=c;}
        }
        if(best==null){
            emit("CARGO EDGE VERIFY REJECTED ✅ • candidate not AVAILABLE on second frame; no tap");
            int shown=Math.min(5,again.diagnostics.size());
            for(int i=0;i<shown;i++) emit("EDGE-DIAG "+again.diagnostics.get(i));
            return null;
        }
        if(best.kind==CargoDetector.Kind.FRUIT){
            String visualReason=CargoDetector.visualBusyReasonAt(second,best.center,again.contentTopY);
            if(visualReason!=null){
                emit("CARGO EDGE VERIFY REJECTED ✅ • visual BUSY veto="+visualReason+" • no tap");
                return null;
            }
        }
        emit("CARGO EDGE VERIFY STABLE ✅ • second=("+Math.round(best.center.x)+","+Math.round(best.center.y)+")");
        return new CargoAndBitmap(best,second);
    }

    /**
     * Universal detail-page CTA transition for both fruit and seedlings.
     *
     * The previous Android build had a real state-machine bug: as soon as
     * dispatchGesture() reported COMPLETED we set CTA-tapped=true and never
     * tried the button again. COMPLETED only means Android delivered the gesture;
     * Pikmin Bloom can still ignore it while the detail sheet is settling.
     *
     * This version separates four facts:
     *  1) the CTA is visually present,
     *  2) its centre is stable across frames,
     *  3) a gesture was dispatched,
     *  4) the screen actually left the CTA state.
     *
     * It prefers the real green pill centre (large hit target), uses OCR as a
     * semantic confirmation/fallback, retries only while the same CTA remains
     * visible, and accepts two consecutive CTA-absent frames as transition
     * evidence even if ML Kit misses the selection header on that phone.
     */
    private void enterExpeditionSelectionPage(CargoDetector.Kind kind,int round,PilotConfig cfg)throws Exception{
        final int attempts=cfg.fast?12:15;
        final long delay=cfg.fast?150:220;
        final int maxTaps=3;
        int taps=0;
        int stableFrames=0;
        int absentAfterTap=0;
        PointF previous=null;

        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();
            List<CargoDetector.OcrItem> ocr=java.util.Collections.emptyList();
            // Geometry is the fast primary path. OCR is intentionally sampled
            // only when geometry has not settled, because ML Kit can cost
            // multiple seconds on some phones.
            boolean sampleOcr=(taps>0 && (i%2==0)) || (taps==0 && i>=3 && (i%3==0));
            if(sampleOcr){
                try{ocr=CargoDetector.recognize(b);}catch(Throwable ignored){}
            }

            String proof=selectionPageProof(b,ocr,cfg.type);
            if(proof!=null){
                emit("EXPEDITION TRANSITION ✅ • kind="+kind+" • proof="+proof+
                        " • taps="+taps+" • attempt="+(i+1)+"/"+attempts);
                return;
            }

            PointF ocrPoint=CargoDetector.expeditionCtaPoint(ocr);
            PointF pillPoint=Detector.detectExpeditionCtaPill(b);
            PointF fallback=(kind==CargoDetector.Kind.SEEDLING)
                    ?Detector.detectSeedlingExpeditionCta(b)
                    :Detector.detectExpeditionButton(b);

            // When available, prefer the geometric centre of the actual outlined
            // pill instead of the OCR glyph centre. It is a much larger hit target.
            PointF p;
            String source;
            if(pillPoint!=null){p=pillPoint;source=ocrPoint!=null?"PILL+OCR":"PILL";}
            else if(ocrPoint!=null){p=ocrPoint;source="OCR";}
            else {p=fallback;source="LEGACY-GEOMETRY";}

            if(p!=null){
                absentAfterTap=0;
                float tol=Math.max(12f,Math.min(b.getWidth(),b.getHeight())*0.028f);
                if(previous!=null&&Math.hypot(p.x-previous.x,p.y-previous.y)<=tol) stableFrames++;
                else stableFrames=1;
                previous=p;

                if(i==0||stableFrames==1){
                    emit("EXPEDITION CTA SEEN • kind="+kind+" • source="+source+
                            " • px=("+Math.round(p.x)+","+Math.round(p.y)+") • stable="+stableFrames+
                            " • taps="+taps);
                }

                // Require two visually stable frames before the first tap so we
                // do not hit a button while the bottom sheet is still sliding.
                // After a tap, seeing the same stable CTA twice proves the game
                // did not consume that gesture, so retry the large hit target.
                if(stableFrames>=2&&taps<maxTaps){
                    if(taps>0) emit("EXPEDITION CTA STILL VISIBLE ⚠️ • previous tap not consumed • retry="+(taps+1)+"/"+maxTaps);
                    tapMapped(b,p.x,p.y,cfg.fast?105:120,"EXPEDITION CTA #"+(taps+1));
                    taps++;
                    stableFrames=0;
                    previous=null;
                    sleep(cfg.fast?420:560);
                    continue;
                }
            }else{
                previous=null;
                stableFrames=0;
                if(taps>0){
                    absentAfterTap++;
                    emit("EXPEDITION CTA ABSENT after tap • frame="+absentAfterTap+"/2 • waiting for selection UI");
                    if(absentAfterTap>=2){
                        // CTA disappearing twice is stronger evidence than one OCR
                        // phrase. Let the next filter detector be the final gate.
                        emit("EXPEDITION TRANSITION ASSUMED ✅ • CTA disappeared on 2 consecutive frames • taps="+taps);
                        sleep(cfg.fast?180:260);
                        return;
                    }
                }else if(i==0||i==3||i==attempts-1){
                    emit("EXPEDITION CTA waiting • kind="+kind+" • attempt="+(i+1)+"/"+attempts);
                }
            }
            sleep(delay);
        }

        if(taps>0) throw new RuntimeException("前往探險已送出 "+taps+" 次點擊，但畫面仍未離開按鈕狀態");
        throw new RuntimeException("前往探險未辨識到（OCR / pill / geometry 全部失敗）");
    }

    /** Strong selection-page signals that do not depend on one OCR sentence. */
    private String selectionPageProof(Bitmap b,List<CargoDetector.OcrItem> ocr,PilotConfig.PikminType target){
        try{
            if(CargoDetector.hasSelectionHeader(ocr)) return "selection-header";
            if(CargoDetector.filterRowHintPoint(ocr)!=null) return "decor/auto-row-label";
        }catch(Throwable ignored){}
        try{
            Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(b);
            if(row!=null&&row.chipCount>=3) return "filter-row-geometry";
        }catch(Throwable ignored){}
        return null;
    }

    /**
     * Filter selection locked to the actual red/yellow/blue/cyan chip lattice.
     *
     * No OCR fallback Y and no blind swipe are permitted.  If the row cannot be
     * proven, stop instead of dragging random parts of the selection grid.
     */
    private PointAndBitmap findPikminFilterAdaptive(PilotConfig cfg,int round,PilotConfig.PikminType targetType)throws Exception{
        Bitmap frame=shot();
        int swipes=0;

        for(int attempt=0;attempt<5&&running.get();attempt++){
            Detector.FilterLattice lattice=Detector.detectFilterLattice(frame);
            if(lattice==null){
                emit("FILTER LATTICE MISS ⚠️ • attempt="+(attempt+1)+"/5 • NO SWIPE • row not proven");
                // One settle retry is useful immediately after the CTA transition.
                if(attempt==0){
                    sleep(cfg.fast?180:280);
                    frame=shot();
                    continue;
                }
                return null;
            }

            float tx=lattice.targetX(targetType);
            boolean visible=tx>=frame.getWidth()*0.055f&&tx<=frame.getWidth()*0.945f;
            boolean plausible=visible&&Detector.filterTargetLooksPlausible(frame,lattice,targetType);

            emit("FILTER LATTICE ✅ • evidence="+lattice.evidenceCount+
                    " • rowY="+Math.round(lattice.rowY)+
                    " ("+String.format(java.util.Locale.US,"%.3f",lattice.rowY/Math.max(1f,frame.getHeight()))+"H)"+
                    " • spacing="+Math.round(lattice.spacing)+
                    " • originX="+Math.round(lattice.originX)+
                    " • residual="+String.format(java.util.Locale.US,"%.1f",lattice.residual)+
                    " • targetX="+Math.round(tx)+
                    " • visible="+visible+
                    " • plausible="+plausible);

            if(visible&&plausible){
                emit((swipes==0?"PIKMIN FILTER DIRECT ✅":"PIKMIN FILTER AFTER SWIPE ✅")+
                        " • round="+round+" • swipes="+swipes);
                return new PointAndBitmap(new PointF(tx,lattice.rowY),frame);
            }

            if(!visible){
                if(swipes>=2){
                    emit("FILTER TARGET OFFSCREEN ⚠️ • swipe limit reached");
                    return null;
                }
                status("第 "+round+" 輪：移動皮克敏顏色列");
                swipeFilterRowTowardTarget(frame,lattice,tx,cfg.fast?240:320);
                swipes++;
                sleep(cfg.fast?220:320);
                frame=shot();
                continue;
            }

            // The lattice is proven and the slot is on-screen, but the expected
            // colour/brightness is not present.  Do NOT swipe somewhere else:
            // that is exactly how alpha14 wandered into the Pikmin grid.
            emit("FILTER TARGET REJECTED ⚠️ • proven row, on-screen slot does not look like "+
                    PilotConfig.pikminName(targetType)+" • NO SWIPE");
            if(attempt<2){
                sleep(cfg.fast?160:240);
                frame=shot();
                continue;
            }
            return null;
        }
        return null;
    }

    private void swipeFilterRowTowardTarget(Bitmap frame,Detector.FilterLattice lattice,float targetX,long ms)throws Exception{
        float w=frame.getWidth(),y=lattice.rowY;
        float fromX,toX;
        if(targetX>w*0.945f){
            fromX=w*0.80f;
            float slots=Math.max(2.4f,Math.min(4.6f,(targetX-w*0.78f)/Math.max(1f,lattice.spacing)+1.2f));
            toX=Math.max(w*0.34f,fromX-slots*lattice.spacing);
            emit("FILTER ROW SWIPE LEFT ✅ • provenRowY="+Math.round(y)+
                    " • targetOffscreenX="+Math.round(targetX)+
                    " • ("+Math.round(fromX)+","+Math.round(y)+") → ("+Math.round(toX)+","+Math.round(y)+")");
        }else if(targetX<w*0.055f){
            fromX=w*0.32f;
            float slots=Math.max(2.4f,Math.min(4.6f,(w*0.22f-targetX)/Math.max(1f,lattice.spacing)+1.2f));
            toX=Math.min(w*0.78f,fromX+slots*lattice.spacing);
            emit("FILTER ROW SWIPE RIGHT ✅ • provenRowY="+Math.round(y)+
                    " • targetOffscreenX="+Math.round(targetX)+
                    " • ("+Math.round(fromX)+","+Math.round(y)+") → ("+Math.round(toX)+","+Math.round(y)+")");
        }else{
            return;
        }
        swipeMapped(frame,fromX,y,toX,y,ms,"FILTER ROW");
    }

    /**
     * A dispatched Accessibility tap is not enough.  On the normal unfiltered
     * row Pikmin Bloom dims the non-target colour chips after consuming a filter
     * tap.  Use that visual change as a lightweight ACK; retry the same proven
     * slot once before allowing Pikmin selection to start.
     */
    /**
     * Apply one colour-filter tap with an idempotency-safe ACK.
     *
     * alpha28 could tap the same colour twice when the first tap had actually
     * succeeded but the saturation ACK was weak/late. On Pikmin Bloom that can
     * toggle the filter back off. This version NEVER blindly re-taps a proven
     * colour chip in the same plan.
     */
    private void applyPikminFilterWithAck(PointAndBitmap filter,PilotConfig cfg,int round,PilotConfig.PikminType targetType)throws Exception{
        Detector.FilterLattice beforeLattice=Detector.detectFilterLattice(filter.b);
        float beforeScore=Detector.filterRowSaturationScore(filter.b,beforeLattice);
        float beforeShadow=Detector.filterTargetSelectedShadowScore(filter.b,beforeLattice,targetType);

        if(beforeLattice!=null && Detector.filterTargetAppearsSelected(filter.b,beforeLattice,targetType)){
            emit("PIKMIN FILTER ALREADY SELECTED ✅ • no tap • shadow="+
                    String.format(java.util.Locale.US,"%.3f",beforeShadow));
            return;
        }

        tapMapped(filter.b,filter.p.x,filter.p.y,55,"PIKMIN FILTER ONCE");
        emit("PIKMIN FILTER TAP ONCE ✅ • double-tap disabled");

        // Observe several frames instead of toggling the chip again. Positive
        // evidence may be the selected-chip shadow, the row dimming, or the
        // roster entering its loading-placeholder state.
        for(int obs=1;obs<=4&&running.get();obs++){
            sleep(cfg.fast?260:380);
            Bitmap after=shot();
            Detector.FilterLattice afterLattice=Detector.detectFilterLattice(after);
            float afterScore=Detector.filterRowSaturationScore(after,afterLattice);
            float shadow=Detector.filterTargetSelectedShadowScore(after,afterLattice,targetType);
            int placeholders=Detector.selectionLoadingPlaceholderCount(after);
            boolean selected=afterLattice!=null && Detector.filterTargetAppearsSelected(after,afterLattice,targetType);
            boolean dimmed=beforeScore>=0.52f && afterScore>=0f && afterScore<=beforeScore*0.82f;
            boolean loading=placeholders>=3 || Detector.isSelectionGridLoading(after);

            emit("PIKMIN FILTER ACK • obs="+obs+
                    " • shadow="+String.format(java.util.Locale.US,"%.3f",shadow)+
                    " • saturation="+String.format(java.util.Locale.US,"%.3f",beforeScore)+" → "+
                    String.format(java.util.Locale.US,"%.3f",afterScore)+
                    " • loading="+loading+"("+placeholders+")");

            if(selected){
                emit("PIKMIN FILTER ACK ✅ • selected-chip shadow confirmed • no second tap");
                return;
            }
            if(dimmed){
                emit("PIKMIN FILTER ACK ✅ • row dimmed after single tap • no second tap");
                return;
            }
            if(loading){
                emit("PIKMIN FILTER ACK ✅ • roster loading after single tap • no second tap");
                return;
            }
        }

        // A completed gesture plus a still-proven target can be visually
        // ambiguous on some phones. Do not manufacture a second tap: continue
        // to the loading/count/GO reconciliation, which is safer than toggling
        // the colour back off.
        emit("PIKMIN FILTER ACK UNKNOWN ⚠️ • one tap was sent; NO SECOND TAP • continue to selection/GO reconcile");
    }



    /**
     * Fast iOS-style selection with Android-safe geometric slot centres.
     *
     * There is intentionally NO per-tap OCR count acknowledgement here.  Some
     * expedition items allow fewer Pikmin than the user's requested count; in
     * that case the extra tap is harmless and GO is already available.  Waiting
     * for N/MAX after every tap made selection slow and incorrectly treated a
     * valid maxed-out party as an error.
     */
    private void selectPikminFastGeometric(PilotConfig cfg,int round,int desired,Bitmap readyFrame)throws Exception{
        Bitmap frame=readyFrame!=null?readyFrame:shot();
        List<PointF> grid=Detector.detectPikminSelectionGrid(frame);
        if(grid.size()<Math.min(12,Math.max(2,desired)))
            throw new RuntimeException("皮克敏格線辨識失敗：points="+grid.size());

        StringBuilder log=new StringBuilder("PIKMIN GRID STABLE ✅ • geometric centres • ");
        for(int i=0;i<Math.min(desired,grid.size());i++){
            if(i>0)log.append(" | ");
            PointF q=grid.get(i);
            log.append(i+1).append("=(").append(Math.round(q.x)).append(',').append(Math.round(q.y)).append(')');
        }
        emit(log.toString());

        int count=Math.min(desired,grid.size());
        for(int i=0;i<count&&running.get();i++){
            PointF q=grid.get(i);
            PilotAccessibilityService activeService=waitForService();
            PilotAccessibilityService.TapResult tr=activeService.tapFromBitmap(frame,q.x,q.y,cfg.fast?90:95)
                    .get(3,TimeUnit.SECONDS);
            emit("PIKMIN TAP "+(i+1)+"/"+desired+" • display=("+Math.round(tr.displayX)+","+
                    Math.round(tr.displayY)+") • dispatch="+
                    (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));
            // Keep the proven fast rhythm.  No screenshot/OCR between taps.
            sleep(cfg.fast?35:55);
        }
        sleep(cfg.fast?100:160);
    }


    private static final class SelectionCommit {
        final boolean sent;
        final String planName;
        SelectionCommit(boolean sent,String planName){this.sent=sent;this.planName=planName;}
    }

    private static final class SelectionObservedNow {
        final int selected,maximum;
        final String source;
        SelectionObservedNow(int selected,int maximum,String source){this.selected=selected;this.maximum=maximum;this.source=source;}
    }
    private static final class GoReconcileResult {
        final PointAndBitmap go;
        final SelectionObservedNow observed;
        final boolean loadingSeen;
        final boolean loadingStill;
        GoReconcileResult(PointAndBitmap go,SelectionObservedNow observed,boolean loadingSeen,boolean loadingStill){
            this.go=go;this.observed=observed;this.loadingSeen=loadingSeen;this.loadingStill=loadingStill;
        }
    }

    /**
     * Pre-GO selection fallback chain. selected/maximum describes the live team,
     * while an enabled GO is authoritative proof that the game accepts that team.
     * The transient "似乎很忙" toast remains diagnostic only.
     */
    private boolean[] enabledSelectionPlanFlags(PilotConfig cfg){
        boolean[] enabled=new boolean[cfg.selectionPlans.size()];
        for(int i=0;i<enabled.length;i++) enabled[i]=cfg.selectionPlans.get(i).enabled;
        return enabled;
    }

    private int firstEnabledSelectionPlanAtOrAfter(PilotConfig cfg,int floor){
        return SelectionPolicy.firstEnabledAtOrAfter(enabledSelectionPlanFlags(cfg),floor);
    }

    private int nextEnabledSelectionPlanAfter(PilotConfig cfg,int current){
        return SelectionPolicy.nextEnabledAfter(enabledSelectionPlanFlags(cfg),current);
    }

    /**
     * One transition path for PRIMARY→F1, F1→F2 and F2→F3. The cursor is
     * advanced before reset so the next cargo never walks backward to a colour
     * already proven insufficient during this START session.
     */
    private void advanceSelectionPlan(PilotConfig cfg,int round,CargoDetector.Kind kind,
                                      SelectionObservedNow priorObserved,int fromIndex,int toIndex)throws Exception{
        PilotConfig.SelectionPlan from=cfg.selectionPlans.get(fromIndex);
        PilotConfig.SelectionPlan to=cfg.selectionPlans.get(toIndex);
        stickySelectionPlanFloor=SelectionPolicy.advanceStickyFloor(stickySelectionPlanFloor,toIndex);
        emit("SELECTION PLAN ADVANCE • "+from.name+" → "+to.name+
                " • same-reset-state-machine • sticky-next-round="+to.name+
                " • floor="+stickySelectionPlanFloor);
        cancelAndResetSelection(cfg,round,kind,priorObserved);
    }

    /**
     * Pre-GO selection fallback chain. selected/maximum describes the live team,
     * while an enabled GO is authoritative proof that the game accepts that team.
     * The transient "似乎很忙" toast remains diagnostic only.
     *
     * The plan cursor is sticky for the lifetime of one START session. If PRIMARY
     * runs out and FALLBACK-1 succeeds, the next cargo starts directly at F1. If
     * F1 later runs out and advances to F2, later cargo starts at F2, etc.
     */
    private SelectionCommit runSelectionPlans(PilotConfig cfg,int round,CargoDetector.Kind kind)throws Exception{
        int startIndex=firstEnabledSelectionPlanAtOrAfter(cfg,stickySelectionPlanFloor);
        if(startIndex<0) throw new RuntimeException("沒有啟用的皮克敏選擇方案");
        if(startIndex>0){
            PilotConfig.SelectionPlan startPlan=cfg.selectionPlans.get(startIndex);
            emit("SELECTION STICKY START ✅ • round="+round+" • start="+startPlan.name+
                    " • type="+PilotConfig.pikminName(startPlan.type)+"皮 • skipped-earlier-plans=true");
        }

        int planIndex=startIndex;
        while(planIndex>=0 && planIndex<cfg.selectionPlans.size() && running.get()){
            PilotConfig.SelectionPlan plan=cfg.selectionPlans.get(planIndex);
            if(!plan.enabled){
                planIndex=nextEnabledSelectionPlanAfter(cfg,planIndex);
                continue;
            }

            stage("FILTER","第 "+round+" 輪："+plan.name+" • "+PilotConfig.pikminName(plan.type)+"皮");
            emit("SELECTION PLAN • "+plan.name+" • type="+PilotConfig.pikminName(plan.type)+"皮 • configured="+plan.configuredCount+
                    " • sticky-floor="+stickySelectionPlanFloor);

            PointAndBitmap filter=findPikminFilterAdaptive(cfg,round,plan.type);
            if(filter==null) throw new RuntimeException(plan.name+" "+PilotConfig.pikminName(plan.type)+"皮克敏顏色圓圈未辨識到");
            emit("PIKMIN FILTER TARGET ✅ • plan="+plan.name+" • type="+PilotConfig.pikminName(plan.type)+
                    " • px=("+Math.round(filter.p.x)+","+Math.round(filter.p.y)+")"+
                    " • norm=("+String.format(java.util.Locale.US,"%.3f",filter.p.x/Math.max(1f,filter.b.getWidth()))+","+
                    String.format(java.util.Locale.US,"%.3f",filter.p.y/Math.max(1f,filter.b.getHeight()))+") • detector=canonical-chip-lattice");
            applyPikminFilterWithAck(filter,cfg,round,plan.type);

            stage("SELECT","第 "+round+" 輪："+plan.name+" 選擇 "+plan.configuredCount+" 隻");
            Bitmap preTapFrame=shot();
            int loadingBeforeTap=Detector.selectionLoadingPlaceholderCount(preTapFrame);
            if(loadingBeforeTap>0){
                emit("SELECTION LOADING ⏳ • placeholders="+loadingBeforeTap+
                        " • taps are still allowed • Cancel/Fallback LOCKED until loading clears");
            }
            // Do NOT wait for the sprites to finish loading. The slot geometry is
            // already stable and Pikmin Bloom accepts taps on the placeholder
            // positions.  The important safety rule is only that loading must
            // never be interpreted as insufficiency / a reason to Cancel.
            selectPikminFastGeometric(cfg,round,plan.configuredCount,preTapFrame);

            SelectionObservedNow observed=readSelectionObservedBounded(cfg,3);
            if(observed==null){
                observed=new SelectionObservedNow(0,Math.max(1,plan.configuredCount),"UNAVAILABLE-PRELOAD");
                emit("SELECTION COUNT WAIT • selected/maximum not readable yet • defer fallback; GO/loading is source of truth");
            }else{
                int effective=SelectionPolicy.effectiveRequired(plan.configuredCount,observed.maximum);
                emit("SELECTION COUNT • selected="+observed.selected+"/"+observed.maximum+
                        " • configured="+plan.configuredCount+" • effective-required="+effective+
                        " • source="+observed.source);
            }

            // GO is authoritative. A smaller-than-configured team may still be
            // legal, and loading placeholders can temporarily leave the count at
            // 0/N even though the taps were already issued.
            stage("GO","第 "+round+" 輪："+plan.name+" • 確認 GO");
            GoReconcileResult reconciled=reconcileGoPreCommit(cfg,plan,observed);
            SelectionObservedNow finalObserved=reconciled.observed==null?observed:reconciled.observed;

            // If this phone was visibly loading and the queued taps ended with a
            // genuine 0-selected state after loading cleared, retry the SAME plan
            // once on the now-stable grid. Never Cancel just because loading was
            // seen.
            if(reconciled.go==null && reconciled.loadingSeen && !reconciled.loadingStill && finalObserved.selected==0){
                emit("SELECTION LOAD RECOVERY ↻ • loading cleared • selected=0 • GO off • retry SAME plan once • NO Cancel");
                Bitmap stableFrame=shot();
                selectPikminFastGeometric(cfg,round,plan.configuredCount,stableFrame);
                SelectionObservedNow retryObserved=readSelectionObservedBounded(cfg,3);
                if(retryObserved!=null) finalObserved=retryObserved;
                reconciled=reconcileGoPreCommit(cfg,plan,finalObserved);
                if(reconciled.observed!=null) finalObserved=reconciled.observed;
            }

            if(finalObserved.source.startsWith("UNAVAILABLE")){
                if(reconciled.go!=null){
                    // Strict bottom-right orange/red GO is authoritative: the
                    // game cannot enable it with an empty/illegal team.  Do not
                    // throw away a valid commit merely because OCR/UI-tree text
                    // is late while the roster is loading.
                    finalObserved=new SelectionObservedNow(1,1,"GO-AUTHORITATIVE");
                    emit("SELECTION COUNT BYPASS ✅ • count unavailable but strict GO is enabled • treat team as legal/non-zero");
                }else{
                    SelectionObservedNow reread=readSelectionObservedBounded(cfg,3);
                    if(reread==null) throw new RuntimeException("selection count 仍不可讀；拒絕把 loading/unknown 誤判成不足並取消");
                    finalObserved=reread;
                }
            }
            int finalEffective=SelectionPolicy.effectiveRequired(plan.configuredCount,finalObserved.maximum);

            if(reconciled.go!=null){
                SelectionPolicy.Decision d=SelectionPolicy.decision(finalObserved.selected,plan.configuredCount,finalObserved.maximum,true);
                if(d==SelectionPolicy.Decision.COMMIT_GO){
                    if(finalObserved.selected<finalEffective){
                        emit("SELECTION GO-OVERRIDE ✅ • GO enabled with selected="+finalObserved.selected+"/"+finalObserved.maximum+
                                " below configured/effective="+finalEffective+" • accept game's legal team");
                    }
                    stickySelectionPlanFloor=SelectionPolicy.advanceStickyFloor(stickySelectionPlanFloor,planIndex);
                    emit("SELECTION COMMIT ✅ • "+plan.name+" • selected="+finalObserved.selected+"/"+finalObserved.maximum+
                            " • effective-required="+finalEffective+" • GO enabled");
                    if(planIndex>0){
                        emit("SELECTION PLAN RETAIN ✅ • "+plan.name+" remains start plan for next cargo • floor="+stickySelectionPlanFloor);
                    }
                    // Non-idempotent: exactly one GO tap. Add a controller-
                    // level absolute bottom-right lock as a second independent
                    // defence. The centre drone is around the middle of the
                    // screen and can never pass this gate.
                    if(!Detector.isHardSafeGoPoint(reconciled.go.b,reconciled.go.p)){
                        emit("GO SAFETY REJECTED 🛑 • detector point not in absolute bottom-right • px=("+
                                Math.round(reconciled.go.p.x)+","+Math.round(reconciled.go.p.y)+")");
                        throw new RuntimeException("GO detector returned unsafe non-bottom-right point; refusing tap");
                    }
                    emit("GO SAFETY LOCK ✅ • absolute bottom-right + orange/red disc + white G/O glyphs");
                    tapMapped(reconciled.go.b,reconciled.go.p.x,reconciled.go.p.y,55,"GO COMMIT "+plan.name);
                    emit("GO SENT ✅ • plan="+plan.name+" • non-idempotent commit; no blind retry");
                    return new SelectionCommit(true,plan.name);
                }
                emit("GO CANDIDATE REJECTED ⚠️ • selected=0; refusing non-idempotent commit");
            }

            SelectionPolicy.Decision decision=SelectionPolicy.decision(finalObserved.selected,plan.configuredCount,finalObserved.maximum,false);
            if(decision==SelectionPolicy.Decision.GO_RECOVERY){
                throw new RuntimeException("GO/UI state recovery failed while selection count remained satisfied");
            }

            emit("SELECTION FALLBACK • "+plan.name+" insufficient after bounded GO reconcile • GO NOT SENT"+
                    " • selected="+finalObserved.selected+"/"+finalObserved.maximum+" • effective-required="+finalEffective);
            int nextIndex=nextEnabledSelectionPlanAfter(cfg,planIndex);
            if(nextIndex>=0){
                advanceSelectionPlan(cfg,round,kind,finalObserved,planIndex,nextIndex);
                planIndex=nextIndex;
                continue;
            }

            stickySelectionPlanFloor=SelectionPolicy.advanceStickyFloor(stickySelectionPlanFloor,planIndex);
            emit("SELECTION FALLBACK EXHAUSTED ⚠️ • all enabled plans insufficient • GO NOT SENT • sticky-floor="+stickySelectionPlanFloor);
            cancelAndResetSelection(cfg,round,kind,finalObserved);
            returnToExpeditionListAfterSelectionFailure(cfg,round);
            return new SelectionCommit(false,plan.name);
        }
        return new SelectionCommit(false,"none");
    }

    private SelectionObservedNow readSelectionObservedBounded(PilotConfig cfg,int attempts)throws Exception{
        for(int i=0;i<attempts&&running.get();i++){
            PilotAccessibilityService svc=waitForService();
            try{
                PilotAccessibilityService.SelectionCountResult tree=svc.readSelectionCountFromTree();
                if(tree!=null){
                    emit("SELECTION COUNT SOURCE ✅ • UI tree • "+tree.selected+"/"+tree.maximum);
                    return new SelectionObservedNow(tree.selected,tree.maximum,tree.source);
                }
            }catch(Throwable ignored){}

            Bitmap b=shot();
            try{
                java.util.List<CargoDetector.OcrItem> ocr=CargoDetector.recognize(b);
                if(CargoDetector.hasBusyToast(ocr)) emit("SELECTION DIAG • transient busy toast observed (not source of truth)");
                CargoDetector.SelectionObserved v=CargoDetector.selectionObserved(ocr);
                if(v!=null){
                    emit("SELECTION COUNT SOURCE ✅ • screenshot OCR • "+v.selected+"/"+v.maximum);
                    return new SelectionObservedNow(v.selected,v.maximum,v.source);
                }
            }catch(Throwable t){
                emit("SELECTION COUNT OCR RETRY • attempt="+(i+1)+"/"+attempts+" • "+t.getMessage());
            }
            sleep(cfg.fast?180:280);
        }
        return null;
    }

    private GoReconcileResult reconcileGoPreCommit(PilotConfig cfg,PilotConfig.SelectionPlan plan,
                                                     SelectionObservedNow initial)throws Exception{
        SelectionObservedNow observed=initial;
        boolean loadingSeen=false;
        boolean loadingStill=false;
        int stableNonLoading=0;
        int selectionMissingStreak=0;
        long started=android.os.SystemClock.uptimeMillis();
        final long minNoGoSettleMs=3000L;
        final long hardLoadingGuardMs=12000L;
        int attempt=0;

        while(running.get()){
            attempt++;
            Bitmap b=shot();
            PointF go=Detector.detectActiveGo(b);
            if(go!=null){
                String countText=observed==null?"unknown":(observed.selected+"/"+observed.maximum);
                emit("GO RECONCILE ✅ • state=enabled • attempt="+attempt+" • px=("+
                        Math.round(go.x)+","+Math.round(go.y)+") • selected="+countText);
                return new GoReconcileResult(new PointAndBitmap(go,b),observed,loadingSeen,false);
            }

            int placeholders=Detector.selectionLoadingPlaceholderCount(b);
            // Trust concrete placeholder rings, not the older broad grid-loading
            // heuristic by itself. The latter can stay true on some decor-heavy
            // accounts even after the roster is usable, producing endless
            // "loading" with placeholders=0.
            boolean broadLoading=Detector.isSelectionGridLoading(b);
            loadingStill=placeholders>=2 || (placeholders>0 && broadLoading);
            long elapsed=android.os.SystemClock.uptimeMillis()-started;
            if(loadingStill){
                loadingSeen=true;
                stableNonLoading=0;
                selectionMissingStreak=0;
                emit("SELECTION LOADING ⏳ • GO off • placeholders="+placeholders+
                        " • broad="+broadLoading+" • elapsed="+elapsed+"ms • Cancel/Fallback LOCKED • keep watching GO");
                if(elapsed>=hardLoadingGuardMs){
                    // Loading is not evidence of insufficiency. Some phones keep
                    // placeholder/transition pixels around for a long time even
                    // though taps are still accepted. Never Cancel/Fallback from
                    // this state. Keep watching the strict GO detector and let the
                    // user STOP manually if the network/game is genuinely stuck.
                    emit("SELECTION LOADING EXTENDED ⏳ • elapsed="+elapsed+
                            "ms • NO Cancel/Fallback • continue watching GO");
                }
                sleep(cfg.fast?520:760);
                continue;
            }

            stableNonLoading++;
            emit("GO RECONCILE • state=unknown/disabled • attempt="+attempt+
                    " • stable-nonloading="+stableNonLoading+" • elapsed="+elapsed+"ms • no fallback yet");

            SelectionObservedNow reread=readSelectionObservedBounded(cfg,1);
            boolean freshCountProof=reread!=null;
            if(reread!=null){
                observed=reread;
                int req=SelectionPolicy.effectiveRequired(plan.configuredCount,observed.maximum);
                emit("GO RECONCILE COUNT • selected="+observed.selected+"/"+observed.maximum+" • effective-required="+req+
                        " • GO remains source of truth if it becomes enabled");
            }

            java.util.List<CargoDetector.OcrItem> ocr;
            try{ocr=CargoDetector.recognize(b);}catch(Throwable t){ocr=java.util.Collections.emptyList();}
            Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(b);
            PointF cancelText=CargoDetector.cancelPoint(ocr);
            boolean cancelShape=false;
            if(cancelText==null && observed!=null && observed.selected>0){
                cancelShape=Detector.detectSelectionCancel(b)!=null;
            }
            // A freshly re-read selected/maximum counter is itself strong proof
            // that the selection sheet still exists. Do not abort/freeze fallback
            // merely because one screenshot misses both the canonical chip row
            // and the OCR header. Conversely, do not trust a stale prior count
            // forever: require three consecutive frames with NO fresh count,
            // filter row, selection header, or Cancel evidence before declaring
            // that the selection page actually disappeared.
            boolean selectionVisible=freshCountProof || row!=null || CargoDetector.hasSelectionHeader(ocr)
                    || cancelText!=null || cancelShape;
            if(selectionVisible){
                if(selectionMissingStreak>0){
                    emit("SELECTION PAGE VERIFY ✅ • evidence recovered after misses="+selectionMissingStreak+
                            " • count="+freshCountProof+" • filterRow="+(row!=null)+
                            " • header="+CargoDetector.hasSelectionHeader(ocr)+
                            " • cancel="+(cancelText!=null||cancelShape));
                }
                selectionMissingStreak=0;
            }else{
                selectionMissingStreak++;
                emit("SELECTION PAGE VERIFY ⚠️ • no current-frame proof • miss="+selectionMissingStreak+"/3"+
                        " • refusing fallback/Cancel until state is confirmed");
                if(selectionMissingStreak>=3 && elapsed>=minNoGoSettleMs){
                    throw new RuntimeException("selection page disappeared before GO commit after 3 confirmed misses; refusing retry/fallback");
                }
                sleep(cfg.fast?320:500);
                continue;
            }

            // Require a real settling window AND two consecutive non-loading
            // frames before insufficiency is allowed to trigger fallback.
            if(elapsed>=minNoGoSettleMs && stableNonLoading>=2){
                return new GoReconcileResult(null,observed,loadingSeen,false);
            }
            sleep(cfg.fast?320:500);
        }
        return new GoReconcileResult(null,observed,loadingSeen,loadingStill);
    }

    private void cancelAndResetSelection(PilotConfig cfg,int round,CargoDetector.Kind kind,SelectionObservedNow priorObserved)throws Exception{
        // Must remain pre-GO. This method is never called after GO SENT.
        for(int attempt=1;attempt<=2&&running.get();attempt++){
            Bitmap b=shot();
            java.util.List<CargoDetector.OcrItem> ocr;
            try{ocr=CargoDetector.recognize(b);}catch(Throwable t){ocr=java.util.Collections.emptyList();}
            if(CargoDetector.hasBusyToast(ocr)) emit("SELECTION DIAG • transient『似乎很忙』toast seen • diagnostic only");
            // OCR text is the authoritative Cancel proof.  In the zero-select
            // state the lower-left control is often a round BACK arrow, so do
            // not run the broad visual Cancel detector until after the safe
            // zero-select/no-Cancel bypass has been considered.
            PointF cancel=CargoDetector.cancelPoint(ocr);

            // Zero-select special case: there is nothing to clear. Some builds
            // show no Cancel (only a back arrow); others may still render a
            // Cancel pill. Either way, if selected==0, GO is absent, and the
            // selection page is proven, navigation is unnecessary and risky.
            // Switch the next fallback colour in-place.
            PointF goNow=Detector.detectActiveGo(b);
            Detector.FilterRowGeometry rowNow=Detector.detectFilterRowGeometry(b);
            boolean selectionVisible=rowNow!=null || CargoDetector.hasSelectionHeader(ocr);
            int selectedNow=priorObserved==null?-1:priorObserved.selected;
            if(cancel==null && selectedNow!=0){
                // One quick truth-source reread handles a stale prior counter.
                SelectionObservedNow reread=readSelectionObservedBounded(cfg,1);
                if(reread!=null) selectedNow=reread.selected;
            }
            if(SelectionPolicy.canSwitchFallbackInPlace(selectedNow,goNow!=null,cancel!=null,selectionVisible)){
                emit("SELECTION RESET BYPASS ✅ • selected=0 • GO absent • selection page visible"+
                        " • Cancel="+(cancel!=null?"visible-but-unneeded":"absent")+
                        " • switch fallback colour in-place; no navigation tap");
                return;
            }

            // Only a state with something selected is allowed to use the visual
            // Cancel-shape fallback. This prevents both the empty-selection BACK
            // arrow and a transient loading UI from being mistaken for Cancel.
            if(cancel==null && selectedNow>0) cancel=Detector.detectSelectionCancel(b);
            if(cancel==null)
                throw new RuntimeException("fallback reset 無『取消』且無法證明安全的 zero-select in-place 切色；拒絕疊加下一方案");

            // If GO is actually enabled, fallback must never proceed: GO is a
            // non-idempotent commit and the caller should have committed it in
            // reconcileGoPreCommit(). Fail closed rather than cancelling a legal
            // team due to a transient state mismatch.
            if(goNow!=null) throw new RuntimeException("fallback reset 前 GO 已亮；拒絕取消合法隊伍");

            tapMapped(b,cancel.x,cancel.y,70,"SELECTION CANCEL");
            sleep(cfg.fast?320:520);

            SelectionObservedNow after=readSelectionObservedBounded(cfg,2);
            if(after!=null&&after.selected==0){
                emit("SELECTION RESET ✅ • selected=0/"+after.maximum+" • source="+after.source);
                return;
            }

            // Cancel may leave the selection sheet and return to expedition detail.
            Bitmap state=shot();
            PointF pill=Detector.detectExpeditionCtaPill(state);
            if(pill!=null){
                emit("SELECTION RESET ✅ • cancel returned to expedition detail • re-enter selection");
                enterExpeditionSelectionPage(kind,round,cfg);
                SelectionObservedNow reentered=readSelectionObservedBounded(cfg,2);
                if(reentered!=null&&reentered.selected==0){
                    emit("SELECTION RESET ✅ • re-entered selected=0/"+reentered.maximum);
                    return;
                }
                // Fresh re-entry is enough to guarantee the previous selected set
                // was not carried forward even if the counter OCR is temporarily weak.
                if(Detector.detectFilterRowGeometry(shot())!=null){
                    emit("SELECTION RESET ✅ • fresh selection page verified by filter row");
                    return;
                }
            }
            emit("SELECTION RESET RETRY • attempt="+attempt+"/2");
        }
        throw new RuntimeException("fallback reset failed; refusing to stack Pikmin from previous plan");
    }

    private void returnToExpeditionListAfterSelectionFailure(PilotConfig cfg,int round)throws Exception{
        PilotAccessibilityService svc=waitForService();
        for(int i=1;i<=3&&running.get();i++){
            Bitmap b=shot();
            try{
                CargoDetector.Result r=CargoDetector.scan(b);
                int evidence=r.fruits.size()+r.seedlings.size()+r.blocked.size()+r.cards.size();
                if(r.navGuardProven&&evidence>=2){
                    emit("SELECTION ABORT RETURN ✅ • expedition list verified • evidence="+evidence);
                    return;
                }
            }catch(Throwable ignored){}
            boolean ok=svc.globalBack();
            emit("SELECTION ABORT BACK • attempt="+i+"/3 • dispatched="+ok);
            sleep(cfg.fast?500:800);
        }
        throw new RuntimeException("all selection plans insufficient and could not safely return to expedition list");
    }

    /**
     * The close button can become visible slightly before Pikmin Bloom accepts
     * input.  Also, some Android devices expose a screenshot buffer whose pixel
     * size differs from the gesture display space.  Re-sample after a short
     * settle, map screenshot coordinates into display coordinates, and verify
     * the screen actually left the carrying page before declaring success.
     */
    private boolean closeGreenX(PointAndBitmap initial,PilotConfig cfg)throws Exception{
        // Keep structural verification anchored to the first real bottom-left X.
        // The tap point is tracked separately: glyph detection already lands on
        // the white X itself; the legacy green-component path is refined toward
        // the white cross so gradient fills cannot bias the gesture off-centre.
        PointF structuralAnchor=initial.p;
        Bitmap anchorFrame=initial.b;
        PointF initialGlyph=Detector.detectCarryingCloseGlyph(initial.b);
        PointF tapAnchor=(initialGlyph!=null&&Math.hypot(initialGlyph.x-initial.p.x,initialGlyph.y-initial.p.y)
                <=Math.max(24f,Math.min(initial.b.getWidth(),initial.b.getHeight())*0.055f))
                ?initialGlyph:Detector.refineCarryingCloseTapPoint(initial.b,initial.p);
        if(tapAnchor==null) tapAnchor=structuralAnchor;

        float shortEdge=Math.min(initial.b.getWidth(),initial.b.getHeight());
        float tolerance=Math.max(24f,shortEdge*0.055f);
        int stableHits=1;

        // The initial waitPoint() hit already proves one anchored frame. Require
        // one more near the same anchor, but do not erase that proof because of
        // a single transient detector miss while the carrying page animates in.
        for(int i=0;i<5&&running.get()&&stableHits<2;i++){
            sleep(cfg.fast?110:170);
            Bitmap b=shot();
            PointF p=Detector.detectCarryingClose(b);
            if(p!=null&&Math.hypot(p.x-structuralAnchor.x,p.y-structuralAnchor.y)<=tolerance){
                stableHits++;
                structuralAnchor=new PointF((structuralAnchor.x+p.x)*0.5f,(structuralAnchor.y+p.y)*0.5f);
                anchorFrame=b;
                PointF glyph=Detector.detectCarryingCloseGlyph(b);
                PointF refined=(glyph!=null&&Math.hypot(glyph.x-p.x,glyph.y-p.y)<=tolerance)
                        ?glyph:Detector.refineCarryingCloseTapPoint(b,p);
                tapAnchor=refined==null?p:refined;
                emit("GREEN X VERIFY • anchored • hits="+stableHits+"/2 • structural=("+
                        Math.round(structuralAnchor.x)+","+Math.round(structuralAnchor.y)+") • tap=("+
                        Math.round(tapAnchor.x)+","+Math.round(tapAnchor.y)+")");
            }else{
                if(p!=null) emit("GREEN X VERIFY • rejected jump • candidate=("+
                        Math.round(p.x)+","+Math.round(p.y)+") anchor=("+
                        Math.round(structuralAnchor.x)+","+Math.round(structuralAnchor.y)+") • retained-hits="+stableHits+"/2");
                else emit("GREEN X VERIFY • transient miss • retained-hits="+stableHits+"/2");
            }
        }
        if(stableHits<2){
            emit("GREEN X REJECTED ⚠️ • no second anchored bottom-left proof");
            return false;
        }

        // A retry is allowed only while we still have a recent positive proof of
        // THIS anchored X.  This prevents a stale coordinate from being tapped on
        // some unrelated page when one screenshot happens to miss the detector.
        boolean retryArmed=true;

        for(int attempt=1;attempt<=4&&running.get();attempt++){
            Bitmap fresh=shot();
            PointF current=Detector.detectCarryingClose(fresh);
            if(current!=null&&Math.hypot(current.x-structuralAnchor.x,current.y-structuralAnchor.y)<=tolerance){
                structuralAnchor=new PointF((structuralAnchor.x+current.x)*0.5f,(structuralAnchor.y+current.y)*0.5f);
                anchorFrame=fresh;
                PointF glyph=Detector.detectCarryingCloseGlyph(fresh);
                PointF refined=(glyph!=null&&Math.hypot(glyph.x-current.x,glyph.y-current.y)<=tolerance)
                        ?glyph:Detector.refineCarryingCloseTapPoint(fresh,current);
                tapAnchor=refined==null?current:refined;
                retryArmed=true;
                emit("GREEN X TARGET ✅ • attempt="+attempt+" • structural=("+
                        Math.round(structuralAnchor.x)+","+Math.round(structuralAnchor.y)+") • tap=("+
                        Math.round(tapAnchor.x)+","+Math.round(tapAnchor.y)+")");
            }else if(current!=null){
                emit("GREEN X TARGET • unrelated candidate ignored before tap • candidate=("+
                        Math.round(current.x)+","+Math.round(current.y)+") • keep anchored target");
            }else{
                emit("GREEN X TARGET • reacquire flicker"+(retryArmed?" • last anchored proof still valid":" • retry not armed"));
            }

            if(!retryArmed){
                // Do not blindly tap a stale coordinate. First prove either that
                // the destination list has arrived, or that the same X is still
                // present and therefore safe to retry.
                boolean resolved=false;
                for(int r=0;r<5&&running.get();r++){
                    sleep(cfg.fast?140:220);
                    Bitmap probe=shot();
                    PointF p=Detector.detectCarryingClose(probe);
                    if(p!=null&&Math.hypot(p.x-structuralAnchor.x,p.y-structuralAnchor.y)<=tolerance){
                        structuralAnchor=p; anchorFrame=probe; retryArmed=true; resolved=true;
                        PointF glyph=Detector.detectCarryingCloseGlyph(probe);
                        PointF refined=(glyph!=null&&Math.hypot(glyph.x-p.x,glyph.y-p.y)<=tolerance)
                                ?glyph:Detector.refineCarryingCloseTapPoint(probe,p);
                        tapAnchor=refined==null?p:refined;
                        emit("GREEN-X STATE ✅ • same anchored X reacquired before retry");
                        break;
                    }
                    if(greenXDestinationProven(probe,true)) return true;
                    emit("GREEN-X STATE • unresolved frame "+(r+1)+"/5 • no anchored X and no expedition-list proof");
                }
                if(!resolved&&!retryArmed){
                    emit("GREEN X RETRY BLOCKED ⚠️ • destination not proven and anchored X not reacquired; refusing blind tap");
                    return false;
                }
            }

            PilotAccessibilityService activeService=waitForService();
            PilotAccessibilityService.TapResult tr=activeService.tapFromBitmap(
                    anchorFrame,tapAnchor.x,tapAnchor.y,cfg.fast?105:125).get(4,TimeUnit.SECONDS);
            emit("GREEN X TAP #"+attempt+" • refined screenshot=("+
                    Math.round(tr.sourceX)+","+Math.round(tr.sourceY)+")/"+
                    tr.sourceWidth+"×"+tr.sourceHeight+" → display=("+
                    Math.round(tr.displayX)+","+Math.round(tr.displayY)+")/"+
                    tr.displayWidth+"×"+tr.displayHeight+" • dispatch="+
                    (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));

            if(!tr.accepted||!tr.completed){
                sleep(cfg.fast?120:180);
                // Dispatch failure means the screen did not acknowledge the
                // gesture. Keep the existing anchored proof for the next retry.
                retryArmed=true;
                continue;
            }

            // alpha35 retains the alpha34 ACK contract: X absence by itself is NOT success. Some
            // frames can miss the X detector even while the carrying page is
            // still on screen. Success now requires positive proof that the
            // Expedition list has returned. If the same anchored X is still
            // visible, we explicitly arm a controlled retry.
            retryArmed=false;
            boolean sameXSeen=false;
            for(int v=0;v<7&&running.get();v++){
                sleep(cfg.fast?140:220);
                Bitmap verify=shot();
                PointF p=Detector.detectCarryingClose(verify);
                if(p!=null&&Math.hypot(p.x-structuralAnchor.x,p.y-structuralAnchor.y)<=tolerance){
                    sameXSeen=true; retryArmed=true;
                    structuralAnchor=p; anchorFrame=verify;
                    PointF glyph=Detector.detectCarryingCloseGlyph(verify);
                    PointF refined=(glyph!=null&&Math.hypot(glyph.x-p.x,glyph.y-p.y)<=tolerance)
                            ?glyph:Detector.refineCarryingCloseTapPoint(verify,p);
                    tapAnchor=refined==null?p:refined;
                    emit("GREEN-X ACK • same X still visible @("+
                            Math.round(p.x)+","+Math.round(p.y)+") • retry armed • nextTap=("+
                            Math.round(tapAnchor.x)+","+Math.round(tapAnchor.y)+")");
                    // Once we have positive same-X evidence there is no value in
                    // waiting through the rest of this ACK window; retry safely.
                    break;
                }

                if(p!=null) emit("GREEN-X ACK • unrelated X-like candidate ignored • checking destination page");
                else emit("GREEN-X ACK • anchored X not detected • checking destination page");

                if(greenXDestinationProven(verify,true)) return true;
                emit("GREEN-X ACK • ambiguous • X miss is not treated as success");
            }

            if(sameXSeen){
                emit("GREEN X STILL VISIBLE ⚠️ • retrying same anchored control");
                continue;
            }

            // No same-X proof and no destination proof yet. Keep observing for a
            // short bounded window; do not either declare success or blindly tap.
            for(int r=0;r<5&&running.get()&&!retryArmed;r++){
                sleep(cfg.fast?160:240);
                Bitmap probe=shot();
                PointF p=Detector.detectCarryingClose(probe);
                if(p!=null&&Math.hypot(p.x-structuralAnchor.x,p.y-structuralAnchor.y)<=tolerance){
                    structuralAnchor=p; anchorFrame=probe; retryArmed=true;
                    PointF glyph=Detector.detectCarryingCloseGlyph(probe);
                    PointF refined=(glyph!=null&&Math.hypot(glyph.x-p.x,glyph.y-p.y)<=tolerance)
                            ?glyph:Detector.refineCarryingCloseTapPoint(probe,p);
                    tapAnchor=refined==null?p:refined;
                    emit("GREEN-X ACK RECOVER ✅ • anchored X reacquired • retry armed");
                    break;
                }
                if(greenXDestinationProven(probe,true)) return true;
                emit("GREEN-X ACK RECOVER • frame "+(r+1)+"/5 unresolved");
            }

            if(!retryArmed){
                emit("GREEN X ACK UNRESOLVED ⚠️ • no expedition-list proof and no anchored X proof; refusing stale-coordinate retry");
                return false;
            }
        }
        return false;
    }

    /**
     * Positive transition ACK for closing the carrying page.
     *
     * alpha35 keeps alpha34's safety contract (X miss alone is never success),
     * but restores a fast normal path: first look for the selected Expedition
     * tab pill with cheap pixel geometry.  Only if that strict visual proof is
     * absent do we pay for the full ML Kit OCR + card/list scan.
     */
    private boolean greenXDestinationProven(Bitmap b,boolean logMiss){
        PointF fast=Detector.detectExpeditionTabPill(b);
        if(fast!=null){
            emit("GREEN-X ACK DESTINATION FAST ✅ • expedition tab pill @("+
                    Math.round(fast.x)+","+Math.round(fast.y)+") • OCR skipped");
            return true;
        }

        try{
            CargoDetector.Result r=CargoDetector.scan(b);
            int evidence=r.fruits.size()+r.seedlings.size()+r.blocked.size()+r.cards.size();
            boolean ok=r.navGuardProven&&evidence>=1;
            if(ok){
                emit("GREEN-X ACK DESTINATION OCR ✅ • expedition list proven • nav="+r.navGuardSource+" • evidence="+evidence);
                return true;
            }
            if(logMiss) emit("GREEN-X ACK DESTINATION • unproven • fast-pill=MISS • nav="+r.navGuardProven+
                    " source="+r.navGuardSource+" • evidence="+evidence);
        }catch(Throwable t){
            if(logMiss) emit("GREEN-X ACK DESTINATION • probe error="+t.getClass().getSimpleName()+
                    (t.getMessage()==null?"":" • "+t.getMessage()));
        }
        return false;
    }

    private static String dispatchKey(CargoDetector.Candidate c){
        if(c==null||c.label==null)return "";
        String k=c.label.replaceAll("\\s+","")
                .replace('籃','藍').replace('蓝','藍').replace('苖','苗');
        return k;
    }


    private void temporarilySkip(CargoAndBitmap choice,int round){
        if(choice==null||choice.item==null||choice.b==null)return;
        String key=dispatchKey(choice.item);
        float nx=choice.item.center.x/Math.max(1f,choice.b.getWidth());
        float ny=choice.item.center.y/Math.max(1f,choice.b.getHeight());
        failedSelectionSkips.addLast(new RecentDispatch(choice.item.kind,key,nx,ny,round));
        while(failedSelectionSkips.size()>6)failedSelectionSkips.removeFirst();
        emit("SELECTION-FAILED SKIP LATCH ✅ • key="+key+" • round="+round);
    }

    private boolean isFailedSelectionSkip(CargoDetector.Candidate c,Bitmap b,int round){
        if(c==null||b==null)return false;
        String key=dispatchKey(c);float nx=c.center.x/Math.max(1f,b.getWidth()),ny=c.center.y/Math.max(1f,b.getHeight());
        for(RecentDispatch r:failedSelectionSkips){
            if(round-r.round>2||r.kind!=c.kind)continue;
            boolean sameKey=!key.isEmpty()&&key.equals(r.key);
            boolean sameSlot=Math.abs(nx-r.nx)<=0.035f&&Math.abs(ny-r.ny)<=0.045f;
            if(sameKey&&sameSlot)return true;
        }
        return false;
    }

    private void rememberDispatch(CargoAndBitmap choice,int round){
        if(choice==null||choice.item==null||choice.b==null)return;
        String key=dispatchKey(choice.item);
        if(key.isEmpty())return;
        float nx=choice.item.center.x/Math.max(1f,choice.b.getWidth());
        float ny=choice.item.center.y/Math.max(1f,choice.b.getHeight());
        recentDispatches.addLast(new RecentDispatch(choice.item.kind,key,nx,ny,round));
        while(recentDispatches.size()>6)recentDispatches.removeFirst();
        emit("RECENT-DISPATCH EXACT ✅ • key="+key+
                " • norm=("+String.format(java.util.Locale.US,"%.3f",nx)+","+
                String.format(java.util.Locale.US,"%.3f",ny)+")");
    }

    private boolean isExactRecentDispatch(CargoDetector.Candidate c,Bitmap b,int round){
        if(c==null||b==null)return false;
        String key=dispatchKey(c);
        if(key.isEmpty())return false;
        float nx=c.center.x/Math.max(1f,b.getWidth()),ny=c.center.y/Math.max(1f,b.getHeight());
        for(RecentDispatch r:recentDispatches){
            if(round-r.round>3||r.kind!=c.kind||!r.key.equals(key))continue;
            if(Math.abs(nx-r.nx)<=0.030f&&Math.abs(ny-r.ny)<=0.040f)return true;
        }
        return false;
    }

    private void stage(String code,String human){
        currentStage=code; status(human); emit("STAGE["+code+"] • "+human);
    }

    private String classifyFailure(Throwable t){
        String m=t==null||t.getMessage()==null?"":t.getMessage().toLowerCase(java.util.Locale.ROOT);
        if(m.contains("screenshot"))return "E_SCREENSHOT";
        if(m.contains("前往探險")||m.contains("cta"))return "E_CTA";
        if(m.contains("顏色圓圈")||m.contains("filter"))return "E_FILTER";
        if(m.contains("roster still loading")||(m.contains("roster")&&m.contains("loading")))return "E_SELECTION_LOADING";
        if(m.contains("selection count")||m.contains("fallback reset")||m.contains("selection plans")||m.contains("selected/maximum"))return "E_SELECTION_STATE";
        if(m.contains("格線")||m.contains("pikmin grid"))return "E_PIKMIN_GRID";
        if(m.contains("go ")||m.startsWith("go")||m.contains("go未")||m.contains("go 未"))return "E_GO";
        if(m.contains("green x")||m.contains("綠色 x")||m.contains("綠色x"))return "E_GREEN_X_ACK";
        if(m.contains("tap cancelled")||m.contains("swipe cancelled")||m.contains("gesture"))return "E_GESTURE";
        if(m.contains("ocr")||m.contains("ml kit"))return "E_OCR";
        return "E_UNKNOWN";
    }

    private interface PointDetector{PointF find(Bitmap b);}
    private PointAndBitmap waitPoint(String name,int attempts,long delay,PointDetector d)throws Exception{
        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();
            PointF p=d.find(b);
            if(p!=null){
                emit(name+" detected ✅ • px=("+Math.round(p.x)+","+Math.round(p.y)+") • norm=("+
                        String.format(java.util.Locale.US,"%.3f",p.x/Math.max(1f,b.getWidth()))+","+
                        String.format(java.util.Locale.US,"%.3f",p.y/Math.max(1f,b.getHeight()))+")");
                return new PointAndBitmap(p,b);
            }
            if("綠色 X".equals(name)) emit("GREEN X SCAN • attempt="+(i+1)+"/"+attempts+" • not found");
            sleep(delay);
        }
        return null;
    }

    private static final class PointAndBitmap{final PointF p;final Bitmap b;PointAndBitmap(PointF p,Bitmap b){this.p=p;this.b=b;}}
    private static final class CargoAndBitmap{final CargoDetector.Candidate item;final Bitmap b;CargoAndBitmap(CargoDetector.Candidate i,Bitmap b){item=i;this.b=b;}}

    private PilotAccessibilityService waitForService()throws Exception{
        PilotAccessibilityService s=service;
        if(s==null){s=PilotAccessibilityService.get();if(s!=null)service=s;}
        if(s!=null)return s;

        // If Pilot is not running (e.g. Test Screenshot), fail immediately.
        if(!running.get())throw new IllegalStateException("Accessibility service is not enabled");

        long started=SystemClock.elapsedRealtime();
        boolean announced=false;
        while(running.get()){
            s=service;
            if(s==null){s=PilotAccessibilityService.get();if(s!=null)service=s;}
            if(s!=null){
                if(announced)emit("ACCESSIBILITY WAIT END ✅ • service available • stage="+currentStage);
                return s;
            }
            if(!announced){
                announced=true;
                emit("ACCESSIBILITY WAIT ⏸️ • no bound service • stage="+currentStage+" • preserving round state");
            }
            if(SystemClock.elapsedRealtime()-started>10*60*1000L)
                throw new RuntimeException("Accessibility service reconnect timeout (10 min)");
            synchronized(serviceLock){serviceLock.wait(1000L);}
        }
        throw new InterruptedException("stopped");
    }

    private void requireService()throws Exception{waitForService();}
    private static boolean isScreenshotIntervalShort(Throwable t){
        Throwable cur=t;
        while(cur!=null){
            String m=cur.getMessage();
            if(m!=null&&m.contains("takeScreenshot error=3"))return true;
            cur=cur.getCause();
        }
        return false;
    }

    private Bitmap shot()throws Exception{
        Throwable last=null;
        int hardAttempt=0;
        int throttleRetry=0;
        while(running.get()&&hardAttempt<3){
            PilotAccessibilityService s=waitForService();
            try{
                Bitmap b=s.screenshot().get(5,TimeUnit.SECONDS);
                if(b==null)throw new RuntimeException("screenshot returned null");
                return b;
            }catch(Throwable t){
                last=t;
                boolean serviceChanged=(service!=s || PilotAccessibilityService.get()!=s);
                if(isScreenshotIntervalShort(t)&&throttleRetry<8){
                    throttleRetry++;
                    long wait=Math.min(1400L,650L+(throttleRetry-1)*120L);
                    emit("SCREENSHOT THROTTLE ⏳ • Android error=3 (interval too short) • retry="+
                            throttleRetry+"/8 • wait="+wait+"ms • stage="+currentStage);
                    sleep(wait);
                    continue;
                }
                hardAttempt++;
                emit("SCREENSHOT RETRY ⚠️ • attempt="+hardAttempt+"/3 • serviceChanged="+serviceChanged+
                        " • error="+(t.getMessage()==null?t.getClass().getSimpleName():t.getMessage()));
                if(hardAttempt<3) sleep(serviceChanged?220:500);
            }
        }
        if(last instanceof Exception)throw (Exception)last;
        throw new RuntimeException("screenshot failed after retries",last);
    }
    private void tap(float x,float y,long ms)throws Exception{
        if(!running.get())throw new InterruptedException("stopped");
        PilotAccessibilityService s=waitForService();
        if(!s.tap(x,y,ms).get(3,TimeUnit.SECONDS))throw new RuntimeException("tap cancelled");
    }
    private void tapMapped(Bitmap frame,float x,float y,long ms,String label)throws Exception{
        if(!running.get())throw new InterruptedException("stopped");
        PilotAccessibilityService s=waitForService();
        PilotAccessibilityService.TapResult tr=s.tapFromBitmap(frame,x,y,ms).get(4,TimeUnit.SECONDS);
        emit(label+" TAP • screenshot=("+Math.round(tr.sourceX)+","+Math.round(tr.sourceY)+")/"+
                tr.sourceWidth+"×"+tr.sourceHeight+" → display=("+Math.round(tr.displayX)+","+Math.round(tr.displayY)+")/"+
                tr.displayWidth+"×"+tr.displayHeight+" • dispatch="+
                (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));
        if(!tr.accepted||!tr.completed)throw new RuntimeException(label+" tap cancelled");
    }

    private void swipeMapped(Bitmap frame,float x1,float y1,float x2,float y2,long ms,String label)throws Exception{
        if(!running.get())throw new InterruptedException("stopped");
        PilotAccessibilityService s=waitForService();
        if(!s.swipeFromBitmap(frame,x1,y1,x2,y2,ms).get(4,TimeUnit.SECONDS))
            throw new RuntimeException(label+" swipe cancelled");
    }

    private void swipe(float x1,float y1,float x2,float y2,long ms)throws Exception{
        if(!running.get())throw new InterruptedException("stopped");
        PilotAccessibilityService s=waitForService();
        if(!s.swipe(x1,y1,x2,y2,ms).get(4,TimeUnit.SECONDS))throw new RuntimeException("swipe cancelled");
    }
    private void sleep(long ms)throws InterruptedException{long left=ms;while(left>0&&running.get()){long n=Math.min(100,left);Thread.sleep(n);left-=n;}if(!running.get())throw new InterruptedException("stopped");}
    private void emit(String s){
        long base=runStartUptime;
        String line=base>0?(String.format(java.util.Locale.US,"[T+%.2fs] %s",(SystemClock.elapsedRealtime()-base)/1000.0,s)):s;
        android.util.Log.i("PikminPilot",line);Listener l=listener;if(l!=null)main.post(()->l.onLog(line));
    }
    private void status(String s){Listener l=listener;if(l!=null)main.post(()->l.onStatus(s));}

    public void testScreenshot(java.util.function.Consumer<String> callback){
        worker.execute(()->{try{
            requireService();
            Bitmap b=shot();
            CargoDetector.Result c=CargoDetector.scan(b);
            PointF e=Detector.detectExpeditionButton(b),g=Detector.detectActiveGo(b),x=Detector.detectCarryingClose(b);
            PointF seedCta=Detector.detectSeedlingExpeditionCta(b);
            Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(b);
            String xText=x==null?"greenX=false":("greenX=true@("+Math.round(x.x)+","+Math.round(x.y)+")");
            String ctaText=seedCta==null?"seedlingCTA=false":("seedlingCTA=true@("+Math.round(seedCta.x)+","+Math.round(seedCta.y)+")");
            String rowText=row==null?"filterRow=false":("filterRow=true@y="+Math.round(row.y)+" chips="+row.chipCount+" spacing="+Math.round(row.spacing));
            String r="BUILD 0.4.8-alpha35 • single-tap filter + hard GO lock + BUSY preflight • Screenshot "+b.getWidth()+"×"+b.getHeight()+
                    " • fruit="+c.fruits.size()+" • seedling="+c.seedlings.size()+" • blocked="+c.blocked.size()+
                    " • expedition="+(e!=null)+" • GO="+(g!=null)+" • "+ctaText+" • "+rowText+" • "+xText;
            main.post(()->callback.accept(r));
        }catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
