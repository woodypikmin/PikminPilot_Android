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
                    PointAndBitmap expedition=waitSeedlingExpeditionCta(4,cfg.fast?180:280);
                    if(expedition==null) throw new RuntimeException("花苗：前往探險文字未辨識到");
                    tap(expedition.p.x,expedition.p.y,55);
                    sleep(cfg.fast?1050:1450);
                    if(!confirmSelectionPage(4,cfg.fast?160:240))
                        throw new RuntimeException("花苗：已點前往探險，但選皮頁未確認");
                }else{
                    PointAndBitmap expedition=waitPoint("前往探險",18,cfg.fast?240:380,Detector::detectExpeditionButton);
                    if(expedition==null) throw new RuntimeException("水果：前往探險按鈕未辨識到");
                    tap(expedition.p.x,expedition.p.y,55);
                    sleep(cfg.fast?1450:2000);
                }

                status("第 "+round+" 輪：展開皮克敏顏色列");
                Bitmap reveal=shot();
                RectF vp=Detector.activeContentRect(reveal);
                // Exact iOS behavior: drag the color chips to the LEFT so purple/white/pink/rock become visible.
                swipe((float)(vp.left+vp.width()*0.88),(float)(vp.top+vp.height()*0.432),
                        (float)(vp.left+vp.width()*0.43),(float)(vp.top+vp.height()*0.432),380);
                sleep(cfg.fast?300:480);

                status("第 "+round+" 輪：辨識"+PilotConfig.pikminName(cfg.type)+"皮克敏");
                PointAndBitmap filter=null;
                for(int a=0;a<5&&running.get();a++){
                    Bitmap b=shot(); PointF p=Detector.detectPikminFilter(b,cfg.type);
                    if(p!=null){filter=new PointAndBitmap(p,b);break;}
                    RectF r=Detector.activeContentRect(b);
                    swipe((float)(r.left+r.width()*0.88),(float)(r.top+r.height()*0.432),
                            (float)(r.left+r.width()*0.43),(float)(r.top+r.height()*0.432),340);
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
                boolean closed=false; PointF closePoint=close.p;
                for(int attempt=1;attempt<=3&&running.get();attempt++){
                    tap(closePoint.x,closePoint.y,55);
                    sleep(cfg.fast?150:220);
                    if(waitForExpeditionList(cfg.fast?6:8,cfg.fast?130:190)){closed=true;break;}
                    Bitmap b=shot(); PointF p=Detector.detectCarryingClose(b); if(p!=null)closePoint=p;
                }
                if(!closed) throw new RuntimeException("按下綠色 X 後，未確認回到探險列表");

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
            Bitmap b=shot();List<CargoDetector.OcrItem> ocr=CargoDetector.recognize(b);PointF p=CargoDetector.expeditionCtaPoint(ocr);
            if(p!=null){emit("花苗 前往探險 OCR found ✅");return new PointAndBitmap(p,b);}sleep(delay);
        }return null;
    }

    private boolean confirmSelectionPage(int attempts,long delay)throws Exception{
        for(int i=0;i<attempts&&running.get();i++){
            Bitmap b=shot();if(CargoDetector.hasSelectionHeader(CargoDetector.recognize(b))){emit("selection page confirmed ✅");return true;}sleep(delay);
        }return false;
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
        for(int i=0;i<attempts&&running.get();i++){Bitmap b=shot();PointF p=d.find(b);if(p!=null){emit(name+" detected ✅");return new PointAndBitmap(p,b);}sleep(delay);}return null;
    }

    private static final class PointAndBitmap{final PointF p;final Bitmap b;PointAndBitmap(PointF p,Bitmap b){this.p=p;this.b=b;}}
    private static final class CargoAndBitmap{final CargoDetector.Candidate item;final Bitmap b;CargoAndBitmap(CargoDetector.Candidate i,Bitmap b){item=i;this.b=b;}}

    private void requireService(){if(service==null)service=PilotAccessibilityService.get();if(service==null)throw new IllegalStateException("Accessibility service is not enabled");}
    private Bitmap shot()throws Exception{requireService();Bitmap b=service.screenshot().get(4,TimeUnit.SECONDS);if(b==null)throw new RuntimeException("screenshot returned null");return b;}
    private void tap(float x,float y,long ms)throws Exception{if(!running.get())throw new InterruptedException("stopped");if(!service.tap(x,y,ms).get(3,TimeUnit.SECONDS))throw new RuntimeException("tap cancelled");}
    private void swipe(float x1,float y1,float x2,float y2,long ms)throws Exception{if(!running.get())throw new InterruptedException("stopped");if(!service.swipe(x1,y1,x2,y2,ms).get(4,TimeUnit.SECONDS))throw new RuntimeException("swipe cancelled");}
    private void sleep(long ms)throws InterruptedException{long left=ms;while(left>0&&running.get()){long n=Math.min(100,left);Thread.sleep(n);left-=n;}if(!running.get())throw new InterruptedException("stopped");}
    private void emit(String s){android.util.Log.i("PikminPilot",s);Listener l=listener;if(l!=null)main.post(()->l.onLog(s));}
    private void status(String s){Listener l=listener;if(l!=null)main.post(()->l.onStatus(s));}

    public void testScreenshot(java.util.function.Consumer<String> callback){
        worker.execute(()->{try{
            requireService();Bitmap b=shot();CargoDetector.Result c=CargoDetector.scan(b);PointF e=Detector.detectExpeditionButton(b),g=Detector.detectActiveGo(b),x=Detector.detectCarryingClose(b);
            String r="Screenshot "+b.getWidth()+"×"+b.getHeight()+" • fruit="+c.fruits.size()+" • seedling="+c.seedlings.size()+" • blocked="+c.blocked.size()+" • expedition="+(e!=null)+" • GO="+(g!=null)+" • greenX="+(x!=null);
            main.post(()->callback.accept(r));
        }catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
