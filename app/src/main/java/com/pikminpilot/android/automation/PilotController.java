package com.pikminpilot.android.automation;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import com.pikminpilot.android.detection.Detector;
import com.pikminpilot.android.model.PilotConfig;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** First Android automation loop. It ports the image/gesture critical tail and a
 * permissive list-item fallback; the iOS Vision OCR/card-safety gate is left as
 * a clearly-marked follow-up instead of pretending the port is 1:1. */
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
    public void onServiceReady(PilotAccessibilityService s){service=s;emit("Accessibility service ready ✅");}

    public void start(PilotConfig cfg){
        if(running.getAndSet(true))return; completed=0;
        worker.execute(()->run(cfg));
    }

    public void stop(String reason){if(running.getAndSet(false))emit("STOP • "+reason);}
    public void stop(){stop("user requested");}

    private void run(PilotConfig cfg){
        try{
            requireService(); status("啟動 / 等待探險列表");
            emit("ANDROID PILOT START • target="+(cfg.dispatchTarget==0?"∞":cfg.dispatchTarget)+" • type="+cfg.type+" • count="+cfg.pikminCount+" • speed="+(cfg.fast?"FAST":"STABLE"));
            sleep(cfg.fast?450:700);
            while(running.get()&&(cfg.dispatchTarget==0||completed<cfg.dispatchTarget)){
                int round=completed+1; status("第 "+round+" 輪：尋找可搬運物件");
                PointAndBitmap cargo=findCargo(cfg,round);
                if(cargo==null){emit("ROUND "+round+" • no cargo after scan; retrying");sleep(cfg.fast?700:1200);continue;}
                tap(cargo.p.x,cargo.p.y,55); sleep(cfg.fast?550:850);

                status("第 "+round+" 輪：前往探險");
                PointAndBitmap expedition=waitPoint("expedition",18,cfg.fast?220:360,Detector::detectExpeditionButton);
                if(expedition==null)throw new RuntimeException("前往探險 button not detected");
                tap(expedition.p.x,expedition.p.y,55); sleep(cfg.fast?1050:1500);

                status("第 "+round+" 輪：選擇皮克敏");
                Bitmap reveal=shot(); RectF vp=Detector.activeContentRect(reveal);
                swipe((float)(vp.left+vp.width()*0.88),(float)(vp.top+vp.height()*0.432),(float)(vp.left+vp.width()*0.43),(float)(vp.top+vp.height()*0.432),340);
                sleep(cfg.fast?300:480);
                PointAndBitmap filter=null;
                for(int a=0;a<5&&running.get();a++){
                    Bitmap b=shot();PointF p=Detector.detectPikminFilter(b,cfg.type);
                    if(p!=null){filter=new PointAndBitmap(p,b);break;}
                    RectF r=Detector.activeContentRect(b);swipe((float)(r.left+r.width()*0.88),(float)(r.top+r.height()*0.432),(float)(r.left+r.width()*0.43),(float)(r.top+r.height()*0.432),330);sleep(cfg.fast?280:420);
                }
                if(filter==null)throw new RuntimeException(cfg.type+" filter not detected");
                tap(filter.p.x,filter.p.y,55); sleep(cfg.fast?220:320);

                Bitmap gridShot=shot(); List<PointF> grid=Detector.detectPikminSelectionGrid(gridShot);
                if(grid.size()<cfg.pikminCount)throw new RuntimeException("adaptive Pikmin grid failed");
                for(int i=0;i<cfg.pikminCount&&running.get();i++){PointF p=grid.get(i);tap(p.x,p.y,40);sleep(cfg.fast?25:40);}
                sleep(cfg.fast?90:150);

                status("第 "+round+" 輪：GO");
                PointAndBitmap go=waitPoint("GO",8,cfg.fast?60:90,Detector::detectActiveGo);
                if(go==null)throw new RuntimeException("active GO not detected");
                tap(go.p.x,go.p.y,55);sleep(cfg.fast?420:560);

                status("第 "+round+" 輪：關閉搬運畫面");
                PointAndBitmap close=waitPoint("green X",8,cfg.fast?80:120,Detector::detectCarryingClose);
                if(close==null)throw new RuntimeException("carrying green X not detected");
                boolean closed=false;PointF closePoint=close.p;
                for(int attempt=1;attempt<=3&&running.get();attempt++){
                    tap(closePoint.x,closePoint.y,55);sleep(cfg.fast?130:190);
                    int absent=0;
                    for(int k=0;k<5;k++){
                        Bitmap b=shot();PointF p=Detector.detectCarryingClose(b);
                        if(p==null||distance(p,closePoint)>Math.max(b.getWidth(),b.getHeight())*0.085){absent++;if(absent>=2){closed=true;break;}}
                        else {absent=0;closePoint=p;}
                        sleep(cfg.fast?80:120);
                    }
                    if(closed)break;
                }
                if(!closed)throw new RuntimeException("green X remained visible");
                completed++;emit("ROUND "+round+" COMPLETED ✅ • total="+completed);status("完成 "+completed+" 次");sleep(cfg.fast?120:240);
            }
            if(running.get())emit("COMPLETED ✅ • total="+completed);
        } catch (InterruptedException stopped) {
            emit("STOPPED • completed=" + completed);
            status("已停止 • 完成 " + completed + " 次");
        } catch(Throwable t) {
            emit("FAILED • completed="+completed+" • "+t.getMessage());
            status("執行失敗："+t.getMessage());
        } finally {
            running.set(false);
        }
    }

    private PointAndBitmap findCargo(PilotConfig cfg,int round)throws Exception{
        boolean down=true,reversed=false;int swipes=0;
        while(running.get()){
            Bitmap b=shot();List<PointF> candidates=Detector.detectCargoFallback(b);
            if(!candidates.isEmpty()){emit("ROUND "+round+" • cargo fallback candidates="+candidates.size()+" • selecting first");return new PointAndBitmap(candidates.get(0),b);}
            if(swipes>=8){if(!reversed){reversed=true;down=false;swipes=0;emit("ROUND "+round+" • reversing list scan");}else return null;}
            RectF r=Detector.activeContentRect(b);float x=(float)(r.left+r.width()*0.52);
            float sy=(float)(r.top+r.height()*(down?0.77:0.35)),ey=(float)(r.top+r.height()*(down?0.35:0.77));
            swipe(x,sy,x,ey,420);swipes++;sleep(cfg.fast?350:550);
        }return null;
    }

    private interface PointDetector { PointF find(Bitmap b); }
    private PointAndBitmap waitPoint(String name,int attempts,long delay,PointDetector d)throws Exception{
        for(int i=0;i<attempts&&running.get();i++){Bitmap b=shot();PointF p=d.find(b);if(p!=null){emit(name+" detected ✅");return new PointAndBitmap(p,b);}sleep(delay);}return null;
    }

    private static final class PointAndBitmap{final PointF p;final Bitmap b;PointAndBitmap(PointF p,Bitmap b){this.p=p;this.b=b;}}
    private void requireService(){if(service==null)service=PilotAccessibilityService.get();if(service==null)throw new IllegalStateException("Accessibility service is not enabled");}
    private Bitmap shot()throws Exception{requireService();Bitmap b=service.screenshot().get(4,TimeUnit.SECONDS);if(b==null)throw new RuntimeException("screenshot returned null");return b;}
    private void tap(float x,float y,long ms)throws Exception{if(!running.get())throw new InterruptedException("stopped");if(!service.tap(x,y,ms).get(3,TimeUnit.SECONDS))throw new RuntimeException("tap cancelled");}
    private void swipe(float x1,float y1,float x2,float y2,long ms)throws Exception{if(!running.get())throw new InterruptedException("stopped");if(!service.swipe(x1,y1,x2,y2,ms).get(4,TimeUnit.SECONDS))throw new RuntimeException("swipe cancelled");}
    private void sleep(long ms)throws InterruptedException{long left=ms;while(left>0&&running.get()){long n=Math.min(100,left);Thread.sleep(n);left-=n;}if(!running.get())throw new InterruptedException("stopped");}
    private float distance(PointF a,PointF b){float dx=a.x-b.x,dy=a.y-b.y;return(float)Math.sqrt(dx*dx+dy*dy);}
    private void emit(String s){android.util.Log.i("PikminPilot",s);Listener l=listener;if(l!=null)main.post(()->l.onLog(s));}
    private void status(String s){Listener l=listener;if(l!=null)main.post(()->l.onStatus(s));}

    public void testScreenshot(java.util.function.Consumer<String> callback){
        worker.execute(()->{try{requireService();Bitmap b=shot();PointF e=Detector.detectExpeditionButton(b),g=Detector.detectActiveGo(b),x=Detector.detectCarryingClose(b);List<PointF> c=Detector.detectCargoFallback(b);String r="Screenshot "+b.getWidth()+"×"+b.getHeight()+" • cargo="+c.size()+" • expedition="+(e!=null)+" • GO="+(g!=null)+" • greenX="+(x!=null);main.post(()->callback.accept(r));}catch(Throwable t){main.post(()->callback.accept("Screenshot failed: "+t.getMessage()));}});
    }
}
