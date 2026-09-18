package com.pikminpilot.android.detection;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import com.pikminpilot.android.model.PilotConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Android port of the image-only geometry used by PikminPilot's
 * ImageAutomationDetector.swift and Runner adaptive geometry.
 *
 * Deliberately has no Android Accessibility dependencies so it can be unit-tested
 * later with recorded screenshots.
 */
public final class Detector {
    private Detector() {}

    public static final class Component {
        public final RectF rect;
        public final int count;
        Component(RectF rect, int count) { this.rect = rect; this.count = count; }
        public PointF center() { return new PointF(rect.centerX(), rect.centerY()); }
    }

    public static final class Hsv {
        public final double h, s, v;
        Hsv(double h, double s, double v) { this.h = h; this.s = s; this.v = v; }
    }

    public static Hsv hsv(int color) {
        double r = ((color >> 16) & 0xff) / 255.0;
        double g = ((color >> 8) & 0xff) / 255.0;
        double b = (color & 0xff) / 255.0;
        double mx = Math.max(r, Math.max(g, b));
        double mn = Math.min(r, Math.min(g, b));
        double d = mx - mn;
        double h = 0.0;
        if (d != 0.0) {
            if (mx == r) h = 60.0 * (((g - b) / d) % 6.0);
            else if (mx == g) h = 60.0 * (((b - r) / d) + 2.0);
            else h = 60.0 * (((r - g) / d) + 4.0);
        }
        if (h < 0) h += 360.0;
        double s = mx == 0.0 ? 0.0 : d / mx;
        return new Hsv(h, s, mx);
    }

    private static boolean active(int color) {
        Hsv v = hsv(color);
        return v.v > 0.085 || v.s > 0.10;
    }

    public static RectF activeContentRect(Bitmap b) {
        int w = b.getWidth(), h = b.getHeight();
        int step = Math.max(2, Math.min(w, h) / 220);
        boolean[] rows = new boolean[h];
        for (int y = 0; y < h; y += step) {
            int on = 0, total = 0;
            for (int x = 0; x < w; x += step) {
                if (active(b.getPixel(x, y))) on++;
                total++;
            }
            boolean value = total > 0 && ((double) on / total) > 0.16;
            for (int yy = y; yy < Math.min(h, y + step); yy++) rows[yy] = value;
        }
        int[] yr = longestRun(rows);
        if (yr == null || yr[1] - yr[0] < (int)(h * 0.55)) return new RectF(0, 0, w, h);

        boolean[] cols = new boolean[w];
        int yStep = Math.max(step, Math.max(1, (yr[1] - yr[0]) / 140));
        for (int x = 0; x < w; x += step) {
            int on = 0, total = 0;
            for (int y = yr[0]; y < yr[1]; y += yStep) {
                if (active(b.getPixel(x, y))) on++;
                total++;
            }
            boolean value = total > 0 && ((double) on / total) > 0.16;
            for (int xx = x; xx < Math.min(w, x + step); xx++) cols[xx] = value;
        }
        int[] xr = longestRun(cols);
        if (xr == null || xr[1] - xr[0] < (int)(w * 0.45)) return new RectF(0, yr[0], w, yr[1]);
        return new RectF(xr[0], yr[0], xr[1], yr[1]);
    }

    private static int[] longestRun(boolean[] values) {
        int bestS = -1, bestE = -1, start = -1;
        for (int i = 0; i <= values.length; i++) {
            boolean on = i < values.length && values[i];
            if (on && start < 0) start = i;
            if (!on && start >= 0) {
                if (bestS < 0 || i - start > bestE - bestS) { bestS = start; bestE = i; }
                start = -1;
            }
        }
        return bestS < 0 ? null : new int[]{bestS, bestE};
    }

    public static List<Component> components(boolean[] mask, int w, int h) {
        boolean[] seen = new boolean[mask.length];
        List<Component> out = new ArrayList<>();
        int[] dx = {1,-1,0,0}, dy = {0,0,1,-1};
        ArrayDeque<Integer> q = new ArrayDeque<>();
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int idx = y * w + x;
            if (!mask[idx] || seen[idx]) continue;
            seen[idx] = true; q.clear(); q.add(idx);
            int minX=x,maxX=x,minY=y,maxY=y,count=0;
            while (!q.isEmpty()) {
                int v=q.removeFirst(), cx=v%w, cy=v/w; count++;
                if(cx<minX)minX=cx; if(cx>maxX)maxX=cx; if(cy<minY)minY=cy; if(cy>maxY)maxY=cy;
                for(int k=0;k<4;k++) {
                    int nx=cx+dx[k], ny=cy+dy[k];
                    if(nx<0||ny<0||nx>=w||ny>=h) continue;
                    int ni=ny*w+nx;
                    if(mask[ni]&&!seen[ni]) { seen[ni]=true; q.add(ni); }
                }
            }
            out.add(new Component(new RectF(minX,minY,maxX+1,maxY+1),count));
        }
        return out;
    }

    public static PointF detectExpeditionButton(Bitmap b) {
        int w=b.getWidth(), h=b.getHeight();
        boolean[] mask=new boolean[w*h];
        int x0=(int)(w*0.18), x1=(int)(w*0.82), y0=(int)(h*0.55), y1=(int)(h*0.84);
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            Hsv v=hsv(b.getPixel(x,y));
            if(v.h>=135&&v.h<=215&&v.s>0.25&&v.v>0.31) mask[y*w+x]=true;
        }
        return components(mask,w,h).stream()
                .filter(c->c.count>=w*h*0.00035&&c.rect.width()>=w*0.12)
                .max(Comparator.comparingInt(c->c.count)).map(Component::center).orElse(null);
    }

    public static PointF detectPikminFilter(Bitmap b, PilotConfig.PikminType type) {
        int w=b.getWidth(),h=b.getHeight(); RectF vp=activeContentRect(b);
        double minDim=Math.max(1,Math.min(vp.width(),vp.height()));
        int x0=Math.max(0,(int)(vp.left+vp.width()*0.12));
        int x1=Math.min(w,(int)(vp.right-vp.width()*0.03));
        int y0=Math.max(0,(int)(vp.top+vp.height()*0.22));
        int y1=Math.min(h,(int)(vp.top+vp.height()*0.66));
        boolean[] mask=new boolean[w*h];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            int color=b.getPixel(x,y); Hsv v=hsv(color);
            int r=(color>>16)&255,g=(color>>8)&255,bl=color&255;
            if(v.h>=278&&v.h<=332&&v.s>=0.16&&v.v>=0.58&&r>g+22&&bl>g+8) mask[y*w+x]=true;
        }
        List<Component> cand=new ArrayList<>();
        for(Component c:components(mask,w,h)) {
            double wf=c.rect.width()/minDim,hf=c.rect.height()/minDim;
            if(c.count>=minDim*minDim*0.00012&&wf>=0.020&&wf<=0.115&&hf>=0.012&&hf<=0.090) cand.add(c);
        }
        PointF left=null,right=null; double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<cand.size();i++) for(int j=i+1;j<cand.size();j++) {
            PointF a=cand.get(i).center(), bb=cand.get(j).center(); if(a.x>bb.x){PointF t=a;a=bb;bb=t;}
            double dxv=bb.x-a.x, dyv=Math.abs(bb.y-a.y);
            if(dxv<vp.width()*0.08||dxv>vp.width()*0.30||dyv>Math.max(minDim*0.045,8)) continue;
            double score=Math.abs(dxv/vp.width()-0.166)+(dyv/Math.max(1,vp.height()))*2.5+
                    Math.abs(((a.y+bb.y)*0.5-vp.centerY())/Math.max(1,vp.height()))*0.12;
            if(score<best){best=score;left=a;right=bb;}
        }
        if(left==null) return null;
        float spacing=(right.x-left.x)*0.5f, row=(left.y+right.y)*0.5f;
        switch(type){
            case PURPLE:return new PointF(left.x,row);
            case WHITE:return new PointF(left.x+spacing,row);
            case PINK:return new PointF(right.x,row);
            case ROCK:
                float x=right.x+spacing;
                return x<vp.right-Math.max(2,minDim*0.01)?new PointF(x,row):null;
            default:return null;
        }
    }

    public static PointF detectActiveGo(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(); RectF vp=activeContentRect(b);
        int x0=Math.max(0,(int)(vp.left+vp.width()*0.42)),x1=Math.min(w,(int)vp.right);
        int y0=Math.max(0,(int)(vp.top+vp.height()*0.58)),y1=Math.min(h,(int)vp.bottom);
        boolean[] mask=new boolean[w*h];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            Hsv v=hsv(b.getPixel(x,y)); boolean warm=v.h<60||v.h>336;
            if(warm&&v.s>0.27&&v.v>0.56) mask[y*w+x]=true;
        }
        double area=Math.max(1,vp.width()*vp.height());
        return components(mask,w,h).stream()
                .filter(c->c.count>=area*0.00075&&c.rect.width()>=vp.width()*0.06&&c.rect.height()>=vp.height()*0.025)
                .max(Comparator.comparingInt(c->c.count)).map(Component::center).orElse(null);
    }

    public static PointF detectCarryingClose(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(); RectF vp=activeContentRect(b);
        int x0=Math.max(0,(int)vp.left),x1=Math.min(w,(int)(vp.left+vp.width()*0.42));
        int y0=Math.max(0,(int)(vp.top+vp.height()*0.60)),y1=Math.min(h,(int)vp.bottom);
        boolean[] mask=new boolean[w*h];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            Hsv v=hsv(b.getPixel(x,y));
            if(v.h>=125&&v.h<=195&&v.s>=0.24&&v.v>=0.14&&v.v<=0.84) mask[y*w+x]=true;
        }
        double area=Math.max(1,vp.width()*vp.height()); PointF bestPoint=null; double best=Double.POSITIVE_INFINITY;
        for(Component c:components(mask,w,h)) {
            if(c.count<area*0.00055)continue;
            double wf=c.rect.width()/Math.max(1,vp.width()),hf=c.rect.height()/Math.max(1,vp.height());
            if(wf<0.045||wf>0.18||hf<0.020||hf>0.12)continue;
            double aspect=c.rect.width()/Math.max(1,c.rect.height()); if(aspect<0.68||aspect>1.45)continue;
            PointF center=c.center(); double nx=(center.x-vp.left)/Math.max(1,vp.width()),ny=(center.y-vp.top)/Math.max(1,vp.height());
            if(nx>0.40||ny<0.62)continue;
            int white=0,sampled=0;
            for(int yy=Math.max(0,(int)c.rect.top);yy<=Math.min(h-1,(int)c.rect.bottom);yy+=2)
                for(int xx=Math.max(0,(int)c.rect.left);xx<=Math.min(w-1,(int)c.rect.right);xx+=2){
                    Hsv v=hsv(b.getPixel(xx,yy)); if(v.s<=0.20&&v.v>=0.82)white++; sampled++;
                }
            double wfraction=sampled>0?(double)white/sampled:0; if(wfraction<0.006)continue;
            double score=Math.abs(Math.log(Math.max(0.001,aspect)))+nx*0.055+Math.abs(ny-0.90)*0.018-
                    Math.min(0.20,c.count/area*14.0)-Math.min(0.10,wfraction*2.5);
            if(score<best){best=score;bestPoint=center;}
        }
        return bestPoint;
    }

    /**
     * A permissive Android fallback for the first port: finds fruit-like colorful
     * blobs aligned to the 3-column expedition grid. The iOS build additionally
     * uses Vision OCR + status-card borders before declaring an item safe.
     */
    public static List<PointF> detectCargoFallback(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(),startY=(int)(h*0.16),endY=(int)(h*0.96);
        boolean[] mask=new boolean[w*h];
        for(int y=startY;y<endY;y++)for(int x=0;x<w;x++){
            Hsv v=hsv(b.getPixel(x,y));
            boolean colorful=v.s>0.30&&v.v>0.24;
            boolean purple=v.h>=235&&v.h<=335&&v.s>0.09&&v.v>0.11;
            boolean red=(v.h<=28||v.h>=332)&&v.s>0.20&&v.v>0.18;
            if(colorful||purple||red)mask[y*w+x]=true;
        }
        double colWidth=w/3.0; List<PointF> out=new ArrayList<>();
        for(Component c:components(mask,w,h)){
            double bw=c.rect.width(),bh=c.rect.height();
            if(bw<w*0.040||bw>w*0.185||bh<h*0.018||bh>h*0.110)continue;
            double aspect=bw/Math.max(1,bh),fill=c.count/Math.max(1.0,bw*bh);
            if(aspect<0.48||aspect>2.0||fill<0.42)continue;
            PointF p=c.center(); int col=Math.min(2,Math.max(0,(int)(p.x/colWidth))); double expected=(col+0.5)*colWidth;
            if(Math.abs(p.x-expected)>colWidth*0.30)continue;
            out.add(p);
        }
        out.sort((a,bb)->Math.abs(a.y-bb.y)>12?Float.compare(a.y,bb.y):Float.compare(a.x,bb.x));
        List<PointF> dedup=new ArrayList<>();
        for(PointF p:out){boolean dup=false;for(PointF old:dedup)if(Math.abs(old.x-p.x)<w*0.045&&Math.abs(old.y-p.y)<h*0.035){dup=true;break;}if(!dup)dedup.add(p);}
        return dedup;
    }

    public static List<PointF> detectPikminSelectionGrid(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(); RectF vp=activeContentRect(b);
        double[] cols={0.129,0.313,0.492,0.672,0.849};
        double aspect=vp.width()/Math.max(1,vp.height()); double t=Math.min(1,Math.max(0,(aspect-0.48)/(0.70-0.48)));
        double[] phone={0.517,0.662,0.801},tablet={0.550,0.720,0.885};
        double[] rows=new double[3];
        int radius=Math.max(5,(int)(Math.min(vp.width(),vp.height())*0.018));
        int collectiveStep=Math.max(3,(int)(vp.height()/260.0));
        for(int r=0;r<3;r++){
            double seed=phone[r]+(tablet[r]-phone[r])*t,seedY=vp.top+vp.height()*seed,bestY=seedY,bestScore=-1e9;
            for(int cy=(int)(seedY-vp.height()*0.060);cy<=(int)(seedY+vp.height()*0.060);cy+=collectiveStep){
                double sum=0;int used=0;for(double col:cols){int cx=(int)(vp.left+vp.width()*col);if(cx>1&&cx<w-2&&cy>1&&cy<h-2){sum+=visualScore(b,cx,cy,radius);used++;}}
                if(used>0&&sum/used>bestScore){bestScore=sum/used;bestY=cy;}
            }
            rows[r]=(bestY-vp.top)/vp.height();
        }
        List<PointF> out=new ArrayList<>(); int step=Math.max(3,(int)(Math.min(vp.width(),vp.height())/180.0));
        double searchX=vp.width()*0.060,searchY=vp.height()*0.042;
        for(double row:rows)for(double col:cols){
            double seedX=vp.left+vp.width()*col,seedY=vp.top+vp.height()*row;PointF best=new PointF((float)seedX,(float)seedY);double bs=-1e9;
            for(int cy=(int)(seedY-searchY);cy<=(int)(seedY+searchY);cy+=step)for(int cx=(int)(seedX-searchX);cx<=(int)(seedX+searchX);cx+=step){
                if(cx>=vp.left&&cx<vp.right&&cy>=vp.top&&cy<vp.bottom){double s=visualScore(b,cx,cy,radius);if(s>bs){bs=s;best=new PointF(cx,cy);}}
            }
            out.add(best);
        }
        return out;
    }

    private static double visualScore(Bitmap b,int cx,int cy,int radius){
        int w=b.getWidth(),h=b.getHeight(),minX=Math.max(1,cx-radius),maxX=Math.min(w-2,cx+radius),minY=Math.max(1,cy-radius),maxY=Math.min(h-2,cy+radius);
        if(minX>=maxX||minY>=maxY)return -1;double score=0;int n=0,step=Math.max(2,radius/5);
        for(int y=minY;y<=maxY;y+=step)for(int x=minX;x<=maxX;x+=step){
            int p=b.getPixel(x,y),pr=b.getPixel(Math.min(w-1,x+1),y),pd=b.getPixel(x,Math.min(h-1,y+1)); Hsv v=hsv(p);
            double lum=(((p>>16)&255)+((p>>8)&255)+(p&255))/765.0,lumR=(((pr>>16)&255)+((pr>>8)&255)+(pr&255))/765.0,lumD=(((pd>>16)&255)+((pd>>8)&255)+(pd&255))/765.0;
            score+=v.s*0.70+(Math.abs(lum-lumR)+Math.abs(lum-lumD))*1.80+Math.min(v.v,1)*0.10;n++;
        }
        return n>0?score/n:-1;
    }
}
