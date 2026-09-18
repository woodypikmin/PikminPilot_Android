package com.pikminpilot.android.automation;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

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

    public void setListener(Listener l){listener=l;}
    public boolean isRunning(){return running.get();}
    public int completed(){return completed;}
    public void onServiceReady(PilotAccessibilityService s){service=s;emit("Accessibility service ready ✅");}

    public void start(PilotConfig cfg){
        if(running.getAndSet(true)) return;
        completed=0;
        worker.execute(()->run(cfg));
    }

    public void stop(String reason){if(running.getAndSet(false))emit("STOP • "+reason);}
    public void stop(){stop("user requested");}

    private void run(PilotConfig cfg){
        try{
            requireService();
            status("啟動 • 開始掃描探險列表");
            emit("BUILD 0.2.9-alpha11 • fast geometric selection • stateful seedling transition • direct-first filter • card-first cargo safety");
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
                status("第 "+round+" 輪：尋找"+PilotConfig.cargoName(cfg.cargoMode));
                CargoAndBitmap choice=findCargo(cfg,round);
                if(choice==null){
                    emit("ROUND "+round+" • full scan found no safe AVAILABLE item • TARGET-LATCH=HOLD");
                    sleep(cfg.fast?800:1300);
                    continue;
                }

                status("第 "+round+" 輪：點擊"+(choice.item.kind==CargoDetector.Kind.SEEDLING?"花苗":"水果"));
                emit("ROUND "+round+" • tap AVAILABLE • kind="+choice.item.kind+" • label="+choice.item.label);
                tapMapped(choice.b,choice.item.center.x,choice.item.center.y,55,"CARGO");
                sleep(cfg.fast?550:850);

                status("第 "+round+" 輪：前往探險");
                if(choice.item.kind==CargoDetector.Kind.SEEDLING){
                    // Treat the seedling detail -> selection transition as a state
                    // machine instead of two unrelated OCR gates.  If the CTA tap
                    // succeeds but OCR misses the next page, keep looking for
                    // selection-page evidence instead of falling back to a stale
                    // "前往探險未辨識到" error.
                    enterSeedlingSelectionPage(round,cfg);
                }else{
                    PointAndBitmap expedition=waitPoint("前往探險",18,cfg.fast?240:380,Detector::detectExpeditionButton);
                    if(expedition==null) throw new RuntimeException("水果：前往探險按鈕未辨識到");
                    tapMapped(expedition.b,expedition.p.x,expedition.p.y,55,"EXPEDITION CTA");
                    sleep(cfg.fast?1450:2000);
                }

                status("第 "+round+" 輪：辨識"+PilotConfig.pikminName(cfg.type)+"皮克敏");
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

                status("第 "+round+" 輪：選擇 "+cfg.pikminCount+" 隻皮克敏");
                selectPikminFastGeometric(cfg,round);

                status("第 "+round+" 輪：等待 GO 亮起");
                PointAndBitmap go=waitPoint("GO",8,cfg.fast?60:90,Detector::detectActiveGo);
                if(go==null) throw new RuntimeException("GO 未亮起 / 未辨識到");
                tapMapped(go.b,go.p.x,go.p.y,55,"GO");
                sleep(cfg.fast?420:560);

                status("第 "+round+" 輪：關閉傳送頁面綠色 X");
                PointAndBitmap close=waitPoint("綠色 X",10,cfg.fast?90:130,Detector::detectCarryingClose);
                if(close==null) throw new RuntimeException("傳送頁面綠色 X 未辨識到");
                if(!closeGreenX(close,cfg)) throw new RuntimeException("GREEN X 已辨識，但點擊後未確認回到探險列表");

                completed++;
                emit("ROUND "+round+" COMPLETED ✅ • total="+completed);
                status("完成 "+completed+(cfg.dispatchTarget>0?" / "+cfg.dispatchTarget:"")+" 次");
                sleep(cfg.fast?120:240);
            }
            if(running.get()){emit("COMPLETED ✅ • total="+completed);status("已完成 • "+completed+" 次");}
        }catch(InterruptedException stopped){
            emit("STOPPED • completed="+completed); status("已停止 • 完成 "+completed+" 次");
        }catch(Throwable t){
            emit("FAILED • completed="+completed+" • "+t.getMessage()); status("執行失敗："+t.getMessage());
        }finally{running.set(false);}
    }

    private CargoAndBitmap findCargo(PilotConfig cfg,int round)throws Exception{
        boolean down=true,reversed=false;int swipes=0;
        while(running.get()){
            status("第 "+round+" 輪：掃描探險列表 • "+(down?"往下":"往上")+" "+(swipes+1)+"/9");
            emit("ROUND "+round+" • capture → OCR/list detector • direction="+(down?"DOWN":"UP")+" • swipe="+swipes);
            Bitmap b=shot();
            CargoDetector.Result result=CargoDetector.scan(b);
            List<CargoDetector.Candidate> available=result.matching(cfg.cargoMode);
            int busy=0,complete=0;for(CargoDetector.StatusCard c:result.cards){if(c.state==CargoDetector.CardState.BUSY)busy++;if(c.state==CargoDetector.CardState.COMPLETE)complete++;}
            emit("ROUND "+round+" SCAN • FRUIT="+result.fruits.size()+" • SEEDLING="+result.seedlings.size()+
                    " • MATCH="+available.size()+" • BUSY="+busy+" • COMPLETE="+complete+" • BLOCKED="+result.blocked.size());
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

            if(swipes>=8){
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
            float sy=(float)(r.top+r.height()*(down?0.77:0.35)),ey=(float)(r.top+r.height()*(down?0.35:0.77));
            swipeMapped(b,x,sy,x,ey,420,"EXPEDITION LIST");swipes++;sleep(cfg.fast?350:550);
        }
        return null;
    }

    /**
     * Robust seedling detail -> Pikmin selection transition.
     *
     * Important: once the CTA has been tapped, never go back to reporting
     * "前往探險未辨識到".  The only question after the tap is whether the
     * selection page appeared.  This prevents the intermittent ice-blue case
     * where the game transitions correctly but a later OCR frame no longer
     * contains the old CTA text.
     */
    private void enterSeedlingSelectionPage(int round,PilotConfig cfg)throws Exception{
        boolean tapped=false;
        final int attempts=cfg.fast?9:11;
        final long delay=cfg.fast?180:240;

        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();
            List<CargoDetector.OcrItem> ocr;
            try{ocr=CargoDetector.recognize(b);}catch(Throwable t){ocr=java.util.Collections.emptyList();}

            String proof=selectionPageProof(b,ocr,cfg.type);
            if(proof!=null){
                emit("SEEDLING TRANSITION ✅ • selection page • proof="+proof+
                        " • CTA-tapped="+tapped+" • attempt="+(i+1)+"/"+attempts);
                return;
            }

            if(!tapped){
                PointF p=CargoDetector.expeditionCtaPoint(ocr);
                String source="OCR";
                if(p==null){p=Detector.detectSeedlingExpeditionCta(b);source="GEOMETRY";}
                if(p!=null){
                    emit("花苗 前往探險 "+source+" found ✅ • attempt="+(i+1)+"/"+attempts+
                            " • px=("+Math.round(p.x)+","+Math.round(p.y)+")");
                    tapMapped(b,p.x,p.y,55,"EXPEDITION CTA");
                    tapped=true;
                    emit("SEEDLING CTA TAP SENT ✅ • waiting only for selection-page evidence");
                    sleep(cfg.fast?650:850);
                    continue;
                }
                if(i==0||i==3||i==attempts-1)
                    emit("花苗 前往探險 waiting • attempt="+(i+1)+"/"+attempts);
            }else{
                if(i==1||i==4||i==attempts-1)
                    emit("SEEDLING TRANSITION waiting • CTA already tapped • attempt="+(i+1)+"/"+attempts);
            }
            sleep(delay);
        }

        if(tapped) throw new RuntimeException("花苗：前往探險已點擊，但選皮頁未確認");
        throw new RuntimeException("花苗：前往探險未辨識到");
    }

    /** Strong selection-page signals that do not depend on one OCR sentence. */
    private String selectionPageProof(Bitmap b,List<CargoDetector.OcrItem> ocr,PilotConfig.PikminType target){
        try{
            if(CargoDetector.hasSelectionHeader(ocr)) return "selection-header";
            if(CargoDetector.filterRowHintPoint(ocr)!=null) return "decor/auto-row-label";
        }catch(Throwable ignored){}
        try{
            // A valid purple/pink magenta pair is highly specific to the Pikmin
            // filter row and is stronger than the generic coloured-row geometry.
            PointF p=Detector.detectPikminFilter(b,target);
            if(p!=null) return "requested-filter-magenta-pair";
            p=Detector.detectPikminFilter(b,PilotConfig.PikminType.PINK);
            if(p!=null) return "pink-magenta-pair";
        }catch(Throwable ignored){}
        return null;
    }

    /**
     * Fresh-frame filter selection.  First inspect the row exactly as it opened;
     * only reveal/scroll it if the requested chip is not already visible.
     */
    private PointAndBitmap findPikminFilterAdaptive(PilotConfig cfg,int round)throws Exception{
        Bitmap first=shot();
        PointF p=Detector.detectPikminFilter(first,cfg.type);
        if(p!=null){
            emit("PIKMIN FILTER DIRECT ✅ • round="+round+" • no swipe needed");
            return new PointAndBitmap(p,first);
        }

        for(int a=0;a<5&&running.get();a++){
            status("第 "+round+" 輪：展開皮克敏顏色列");
            Bitmap before=(a==0?first:shot());
            swipeFilterRowLeft(before,a==0?380:340);
            sleep(cfg.fast?280:420);
            Bitmap after=shot();
            p=Detector.detectPikminFilter(after,cfg.type);
            if(p!=null){
                emit("PIKMIN FILTER AFTER SWIPE ✅ • attempt="+(a+1)+"/5");
                return new PointAndBitmap(p,after);
            }
            emit("PIKMIN FILTER MISS • attempt="+(a+1)+"/5");
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
        PointF closePoint=initial.p;
        Bitmap closeFrame=initial.b;

        // The X is often visible during the end of the send animation before it
        // is touchable.  Do not fire the gesture on the first rendered frame.
        sleep(cfg.fast?420:620);
        Bitmap settled=shot();
        PointF settledPoint=Detector.detectCarryingClose(settled);
        if(settledPoint!=null){
            closePoint=Detector.refineCarryingCloseTapPoint(settled,settledPoint);
            closeFrame=settled;
            emit("GREEN X STABLE ✅ • px=("+Math.round(closePoint.x)+","+Math.round(closePoint.y)+")");
        }else{
            closePoint=Detector.refineCarryingCloseTapPoint(closeFrame,closePoint);
            emit("GREEN X STABLE FRAME miss ⚠️ • using last detected px=("+Math.round(closePoint.x)+","+Math.round(closePoint.y)+")");
        }

        // Keep retries slow enough for the game to finish its transition.  The
        // small offsets remain inside the round X hit target and help if a
        // gradient-only component produced a slightly biased visual centre.
        final float[][] offsets={{0f,0f},{0.010f,0f},{-0.010f,0f},{0f,0.006f},{0f,-0.006f}};
        for(int attempt=1;attempt<=offsets.length&&running.get();attempt++){
            float sx=closePoint.x+closeFrame.getWidth()*offsets[attempt-1][0];
            float sy=closePoint.y+closeFrame.getHeight()*offsets[attempt-1][1];
            sx=Math.max(1,Math.min(closeFrame.getWidth()-2,sx));
            sy=Math.max(1,Math.min(closeFrame.getHeight()-2,sy));

            PilotAccessibilityService.TapResult tr=service.tapFromBitmap(closeFrame,sx,sy,cfg.fast?95:120)
                    .get(4,TimeUnit.SECONDS);
            emit("GREEN X TAP #"+attempt+" • screenshot=("+Math.round(tr.sourceX)+","+Math.round(tr.sourceY)+")/"+
                    tr.sourceWidth+"×"+tr.sourceHeight+" → display=("+Math.round(tr.displayX)+","+Math.round(tr.displayY)+")/"+
                    tr.displayWidth+"×"+tr.displayHeight+" • dispatch="+
                    (!tr.accepted?"REJECTED":(tr.completed?"COMPLETED":"CANCELLED")));

            if(!tr.accepted||!tr.completed){
                sleep(cfg.fast?260:420);
                continue;
            }

            sleep(cfg.fast?480:700);
            if(waitForExpeditionList(cfg.fast?6:8,cfg.fast?150:210)) return true;

            Bitmap b=shot();
            PointF p=Detector.detectCarryingClose(b);
            if(p==null){
                emit("GREEN X no longer visible • waiting for expedition list before another tap");
                if(waitForExpeditionList(cfg.fast?6:9,cfg.fast?180:240)) return true;
                // Do not tap a stale point on an unknown transition frame.  Give
                // the UI one more chance to settle, then re-detect.
                sleep(cfg.fast?300:500);
                b=shot(); p=Detector.detectCarryingClose(b);
                if(p==null) continue;
            }
            closeFrame=b;
            closePoint=Detector.refineCarryingCloseTapPoint(b,p);
            emit("GREEN X still visible • retry center=("+Math.round(closePoint.x)+","+Math.round(closePoint.y)+")");
        }
        return false;
    }

    private boolean waitForExpeditionList(int attempts,long delay)throws Exception{
        int streak=0;
        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();
            PointF x=Detector.detectCarryingClose(b);
            if(x==null){
                List<CargoDetector.OcrItem> ocr=CargoDetector.recognize(b);
                boolean list=CargoDetector.hasExpeditionTab(ocr);
                if(!list){
                    CargoDetector.Result r=CargoDetector.scan(b);
                    list=!r.fruits.isEmpty()||!r.seedlings.isEmpty()||!r.cards.isEmpty();
                }
                streak=list?streak+1:0;
                if(streak>=2){emit("GREEN-X ACK ✅ • expedition list verified");return true;}
            }else streak=0;
            sleep(delay);
        }
        return false;
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
    private void emit(String s){android.util.Log.i("PikminPilot",s);Listener l=listener;if(l!=null)main.post(()->l.onLog(s));}
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
            String r="BUILD 0.2.9-alpha11 • Screenshot "+b.getWidth()+"×"+b.getHeight()+
                    " • fruit="+c.fruits.size()+" • seedling="+c.seedlings.size()+" • blocked="+c.blocked.size()+
                    " • expedition="+(e!=null)+" • GO="+(g!=null)+" • "+ctaText+" • "+rowText+" • "+xText;
            main.post(()->callback.accept(r));
        }catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
