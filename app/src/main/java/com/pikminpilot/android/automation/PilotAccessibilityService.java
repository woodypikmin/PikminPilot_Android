package com.pikminpilot.android.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class PilotAccessibilityService extends AccessibilityService {
    private static volatile PilotAccessibilityService INSTANCE;
    private final Executor screenshotExecutor = Executors.newSingleThreadExecutor();
    private final Object screenshotRateLock = new Object();
    private long lastScreenshotRequestUptime = 0L;

    /** Result from a screenshot-coordinate tap after mapping it into display coordinates. */
    public static final class TapResult {
        public final boolean accepted;
        public final boolean completed;
        public final float sourceX, sourceY, displayX, displayY;
        public final int sourceWidth, sourceHeight, displayWidth, displayHeight;

        TapResult(boolean accepted, boolean completed,
                  float sourceX, float sourceY, float displayX, float displayY,
                  int sourceWidth, int sourceHeight, int displayWidth, int displayHeight) {
            this.accepted=accepted; this.completed=completed;
            this.sourceX=sourceX; this.sourceY=sourceY;
            this.displayX=displayX; this.displayY=displayY;
            this.sourceWidth=sourceWidth; this.sourceHeight=sourceHeight;
            this.displayWidth=displayWidth; this.displayHeight=displayHeight;
        }
    }

    public static PilotAccessibilityService get() { return INSTANCE; }

    @Override public void onServiceConnected() {
        super.onServiceConnected(); INSTANCE=this;
        PilotController.get().onServiceReady(this);
    }

    @Override public void onDestroy() {
        if(INSTANCE==this) INSTANCE=null; PilotController.get().stop("Accessibility service stopped"); super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}

    public CompletableFuture<Bitmap> screenshot() {
        CompletableFuture<Bitmap> f=new CompletableFuture<>();

        // AccessibilityService rejects screenshots requested too close together.
        synchronized (screenshotRateLock) {
            long now = SystemClock.uptimeMillis();
            long wait = 400L - (now - lastScreenshotRequestUptime);
            if (wait > 0) {
                try { Thread.sleep(wait); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    f.completeExceptionally(e);
                    return f;
                }
            }
            lastScreenshotRequestUptime = SystemClock.uptimeMillis();
        }

        takeScreenshot(Display.DEFAULT_DISPLAY, screenshotExecutor, new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult result) {
                HardwareBuffer buffer = result.getHardwareBuffer();
                try {
                    Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                    if (hardware == null) throw new IllegalStateException("wrapHardwareBuffer returned null");
                    Bitmap software = hardware.copy(Bitmap.Config.ARGB_8888, false);
                    hardware.recycle();
                    if (software == null) throw new IllegalStateException("hardware bitmap copy failed");
                    f.complete(software);
                } catch(Throwable t) {
                    f.completeExceptionally(t);
                } finally {
                    buffer.close();
                }
            }
            @Override public void onFailure(int errorCode) {
                f.completeExceptionally(new RuntimeException("takeScreenshot error="+errorCode));
            }
        });
        return f;
    }

    public CompletableFuture<Boolean> tap(float x,float y,long durationMs) {
        Path p=new Path();p.moveTo(x,y);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(1,durationMs))).build();
        return dispatch(g);
    }

    /**
     * Tap a point that came from a screenshot.  Most phones return screenshots at
     * display resolution, but this is not guaranteed on every vendor / display
     * mode.  Mapping through WindowMetrics avoids silently tapping a different
     * physical point when the screenshot and gesture coordinate spaces differ.
     */
    public CompletableFuture<TapResult> tapFromBitmap(Bitmap source,float x,float y,long durationMs) {
        CompletableFuture<TapResult> f=new CompletableFuture<>();
        int sw=Math.max(1,source.getWidth()), sh=Math.max(1,source.getHeight());
        Rect bounds;
        try {
            WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            WindowMetrics metrics=wm.getCurrentWindowMetrics();
            bounds=metrics.getBounds();
        } catch(Throwable ignored) {
            bounds=new Rect(0,0,sw,sh);
        }
        final int dw=Math.max(1,bounds.width()), dh=Math.max(1,bounds.height());
        final float tx=Math.max(bounds.left,Math.min(bounds.right-1f,bounds.left+x*dw/sw));
        final float ty=Math.max(bounds.top,Math.min(bounds.bottom-1f,bounds.top+y*dh/sh));
        final long press=Math.max(90L,durationMs);

        Path p=new Path(); p.moveTo(tx,ty);
        GestureDescription g=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p,0,press)).build();

        boolean accepted=dispatchGesture(g,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription gestureDescription){
                f.complete(new TapResult(true,true,x,y,tx,ty,sw,sh,dw,dh));
            }
            @Override public void onCancelled(GestureDescription gestureDescription){
                f.complete(new TapResult(true,false,x,y,tx,ty,sw,sh,dw,dh));
            }
        },new Handler(Looper.getMainLooper()));
        if(!accepted) f.complete(new TapResult(false,false,x,y,tx,ty,sw,sh,dw,dh));
        return f;
    }

    public CompletableFuture<Boolean> swipe(float x1,float y1,float x2,float y2,long durationMs) {
        Path p=new Path();p.moveTo(x1,y1);p.lineTo(x2,y2);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(80,durationMs))).build();
        return dispatch(g);
    }

    /** Map a swipe measured on a screenshot into the gesture display space. */
    public CompletableFuture<Boolean> swipeFromBitmap(Bitmap source,float x1,float y1,float x2,float y2,long durationMs) {
        int sw=Math.max(1,source.getWidth()), sh=Math.max(1,source.getHeight());
        Rect bounds;
        try {
            WindowManager wm=(WindowManager)getSystemService(WINDOW_SERVICE);
            bounds=wm.getCurrentWindowMetrics().getBounds();
        } catch(Throwable ignored) {
            bounds=new Rect(0,0,sw,sh);
        }
        int dw=Math.max(1,bounds.width()), dh=Math.max(1,bounds.height());
        float tx1=Math.max(bounds.left,Math.min(bounds.right-1f,bounds.left+x1*dw/sw));
        float ty1=Math.max(bounds.top,Math.min(bounds.bottom-1f,bounds.top+y1*dh/sh));
        float tx2=Math.max(bounds.left,Math.min(bounds.right-1f,bounds.left+x2*dw/sw));
        float ty2=Math.max(bounds.top,Math.min(bounds.bottom-1f,bounds.top+y2*dh/sh));
        Path p=new Path(); p.moveTo(tx1,ty1); p.lineTo(tx2,ty2);
        GestureDescription g=new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(80,durationMs))).build();
        return dispatch(g);
    }

    private CompletableFuture<Boolean> dispatch(GestureDescription g) {
        CompletableFuture<Boolean> f=new CompletableFuture<>();
        boolean accepted=dispatchGesture(g,new GestureResultCallback(){
            @Override public void onCompleted(GestureDescription gestureDescription){f.complete(true);}
            @Override public void onCancelled(GestureDescription gestureDescription){f.complete(false);}
        },new Handler(Looper.getMainLooper()));
        if(!accepted)f.complete(false);return f;
    }
}
