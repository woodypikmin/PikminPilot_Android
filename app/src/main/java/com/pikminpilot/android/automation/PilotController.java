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
            emit("BUILD 0.2.6-alpha8 • GREEN-X mapped tap + settle/retry • universal filter row");
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
                tap(choice.item.center.x,choice.item.center.y,55);
                sleep(cfg.fast?550:850);

                status("第 "+round+" 輪：前往探險");
                if(choice.item.kind==CargoDetector.Kind.SEEDLING){
                    PointAndBitmap expedition=waitSeedlingExpeditionCta(8,cfg.fast?220:320);
                    if(expedition==null) throw new RuntimeException("花苗：前往探險文字未辨識到");
                    tap(expedition.p.x,expedition.p.y,55);
                    sleep(cfg.fast?1050:1450);
                    if(!confirmSelectionPage(6,cfg.fast?180:260))
                        throw new RuntimeException("花苗：已點前往探險，但選皮頁未確認");
                }else{
                    PointAndBitmap expedition=waitPoint("前往探險",18,cfg.fast?240:380,Detector::detectExpeditionButton);
                    if(expedition==null) throw new RuntimeException("水果：前往探險按鈕未辨識到");
                    tap(expedition.p.x,expedition.p.y,55);
                    sleep(cfg.fast?1450:2000);
                }

                status("第 "+round+" 輪：展開皮克敏顏色列");
                Bitmap reveal=shot();
                // Do not assume a phone-specific Y coordinate here.  Detect the
                // coloured chip strip on this exact screenshot, then swipe through
                // its measured row.  This survives different screen density, font
                // size, navigation-bar height and Pikmin Bloom sheet layout.
                swipeFilterRowLeft(reveal,380);
                sleep(cfg.fast?300:480);

                status("第 "+round+" 輪：辨識"+PilotConfig.pikminName(cfg.type)+"皮克敏");
                PointAndBitmap filter=null;
                for(int a=0;a<5&&running.get();a++){
                    Bitmap b=shot(); PointF p=Detector.detectPikminFilter(b,cfg.type);
                    if(p!=null){filter=new PointAndBitmap(p,b);break;}
                    swipeFilterRowLeft(b,340);
                    sleep(cfg.fast?280:420);
                }
                if(filter==null) throw new RuntimeException(PilotConfig.pikminName(cfg.type)+"皮克敏顏色圓圈未辨識到");
                tap(filter.p.x,filter.p.y,55);
                sleep(cfg.fast?220:320);

                status("第 "+round+" 輪：選擇 "+cfg.pikminCount+" 隻皮克敏");
                Bitmap gridShot=shot();
                List<PointF> grid=Detector.detectPikminSelectionGrid(gridShot);
                if(grid.size()<cfg.pikminCount) throw new RuntimeException("皮克敏格線辨識失敗");
                for(int i=0;i<cfg.pikminCount&&running.get();i++){
                    PointF p=grid.get(i); tap(p.x,p.y,40); sleep(cfg.fast?25:40);
                }
                sleep(cfg.fast?90:150);

                status("第 "+round+" 輪：等待 GO 亮起");
                PointAndBitmap go=waitPoint("GO",8,cfg.fast?60:90,Detector::detectActiveGo);
                if(go==null) throw new RuntimeException("GO 未亮起 / 未辨識到");
                tap(go.p.x,go.p.y,55);
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
            swipe(x,sy,x,ey,420);swipes++;sleep(cfg.fast?350:550);
        }
        return null;
    }

    private PointAndBitmap waitSeedlingExpeditionCta(int attempts,long delay)throws Exception{
        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();
            List<CargoDetector.OcrItem> ocr=CargoDetector.recognize(b);
            PointF p=CargoDetector.expeditionCtaPoint(ocr);
            if(p!=null){
                emit("花苗 前往探險 OCR found ✅ • attempt="+(i+1)+"/"+attempts+
                        " • px=("+Math.round(p.x)+","+Math.round(p.y)+")");
                return new PointAndBitmap(p,b);
            }

            // New-phone fallback: the green outlined CTA has a stable shape even
            // when ML Kit misses its thin Chinese text.  This detector is gated
            // to a wide/low central teal pill, so the blue seedling pot is rejected.
            PointF geometric=Detector.detectSeedlingExpeditionCta(b);
            if(geometric!=null){
                emit("花苗 前往探險 GEOMETRY found ✅ • attempt="+(i+1)+"/"+attempts+
                        " • px=("+Math.round(geometric.x)+","+Math.round(geometric.y)+")");
                return new PointAndBitmap(geometric,b);
            }

            if(i==0||i==attempts-1){
                StringBuilder seen=new StringBuilder();
                for(CargoDetector.OcrItem item:ocr){
                    if(item.rect.centerY()<b.getHeight()*0.42f) continue;
                    String t=item.text==null?"":item.text.trim();
                    if(t.isEmpty()) continue;
                    if(seen.length()>0)seen.append(" | ");
                    seen.append(t);
                    if(seen.length()>180)break;
                }
                emit("花苗 前往探險 waiting • attempt="+(i+1)+"/"+attempts+
                        " • lower OCR="+(seen.length()==0?"<none>":seen.toString()));
            }
            sleep(delay);
        }
        return null;
    }

    private boolean confirmSelectionPage(int attempts,long delay)throws Exception{
        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();
            if(CargoDetector.hasSelectionHeader(CargoDetector.recognize(b))){
                emit("selection page confirmed ✅ • OCR header");
                return true;
            }
            Detector.FilterRowGeometry row=Detector.detectFilterRowGeometry(b);
            if(row!=null){
                emit("selection page confirmed ✅ • filter row geometry • y="+Math.round(row.y)+" • chips="+row.chipCount);
                return true;
            }
            sleep(delay);
        }
        return false;
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
        swipe(fromX,y,toX,y,ms);
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
            String r="BUILD 0.2.6-alpha8 • Screenshot "+b.getWidth()+"×"+b.getHeight()+
                    " • fruit="+c.fruits.size()+" • seedling="+c.seedlings.size()+" • blocked="+c.blocked.size()+
                    " • expedition="+(e!=null)+" • GO="+(g!=null)+" • "+ctaText+" • "+rowText+" • "+xText;
            main.post(()->callback.accept(r));
        }catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
