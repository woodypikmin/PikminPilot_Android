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
    private volatile Listener listener;
    private volatile int completed=0;
    private volatile String currentStage="IDLE";
    private volatile long runStartUptime=0L;
    private final java.util.ArrayDeque<RecentDispatch> recentDispatches=new java.util.ArrayDeque<>();

    private static final class RecentDispatch {
        final CargoDetector.Kind kind; final String key; final float nx,ny; final int round;
        RecentDispatch(CargoDetector.Kind kind,String key,float nx,float ny,int round){
            this.kind=kind;this.key=key;this.nx=nx;this.ny=ny;this.round=round;
        }
    }

    public void setListener(Listener l){listener=l;}
    public boolean isRunning(){return running.get();}
    public int completed(){return completed;}
    public void onServiceReady(PilotAccessibilityService s){service=s;emit("Accessibility service ready ✅");}

    public void start(PilotConfig cfg){
        if(running.getAndSet(true)) return;
        completed=0; recentDispatches.clear(); runStartUptime=SystemClock.elapsedRealtime(); currentStage="START";
        worker.execute(()->run(cfg));
    }

    public void stop(String reason){if(running.getAndSet(false))emit("STOP • "+reason);}
    public void stop(){stop("user requested");}

    private void run(PilotConfig cfg){
        try{
            requireService();
            stage("START","啟動 • 開始掃描探險列表");
            emit("BUILD 0.3.2-alpha14 • anchored filter row • anchored Green-X • iOS card-only blocking • exact recent-dispatch latch");
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

                stage("FILTER","第 "+round+" 輪：辨識"+PilotConfig.pikminName(cfg.type)+"皮克敏");
                // On Android the requested chip (especially pink) is often already
                // visible when the selection sheet opens.  Detect first and only
                // swipe when necessary.  This avoids a needless second-round swipe
                // that can move an already-visible pink chip away from the detector.
                PointAndBitmap filter=findPikminFilterAdaptive(cfg,round);
                if(filter==null) throw new RuntimeException(PilotConfig.pikminName(cfg.type)+"皮克敏顏色圓圈未辨識到");
                emit("PIKMIN FILTER TARGET ✅ • type="+PilotConfig.pikminName(cfg.type)+
                        " • px=("+Math.round(filter.p.x)+","+Math.round(filter.p.y)+")"+
                        " • norm=("+String.format(java.util.Locale.US,"%.3f",filter.p.x/Math.max(1f,filter.b.getWidth()))+","+
                        String.format(java.util.Locale.US,"%.3f",filter.p.y/Math.max(1f,filter.b.getHeight()))+") • detector=iOS-magenta-pair");
                tapMapped(filter.b,filter.p.x,filter.p.y,55,"PIKMIN FILTER");
                sleep(cfg.fast?220:320);

                stage("SELECT","第 "+round+" 輪：選擇 "+cfg.pikminCount+" 隻皮克敏");
                selectPikminFastGeometric(cfg,round);

                stage("GO","第 "+round+" 輪：等待 GO 亮起");
                PointAndBitmap go=waitPoint("GO",8,cfg.fast?60:90,Detector::detectActiveGo);
                if(go==null) throw new RuntimeException("GO 未亮起 / 未辨識到");
                tapMapped(go.b,go.p.x,go.p.y,55,"GO");
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
        boolean down=true,reversed=false;int swipes=0;
        while(running.get()){
            status("第 "+round+" 輪：掃描探險列表 • "+(down?"往下":"往上")+" "+(swipes+1)+"/13");
            emit("ROUND "+round+" • capture → OCR/list detector • direction="+(down?"DOWN":"UP")+" • swipe="+swipes);
            Bitmap b=shot();
            CargoDetector.Result result=CargoDetector.scan(b);
            List<CargoDetector.Candidate> available=result.matching(cfg.cargoMode);
            int beforeRecent=available.size();
            available.removeIf(c->isExactRecentDispatch(c,b,round));
            if(beforeRecent!=available.size())
                emit("SCAN-DIAG SKIP[EXACT_RECENT_DISPATCH] count="+(beforeRecent-available.size()));
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
            // Smaller overlapping list motion: about 30% of the active content
            // instead of 42%. This makes partially-visible rows much less likely
            // to be jumped over on tall/narrow Android phones.
            float sy=(float)(r.top+r.height()*(down?0.68:0.38)),ey=(float)(r.top+r.height()*(down?0.38:0.68));
            emit("EXPEDITION LIST SWIPE • amplitude=0.30H • direction="+(down?"DOWN":"UP"));
            swipeMapped(b,x,sy,x,ey,360,"EXPEDITION LIST");swipes++;sleep(cfg.fast?260:400);
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
            if(row!=null&&row.chipCount>=5) return "filter-row-geometry";
        }catch(Throwable ignored){}
        return null;
    }

    /**
     * Fresh-frame filter selection.  First inspect the row exactly as it opened;
     * only reveal/scroll it if the requested chip is not already visible.
     */
    private PointAndBitmap findPikminFilterAdaptive(PilotConfig cfg,int round)throws Exception{
        Bitmap frame=shot();

        for(int attempt=0;attempt<6&&running.get();attempt++){
            Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(frame);
            PointF hint=null;
            String rowSource="none";

            if(row!=null&&row.chipCount>=5){
                hint=new PointF(frame.getWidth()*0.50f,row.y);
                rowSource="chip-row";
            }else{
                try{
                    PointF ocrHint=CargoDetector.filterRowHintPoint(CargoDetector.recognize(frame));
                    if(ocrHint!=null){
                        hint=ocrHint;
                        rowSource="ocr-decor/auto";
                    }
                }catch(Throwable ignored){}
            }

            if(hint!=null){
                float tolerance=Math.max(frame.getHeight()*0.028f,
                        row!=null?Math.max(24f,row.spacing*0.70f):frame.getHeight()*0.036f);
                PointF p=Detector.detectPikminFilterNearRow(frame,cfg.type,hint.y,tolerance);
                if(p!=null){
                    emit("FILTER ROW LOCK ✅ • source="+rowSource+
                            " • rowY="+Math.round(hint.y)+
                            " • targetY="+Math.round(p.y)+
                            " • dy="+Math.round(Math.abs(p.y-hint.y)));
                    emit((attempt==0?"PIKMIN FILTER DIRECT ✅":"PIKMIN FILTER AFTER SWIPE ✅")+
                            " • round="+round+" • attempt="+(attempt+1)+"/6");
                    return new PointAndBitmap(p,frame);
                }
                emit("FILTER ROW LOCK • source="+rowSource+" • row found but requested chip pair not found");
            }else{
                emit("FILTER ROW LOCK MISS ⚠️ • no reliable chip-row anchor; refusing broad magenta guess");
            }

            if(attempt>=5)break;
            status("第 "+round+" 輪：展開皮克敏顏色列");
            swipeFilterRowLeft(frame,attempt==0?330:300);
            sleep(cfg.fast?230:340);
            frame=shot();
        }
        return null;
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
    private void selectPikminFastGeometric(PilotConfig cfg,int round)throws Exception{
        final int desired=cfg.pikminCount;
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
            PilotAccessibilityService.TapResult tr=service.tapFromBitmap(frame,q.x,q.y,cfg.fast?90:95)
                    .get(3,TimeUnit.SECONDS);
            emit("PIKMIN TAP "+(i+1)+"/"+desired+" • display=("+Math.round(tr.displayX)+","+
                    Math.round(tr.displayY)+") • dispatch="+
                    (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));
            // Keep the proven fast rhythm.  No screenshot/OCR between taps.
            sleep(cfg.fast?35:55);
        }
        sleep(cfg.fast?100:160);
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

            PilotAccessibilityService.TapResult tr=service.tapFromBitmap(
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

    private void requireService(){if(service==null)service=PilotAccessibilityService.get();if(service==null)throw new IllegalStateException("Accessibility service is not enabled");}
    private Bitmap shot()throws Exception{requireService();Bitmap b=service.screenshot().get(4,TimeUnit.SECONDS);if(b==null)throw new RuntimeException("screenshot returned null");return b;}
    private void tap(float x,float y,long ms)throws Exception{if(!running.get())throw new InterruptedException("stopped");if(!service.tap(x,y,ms).get(3,TimeUnit.SECONDS))throw new RuntimeException("tap cancelled");}
    private void swipeFilterRowLeft(Bitmap frame,long ms)throws Exception {
        float w=frame.getWidth(), h=frame.getHeight();
        Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(frame);
        float y,fromX,toX;
        if(row!=null){
            y=row.y;fromX=row.fromX;toX=row.toX;
            emit("FILTER ROW AUTO ✅ • frame="+Math.round(w)+"×"+Math.round(h)+
                    " • chips="+row.chipCount+
                    " • y="+Math.round(y)+" ("+String.format(java.util.Locale.US,"%.3f",y/Math.max(1f,h))+"H)"+
                    " • spacing="+Math.round(row.spacing)+
                    " • swipe=("+Math.round(fromX)+","+Math.round(y)+") → ("+Math.round(toX)+","+Math.round(y)+")");
        }else{
            PointF hint=null;
            try{hint=CargoDetector.filterRowHintPoint(CargoDetector.recognize(frame));}catch(Throwable ignored){}
            if(hint!=null){
                y=hint.y;fromX=w*0.78f;toX=w*0.36f;
                emit("FILTER ROW OCR FALLBACK ✅ • y="+Math.round(y)+
                        " • swipe=("+Math.round(fromX)+","+Math.round(y)+") → ("+Math.round(toX)+","+Math.round(y)+")");
            }else{
                // Absolute last resort only. Normal builds should use either the
                // image-detected coloured row or the 飾品/自動 OCR anchor above.
                y=h*0.42f;fromX=w*0.78f;toX=w*0.36f;
                emit("FILTER ROW AUTO MISS ⚠️ • last-resort swipe • frame="+Math.round(w)+"×"+Math.round(h)+
                        " • ("+Math.round(fromX)+","+Math.round(y)+") → ("+Math.round(toX)+","+Math.round(y)+")");
            }
        }
        swipeMapped(frame,fromX,y,toX,y,ms,"FILTER ROW");
    }

    private void tapMapped(Bitmap frame,float x,float y,long ms,String label)throws Exception{
        if(!running.get())throw new InterruptedException("stopped");
        PilotAccessibilityService.TapResult tr=service.tapFromBitmap(frame,x,y,ms).get(4,TimeUnit.SECONDS);
        emit(label+" TAP • screenshot=("+Math.round(tr.sourceX)+","+Math.round(tr.sourceY)+")/"+
                tr.sourceWidth+"×"+tr.sourceHeight+" → display=("+Math.round(tr.displayX)+","+Math.round(tr.displayY)+")/"+
                tr.displayWidth+"×"+tr.displayHeight+" • dispatch="+
                (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));
        if(!tr.accepted||!tr.completed)throw new RuntimeException(label+" tap cancelled");
    }

    private void swipeMapped(Bitmap frame,float x1,float y1,float x2,float y2,long ms,String label)throws Exception{
        if(!running.get())throw new InterruptedException("stopped");
        if(!service.swipeFromBitmap(frame,x1,y1,x2,y2,ms).get(4,TimeUnit.SECONDS))
            throw new RuntimeException(label+" swipe cancelled");
    }

    private void swipe(float x1,float y1,float x2,float y2,long ms)throws Exception{if(!running.get())throw new InterruptedException("stopped");if(!service.swipe(x1,y1,x2,y2,ms).get(4,TimeUnit.SECONDS))throw new RuntimeException("swipe cancelled");}
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
            String r="BUILD 0.3.2-alpha14 • Screenshot "+b.getWidth()+"×"+b.getHeight()+
                    " • fruit="+c.fruits.size()+" • seedling="+c.seedlings.size()+" • blocked="+c.blocked.size()+
                    " • expedition="+(e!=null)+" • GO="+(g!=null)+" • "+ctaText+" • "+rowText+" • "+xText;
            main.post(()->callback.accept(r));
        }catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
