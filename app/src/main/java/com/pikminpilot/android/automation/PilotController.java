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
        completed=0; recentDispatches.clear(); failedSelectionSkips.clear(); runStartUptime=SystemClock.elapsedRealtime(); currentStage="START";
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
            emit("BUILD 0.4.0-alpha22 • zero-select in-place fallback • candidate-local clipped BUSY guard • strict bottom-right GO • fallback 岩/紫/粉/白");
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
            if(!available.isEmpty()) return new CargoAndBitmap(available.get(0),b);

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
    private void applyPikminFilterWithAck(PointAndBitmap filter,PilotConfig cfg,int round,PilotConfig.PikminType targetType)throws Exception{
        PointAndBitmap current=filter;
        Detector.FilterLattice beforeLattice=Detector.detectFilterLattice(current.b);
        float beforeScore=Detector.filterRowSaturationScore(current.b,beforeLattice);

        for(int tapTry=1;tapTry<=2&&running.get();tapTry++){
            tapMapped(current.b,current.p.x,current.p.y,55,"PIKMIN FILTER");
            sleep(cfg.fast?220:320);

            Bitmap after=shot();
            Detector.FilterLattice afterLattice=Detector.detectFilterLattice(after);
            float afterScore=Detector.filterRowSaturationScore(after,afterLattice);

            if(beforeScore>=0.52f&&afterScore>=0f){
                emit("PIKMIN FILTER ACK • try="+tapTry+
                        " • saturation="+String.format(java.util.Locale.US,"%.3f",beforeScore)+
                        " → "+String.format(java.util.Locale.US,"%.3f",afterScore));
                if(afterScore<=beforeScore*0.82f){
                    emit("PIKMIN FILTER ACK ✅ • row dimmed after tap");
                    return;
                }
            }else{
                // A row can already be dim when the game carried the previous
                // filter state into the next selection page.  The slot location
                // is still lattice-proven, so don't manufacture a false failure.
                emit("PIKMIN FILTER ACK WEAK • pre/post saturation proof unavailable or already dim"+
                        " • before="+String.format(java.util.Locale.US,"%.3f",beforeScore)+
                        " • after="+String.format(java.util.Locale.US,"%.3f",afterScore));
                return;
            }

            if(tapTry==1){
                if(afterLattice==null){
                    emit("PIKMIN FILTER RETRY ABORT ⚠️ • row disappeared after tap");
                    throw new RuntimeException("皮克敏顏色圓圈點擊後狀態無法確認");
                }
                float tx=afterLattice.targetX(targetType);
                if(tx<after.getWidth()*0.055f||tx>after.getWidth()*0.945f||
                        !Detector.filterTargetLooksPlausible(after,afterLattice,targetType)){
                    emit("PIKMIN FILTER RETRY ABORT ⚠️ • target slot no longer proven");
                    throw new RuntimeException("皮克敏顏色圓圈點擊後目標位置無法確認");
                }
                emit("PIKMIN FILTER RETRY #2 • same canonical slot • x="+Math.round(tx)+
                        " • y="+Math.round(afterLattice.rowY));
                current=new PointAndBitmap(new PointF(tx,afterLattice.rowY),after);
                beforeLattice=afterLattice;
                beforeScore=afterScore;
            }
        }
        throw new RuntimeException("皮克敏顏色圓圈點擊未生效");
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
    private void selectPikminFastGeometric(PilotConfig cfg,int round,int desired)throws Exception{
        Bitmap frame=shot();
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
        GoReconcileResult(PointAndBitmap go,SelectionObservedNow observed){this.go=go;this.observed=observed;}
    }

    /**
     * Pre-GO selection fallback chain. selected/maximum describes the live team,
     * while an enabled GO is authoritative proof that the game accepts that team.
     * The transient "似乎很忙" toast remains diagnostic only.
     */
    private SelectionCommit runSelectionPlans(PilotConfig cfg,int round,CargoDetector.Kind kind)throws Exception{
        java.util.List<PilotConfig.SelectionPlan> plans=new java.util.ArrayList<>();
        for(PilotConfig.SelectionPlan p:cfg.selectionPlans) if(p.enabled) plans.add(p);
        if(plans.isEmpty()) throw new RuntimeException("沒有啟用的皮克敏選擇方案");

        for(int pi=0;pi<plans.size()&&running.get();pi++){
            PilotConfig.SelectionPlan plan=plans.get(pi);
            stage("FILTER","第 "+round+" 輪："+plan.name+" • "+PilotConfig.pikminName(plan.type)+"皮");
            emit("SELECTION PLAN • "+plan.name+" • type="+PilotConfig.pikminName(plan.type)+"皮 • configured="+plan.configuredCount);

            PointAndBitmap filter=findPikminFilterAdaptive(cfg,round,plan.type);
            if(filter==null) throw new RuntimeException(plan.name+" "+PilotConfig.pikminName(plan.type)+"皮克敏顏色圓圈未辨識到");
            emit("PIKMIN FILTER TARGET ✅ • plan="+plan.name+" • type="+PilotConfig.pikminName(plan.type)+
                    " • px=("+Math.round(filter.p.x)+","+Math.round(filter.p.y)+")"+
                    " • norm=("+String.format(java.util.Locale.US,"%.3f",filter.p.x/Math.max(1f,filter.b.getWidth()))+","+
                    String.format(java.util.Locale.US,"%.3f",filter.p.y/Math.max(1f,filter.b.getHeight()))+") • detector=canonical-chip-lattice");
            applyPikminFilterWithAck(filter,cfg,round,plan.type);

            stage("SELECT","第 "+round+" 輪："+plan.name+" 選擇 "+plan.configuredCount+" 隻");
            selectPikminFastGeometric(cfg,round,plan.configuredCount);

            SelectionObservedNow observed=readSelectionObservedBounded(cfg,4);
            if(observed==null) throw new RuntimeException("selection count 無法讀取 selected/maximum");
            int effective=SelectionPolicy.effectiveRequired(plan.configuredCount,observed.maximum);
            emit("SELECTION COUNT • selected="+observed.selected+"/"+observed.maximum+
                    " • configured="+plan.configuredCount+" • effective-required="+effective+
                    " • source="+observed.source);

            // IMPORTANT: do NOT fallback merely because selected < configured/effective.
            // Pikmin Bloom can enable GO with a smaller legal team (e.g. 4/12).
            // The enabled GO is authoritative dispatchability evidence. First do
            // a bounded GO reconcile; only if GO stays absent do counts decide
            // whether this is a real insufficiency or a GO/UI recovery problem.
            stage("GO","第 "+round+" 輪："+plan.name+" • 確認 GO");
            GoReconcileResult reconciled=reconcileGoPreCommit(cfg,plan,observed);
            SelectionObservedNow finalObserved=reconciled.observed==null?observed:reconciled.observed;
            int finalEffective=SelectionPolicy.effectiveRequired(plan.configuredCount,finalObserved.maximum);

            if(reconciled.go!=null){
                SelectionPolicy.Decision d=SelectionPolicy.decision(finalObserved.selected,plan.configuredCount,finalObserved.maximum,true);
                if(d==SelectionPolicy.Decision.COMMIT_GO){
                    if(finalObserved.selected<finalEffective){
                        emit("SELECTION GO-OVERRIDE ✅ • GO enabled with selected="+finalObserved.selected+"/"+finalObserved.maximum+
                                " below configured/effective="+finalEffective+" • accept game's legal team");
                    }
                    emit("SELECTION COMMIT ✅ • "+plan.name+" • selected="+finalObserved.selected+"/"+finalObserved.maximum+
                            " • effective-required="+finalEffective+" • GO enabled");
                    // Non-idempotent: exactly one GO tap. Never blind retry after this line.
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
            if(pi+1<plans.size()){
                cancelAndResetSelection(cfg,round,kind,finalObserved);
                continue;
            }

            emit("SELECTION FALLBACK EXHAUSTED ⚠️ • all enabled plans insufficient • GO NOT SENT");
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
        for(int i=0;i<3&&running.get();i++){
            Bitmap b=shot();
            PointF go=Detector.detectActiveGo(b);
            if(go!=null){
                emit("GO RECONCILE ✅ • state=enabled • attempt="+(i+1)+"/3 • px=("+
                        Math.round(go.x)+","+Math.round(go.y)+") • selected="+observed.selected+"/"+observed.maximum);
                return new GoReconcileResult(new PointAndBitmap(go,b),observed);
            }
            emit("GO RECONCILE • state=unknown/disabled • attempt="+(i+1)+"/3 • no fallback yet");
            if(i<2){
                SelectionObservedNow reread=readSelectionObservedBounded(cfg,1);
                if(reread!=null){
                    observed=reread;
                    int req=SelectionPolicy.effectiveRequired(plan.configuredCount,observed.maximum);
                    emit("GO RECONCILE COUNT • selected="+observed.selected+"/"+observed.maximum+" • effective-required="+req+
                            " • GO remains source of truth if it becomes enabled");
                }
                Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(b);
                if(row==null) throw new RuntimeException("selection page disappeared before GO commit; refusing retry/fallback");
                sleep(cfg.fast?220:360);
            }
        }
        return new GoReconcileResult(null,observed);
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

            // Zero-select special case: when this colour has literally no
            // selectable Pikmin, Pikmin Bloom shows neither an enabled GO nor
            // the lower-left 「取消」 pill (the lower-left control may instead
            // be a simple back arrow).  There is nothing to clear, so pressing
            // a guessed control is both unnecessary and dangerous.  Prove we
            // are still on the selection page, prove GO is absent, and switch
            // the next fallback colour in-place.
            PointF goNow=Detector.detectActiveGo(b);
            Detector.FilterRowGeometry rowNow=Detector.detectFilterRowGeometry(b);
            boolean selectionVisible=rowNow!=null || CargoDetector.hasSelectionHeader(ocr);
            int selectedNow=priorObserved==null?-1:priorObserved.selected;
            if(cancel==null && selectedNow!=0){
                // One quick truth-source reread handles a stale prior counter.
                SelectionObservedNow reread=readSelectionObservedBounded(cfg,1);
                if(reread!=null) selectedNow=reread.selected;
            }
            if(cancel==null && SelectionPolicy.canSwitchFallbackInPlace(selectedNow,goNow!=null,false,selectionVisible)){
                emit("SELECTION RESET BYPASS ✅ • selected=0 • GO absent • no Cancel • lower-left back arrow untouched • switch fallback colour in-place");
                return;
            }

            // Only a state with something selected is allowed to use the visual
            // Cancel-shape fallback.  This prevents the empty-selection BACK
            // arrow from ever being mistaken for Cancel.
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
        // The close button is fixed to Pikmin Bloom's bottom-left control area.
        // Keep every verification anchored to the first real candidate so a
        // decorative white X elsewhere can never replace the tap target.
        PointF anchor=initial.p;
        Bitmap anchorFrame=initial.b;
        float shortEdge=Math.min(initial.b.getWidth(),initial.b.getHeight());
        float tolerance=Math.max(24f,shortEdge*0.055f);
        int stable=1;

        for(int i=0;i<5&&running.get()&&stable<2;i++){
            sleep(cfg.fast?110:170);
            Bitmap b=shot();
            PointF p=Detector.detectCarryingClose(b);
            if(p!=null&&Math.hypot(p.x-anchor.x,p.y-anchor.y)<=tolerance){
                stable++;
                anchor=new PointF((anchor.x+p.x)*0.5f,(anchor.y+p.y)*0.5f);
                anchorFrame=b;
                emit("GREEN X VERIFY • anchored • streak="+stable+"/2 • px=("+
                        Math.round(anchor.x)+","+Math.round(anchor.y)+")");
            }else{
                if(p!=null) emit("GREEN X VERIFY • rejected jump • candidate=("+
                        Math.round(p.x)+","+Math.round(p.y)+") anchor=("+
                        Math.round(anchor.x)+","+Math.round(anchor.y)+")");
                else emit("GREEN X VERIFY • anchored target not present yet");
                stable=0;
            }
        }
        if(stable<2){
            emit("GREEN X REJECTED ⚠️ • no stable bottom-left anchored target");
            return false;
        }

        for(int attempt=1;attempt<=4&&running.get();attempt++){
            // Reacquire near the same anchor immediately before each retry.
            Bitmap fresh=shot();
            PointF current=Detector.detectCarryingClose(fresh);
            if(current!=null&&Math.hypot(current.x-anchor.x,current.y-anchor.y)<=tolerance){
                anchor=new PointF((anchor.x+current.x)*0.5f,(anchor.y+current.y)*0.5f);
                anchorFrame=fresh;
            }

            PilotAccessibilityService activeService=waitForService();
            PilotAccessibilityService.TapResult tr=activeService.tapFromBitmap(
                    anchorFrame,anchor.x,anchor.y,cfg.fast?105:125).get(4,TimeUnit.SECONDS);
            emit("GREEN X TAP #"+attempt+" • anchored screenshot=("+
                    Math.round(tr.sourceX)+","+Math.round(tr.sourceY)+")/"+
                    tr.sourceWidth+"×"+tr.sourceHeight+" → display=("+
                    Math.round(tr.displayX)+","+Math.round(tr.displayY)+")/"+
                    tr.displayWidth+"×"+tr.displayHeight+" • dispatch="+
                    (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));

            if(!tr.accepted||!tr.completed){
                sleep(cfg.fast?120:180);
                continue;
            }

            // ACK on disappearance of THIS bottom-left control, not on an OCR
            // scan of the next page. Two consecutive absent frames are enough;
            // the next round's normal list scan will validate the returned page.
            int absent=0;
            boolean stillSeen=false;
            for(int v=0;v<6&&running.get();v++){
                sleep(cfg.fast?120:180);
                Bitmap verify=shot();
                PointF p=Detector.detectCarryingClose(verify);
                if(p==null){
                    absent++;
                    emit("GREEN-X ACK • anchored X absent • streak="+absent+"/2");
                    if(absent>=2){
                        sleep(cfg.fast?260:420);
                        emit("GREEN-X ACK ✅ • anchored X disappeared on 2 consecutive frames");
                        return true;
                    }
                }else if(Math.hypot(p.x-anchor.x,p.y-anchor.y)<=tolerance){
                    absent=0; stillSeen=true;
                    anchor=p; anchorFrame=verify;
                    emit("GREEN-X ACK • same X still visible @("+
                            Math.round(p.x)+","+Math.round(p.y)+")");
                }else{
                    // A different candidate is irrelevant; do not jump the target.
                    absent++;
                    emit("GREEN-X ACK • unrelated X-like candidate ignored • absent="+absent+"/2");
                    if(absent>=2){
                        sleep(cfg.fast?260:420);
                        emit("GREEN-X ACK ✅ • original anchored X disappeared");
                        return true;
                    }
                }
            }

            if(stillSeen) emit("GREEN X STILL VISIBLE ⚠️ • retrying same anchored control");
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
    private Bitmap shot()throws Exception{
        Throwable last=null;
        for(int attempt=1;attempt<=3&&running.get();attempt++){
            PilotAccessibilityService s=waitForService();
            try{
                Bitmap b=s.screenshot().get(5,TimeUnit.SECONDS);
                if(b==null)throw new RuntimeException("screenshot returned null");
                return b;
            }catch(Throwable t){
                last=t;
                boolean serviceChanged=(service!=s || PilotAccessibilityService.get()!=s);
                emit("SCREENSHOT RETRY ⚠️ • attempt="+attempt+"/3 • serviceChanged="+serviceChanged+
                        " • error="+(t.getMessage()==null?t.getClass().getSimpleName():t.getMessage()));
                if(attempt<3) sleep(serviceChanged?120:260);
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
            String r="BUILD 0.4.0-alpha22 • Screenshot "+b.getWidth()+"×"+b.getHeight()+
                    " • fruit="+c.fruits.size()+" • seedling="+c.seedlings.size()+" • blocked="+c.blocked.size()+
                    " • expedition="+(e!=null)+" • GO="+(g!=null)+" • "+ctaText+" • "+rowText+" • "+xText;
            main.post(()->callback.accept(r));
        }catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
