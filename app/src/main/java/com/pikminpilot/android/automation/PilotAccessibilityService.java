package com.pikminpilot.android.automation;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.hardware.HardwareBuffer;
import android.os.SystemClock;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class PilotAccessibilityService extends AccessibilityService {
    private static volatile PilotAccessibilityService INSTANCE;
    private final Executor screenshotExecutor = Executors.newSingleThreadExecutor();
    private final Object screenshotRateLock = new Object();
    private long lastScreenshotRequestUptime = 0L;

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
        // Keep one conservative app-side gate so polling loops do not fail with
        // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT on real devices.
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

    public CompletableFuture<Boolean> swipe(float x1,float y1,float x2,float y2,long durationMs) {
        Path p=new Path();p.moveTo(x1,y1);p.lineTo(x2,y2);
        GestureDescription g=new GestureDescription.Builder().addStroke(new GestureDescription.StrokeDescription(p,0,Math.max(80,durationMs))).build();
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
