package com.pikminpilot.android.detection;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.RectF;

import com.pikminpilot.android.model.PilotConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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

    /**
     * Geometry of Pikmin Bloom's horizontal colour-filter strip.  Unlike the
     * early Android build this is detected from the current screenshot; no
     * fixed Y coordinate is assumed.
     */
    public static final class FilterRowGeometry {
        public final float y, fromX, toX, spacing;
        public final int chipCount;
        FilterRowGeometry(float y, float fromX, float toX, float spacing, int chipCount) {
            this.y=y; this.fromX=fromX; this.toX=toX; this.spacing=spacing; this.chipCount=chipCount;
        }
    }

    /**
     * Canonical colour-filter lattice:
     * red=0, yellow=1, blue=2, purple=3, white=4, pink=5, rock=6, cyan=7.
     *
     * We lock the row from the small round red/yellow/blue/cyan chips themselves,
     * not from Pikmin/decor artwork and not from OCR. Once origin + spacing are
     * known, white/pink/rock positions are pure geometry.
     */
    public static final class FilterLattice {
        public final float rowY, spacing, originX;
        public final int evidenceCount;
        public final float residual;
        FilterLattice(float rowY,float spacing,float originX,int evidenceCount,float residual){
            this.rowY=rowY;this.spacing=spacing;this.originX=originX;
            this.evidenceCount=evidenceCount;this.residual=residual;
        }
        public float targetX(PilotConfig.PikminType type){
            int i;
            switch(type){
                case PURPLE:i=3;break;
                case WHITE:i=4;break;
                case PINK:i=5;break;
                case ROCK:i=6;break;
                default:return Float.NaN;
            }
            return originX+i*spacing;
        }
    }

    private static final class FilterAnchor {
        final float x,y; final int index;
        FilterAnchor(float x,float y,int index){this.x=x;this.y=y;this.index=index;}
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

    /**
     * Universal detector for the outlined green/teal "前往探險" pill.
     *
     * A connected-component detector is fragile here because the pill is only an
     * outline: the top/bottom strokes can be separate components and their pixel
     * thickness changes with resolution / vendor scaling. Instead, scan each row
     * for a long horizontal teal run and pair a top edge with a matching bottom
     * edge. This uses proportions only and therefore survives different Android
     * aspect ratios and UI density much better.
     */
    public static PointF detectExpeditionCtaPill(Bitmap b) {
        final int w=b.getWidth(), h=b.getHeight();
        final int x0=(int)(w*0.20), x1=(int)(w*0.80);
        final int y0=(int)(h*0.28), y1=(int)(h*0.91);
        final int minRun=Math.max(18,(int)(w*0.15));

        class RowRun {
            final int y,start,end,len;
            RowRun(int y,int start,int end){this.y=y;this.start=start;this.end=end;this.len=end-start+1;}
            float cx(){return (start+end)*0.5f;}
        }
        List<RowRun> rows=new ArrayList<>();
        for(int y=y0;y<y1;y++){
            int bestStart=-1,bestEnd=-1,bestLen=0;
            int runStart=-1,lastGood=-1,gap=0;
            for(int x=x0;x<x1;x++){
                Hsv v=hsv(b.getPixel(x,y));
                boolean teal=v.h>=125&&v.h<=205&&v.s>=0.18&&v.v>=0.30;
                if(teal){
                    if(runStart<0)runStart=x;
                    lastGood=x;gap=0;
                }else if(runStart>=0){
                    // Allow a tiny antialias/text gap without breaking the line.
                    gap++;
                    if(gap>2){
                        int end=lastGood;
                        int len=end-runStart+1;
                        if(len>bestLen){bestLen=len;bestStart=runStart;bestEnd=end;}
                        runStart=-1;lastGood=-1;gap=0;
                    }
                }
            }
            if(runStart>=0){
                int len=lastGood-runStart+1;
                if(len>bestLen){bestLen=len;bestStart=runStart;bestEnd=lastGood;}
            }
            if(bestLen>=minRun) rows.add(new RowRun(y,bestStart,bestEnd));
        }

        PointF bestPoint=null;
        double bestScore=Double.POSITIVE_INFINITY;
        final float minSep=h*0.024f, maxSep=h*0.095f;
        for(int i=0;i<rows.size();i++){
            RowRun a=rows.get(i);
            for(int j=i+1;j<rows.size();j++){
                RowRun z=rows.get(j);
                float sep=z.y-a.y;
                if(sep<minSep)continue;
                if(sep>maxSep)break;
                float overlap=Math.max(0,Math.min(a.end,z.end)-Math.max(a.start,z.start)+1);
                if(overlap<Math.min(a.len,z.len)*0.62f)continue;
                if(Math.abs(a.cx()-z.cx())>w*0.065f)continue;

                float cx=(a.cx()+z.cx())*0.5f;
                float cy=(a.y+z.y)*0.5f;
                float width=(a.len+z.len)*0.5f;
                float nx=cx/Math.max(1f,w), ny=cy/Math.max(1f,h);
                float nw=width/Math.max(1f,w), ns=sep/Math.max(1f,h);
                if(nx<0.28f||nx>0.72f)continue;
                if(nw<0.15f||nw>0.58f)continue;

                // Centre/shape dominate. Vertical position is only a weak bias so
                // tall/short phones and different bottom-sheet heights still work.
                double score=Math.abs(nx-0.50)*2.0+
                        Math.abs(nw-0.30)*1.0+
                        Math.abs(ns-0.048)*1.1+
                        Math.abs(ny-0.70)*0.10;
                if(score<bestScore){bestScore=score;bestPoint=new PointF(cx,cy);}
            }
        }
        return bestPoint;
    }

    /**
     * Seedling detail pages contain a wide, low, teal/green outlined
     * "前往探險" pill.  OCR is still preferred by the controller, but this
     * detector is a layout-independent fallback for phones where ML Kit misses
     * the thin green Chinese glyphs.  The aspect-ratio gate deliberately rejects
     * the blue/teal seedling pot artwork above the button.
     */
    public static PointF detectSeedlingExpeditionCta(Bitmap b) {
        int w=b.getWidth(), h=b.getHeight();
        boolean[] mask=new boolean[w*h];
        int x0=(int)(w*0.10), x1=(int)(w*0.90);
        int y0=(int)(h*0.38), y1=(int)(h*0.92);
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            Hsv v=hsv(b.getPixel(x,y));
            if(v.h>=125&&v.h<=205&&v.s>=0.20&&v.v>=0.30) mask[y*w+x]=true;
        }
        PointF bestPoint=null; double best=Double.POSITIVE_INFINITY;
        for(Component c:components(mask,w,h)) {
            double wf=c.rect.width()/Math.max(1.0,w), hf=c.rect.height()/Math.max(1.0,h);
            double aspect=c.rect.width()/Math.max(1.0,c.rect.height());
            PointF p=c.center();
            double nx=p.x/Math.max(1.0,w), ny=p.y/Math.max(1.0,h);
            if(wf<0.20||wf>0.62||hf<0.025||hf>0.095) continue;
            if(aspect<2.20||aspect>7.50) continue;
            if(nx<0.28||nx>0.72||ny<0.42||ny>0.89) continue;
            if(c.count<w*h*0.00028) continue;
            double score=Math.abs(nx-0.50)*2.5+Math.abs(wf-0.34)+Math.abs(hf-0.050)*2.0+Math.abs(aspect-3.0)*0.05;
            if(score<best){best=score;bestPoint=p;}
        }
        return bestPoint;
    }

    private static int canonicalFilterAnchor(Hsv v) {
        // Use only colours that remain separable even after the game dims the row.
        // Purple/pink are NOT required to lock the lattice.
        if ((v.h<=18||v.h>=345) && v.s>=0.20 && v.v>=0.72) return 0; // red
        if (v.h>=32&&v.h<=72 && v.s>=0.30 && v.v>=0.72) return 1;    // yellow
        if (v.h>=190&&v.h<=225 && v.s>=0.20 && v.v>=0.62) return 2; // blue
        if (v.h>=165&&v.h<=195 && v.s>=0.18 && v.v>=0.70) return 7; // cyan
        return -1;
    }

    /**
     * Lock the real filter row from circular colour chips.
     *
     * Safety property: >=3 canonical colours must agree on one equally-spaced
     * lattice. Random pink Pikmin/decor art lower in the grid cannot satisfy it.
     */
    public static FilterLattice detectFilterLattice(Bitmap b) {
        int w=b.getWidth(), h=b.getHeight();
        double minDim=Math.max(1,Math.min(w,h));
        int x0=(int)(w*0.10), x1=(int)(w*0.99);
        int y0=(int)(h*0.26), y1=(int)(h*0.57);

        boolean[] mask=new boolean[w*h];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++) {
            Hsv v=hsv(b.getPixel(x,y));
            if(v.s>=0.155&&v.v>=0.48) mask[y*w+x]=true;
        }

        List<FilterAnchor> anchors=new ArrayList<>();
        for(Component c:components(mask,w,h)) {
            double wf=c.rect.width()/minDim, hf=c.rect.height()/minDim;
            double aspect=c.rect.width()/Math.max(1.0,c.rect.height());
            double fill=c.count/Math.max(1.0,c.rect.width()*c.rect.height());
            if(c.count<minDim*minDim*0.000075) continue;
            if(wf<0.025||wf>0.078||hf<0.025||hf>0.078) continue;
            if(aspect<0.74||aspect>1.34||fill<0.48) continue;
            PointF p=c.center();
            int px=Math.max(0,Math.min(w-1,Math.round(p.x)));
            int py=Math.max(0,Math.min(h-1,Math.round(p.y)));
            int idx=canonicalFilterAnchor(hsv(b.getPixel(px,py)));
            if(idx>=0) anchors.add(new FilterAnchor(p.x,p.y,idx));
        }
        if(anchors.size()<3) return null;

        float yTol=(float)Math.max(10,minDim*0.030);
        FilterLattice best=null;
        double bestScore=-1e18;

        for(int i=0;i<anchors.size();i++) for(int j=i+1;j<anchors.size();j++) {
            FilterAnchor a=anchors.get(i), c=anchors.get(j);
            if(a.index==c.index) continue;
            if(Math.abs(a.y-c.y)>yTol) continue;
            float spacing=(c.x-a.x)/(c.index-a.index);
            if(spacing<0) spacing=-spacing;
            if(spacing<w*0.045f||spacing>w*0.115f) continue;

            float originA=a.x-a.index*spacing;
            float originC=c.x-c.index*spacing;
            float origin=(originA+originC)*0.5f;
            float rowSeed=(a.y+c.y)*0.5f;

            List<FilterAnchor> support=new ArrayList<>();
            boolean[] seenIndex=new boolean[8];
            for(FilterAnchor q:anchors) {
                if(Math.abs(q.y-rowSeed)>yTol) continue;
                float expected=origin+q.index*spacing;
                float err=Math.abs(q.x-expected);
                if(err<=Math.max(7f,spacing*0.26f)) {
                    support.add(q);
                    seenIndex[q.index]=true;
                }
            }
            int distinct=0; for(boolean s:seenIndex) if(s) distinct++;
            if(distinct<3||support.size()<3) continue;

            float mi=0,mx=0,my=0;
            for(FilterAnchor q:support){mi+=q.index;mx+=q.x;my+=q.y;}
            mi/=support.size();mx/=support.size();my/=support.size();
            float num=0,den=0;
            for(FilterAnchor q:support){float di=q.index-mi;num+=di*(q.x-mx);den+=di*di;}
            if(den>0.001f) spacing=num/den;
            if(spacing<w*0.045f||spacing>w*0.115f) continue;
            origin=mx-mi*spacing;

            float residualSum=0;
            for(FilterAnchor q:support) residualSum+=Math.abs(q.x-(origin+q.index*spacing));
            float residual=residualSum/support.size();

            double score=distinct*30.0+support.size()*8.0
                    -(residual/Math.max(1f,spacing))*45.0
                    -(my/Math.max(1f,h))*4.0;
            if(score>bestScore) {
                bestScore=score;
                best=new FilterLattice(my,spacing,origin,distinct,residual);
            }
        }
        return best;
    }

    /** Compatibility wrapper used by selection-page proof. */
    public static FilterRowGeometry detectFilterRowGeometry(Bitmap b) {
        FilterLattice l=detectFilterLattice(b);
        if(l==null) return null;
        float w=b.getWidth();
        float fromX=w*0.80f;
        float toX=Math.max(w*0.32f,fromX-Math.min(w*0.42f,l.spacing*5.0f));
        return new FilterRowGeometry(l.rowY,fromX,toX,l.spacing,l.evidenceCount);
    }

    private static Hsv patchHsv(Bitmap b,float cx,float cy,float radius) {
        int w=b.getWidth(),h=b.getHeight();
        int r=Math.max(2,Math.round(radius));
        long sr=0,sg=0,sb=0,n=0;
        int x0=Math.max(0,Math.round(cx)-r),x1=Math.min(w-1,Math.round(cx)+r);
        int y0=Math.max(0,Math.round(cy)-r),y1=Math.min(h-1,Math.round(cy)+r);
        int step=Math.max(1,r/4);
        for(int y=y0;y<=y1;y+=step) for(int x=x0;x<=x1;x+=step) {
            int col=b.getPixel(x,y);
            sr+=(col>>16)&255; sg+=(col>>8)&255; sb+=col&255; n++;
        }
        if(n==0) return new Hsv(0,0,0);
        int color=(0xff<<24)|(((int)(sr/n)&255)<<16)|(((int)(sg/n)&255)<<8)|((int)(sb/n)&255);
        return hsv(color);
    }

    public static boolean filterTargetLooksPlausible(Bitmap b,FilterLattice l,PilotConfig.PikminType type) {
        if(l==null) return false;
        float x=l.targetX(type), w=b.getWidth();
        if(Float.isNaN(x)||x<w*0.055f||x>w*0.945f) return false;
        Hsv v=patchHsv(b,x,l.rowY,Math.max(4f,Math.min(b.getWidth(),b.getHeight())*0.010f));
        switch(type) {
            case PURPLE:
                return v.h>=270&&v.h<=338&&v.s>=0.13&&v.v>=0.58;
            case PINK:
                return v.h>=270&&v.h<=345&&v.s>=0.07&&v.v>=0.72;
            case WHITE:
                return v.s<=0.32&&v.v>=0.68;
            case ROCK:
                return v.s<=0.32&&v.v>=0.22&&v.v<=0.86;
            default:
                return false;
        }
    }

    public static float filterRowSaturationScore(Bitmap b,FilterLattice l) {
        if(l==null) return -1f;
        int[] idx={0,1,2,3,7};
        float sum=0; int n=0; float w=b.getWidth();
        float radius=Math.max(4f,Math.min(b.getWidth(),b.getHeight())*0.010f);
        for(int i:idx) {
            float x=l.originX+i*l.spacing;
            if(x<w*0.055f||x>w*0.945f) continue;
            Hsv v=patchHsv(b,x,l.rowY,radius);
            if(v.v<0.35) continue;
            sum+=(float)v.s; n++;
        }
        return n>=3?sum/n:-1f;
    }

    /**
     * Direct Android port of ImageAutomationDetector.detectPikminFilter() from
     * iOS Stage 11.5.4.31.  Purple and pink are the two magenta anchors; their
     * separation is exactly two chip slots.  No generic colour-row spacing is
     * blended into the final tap point, because that caused second-round drift
     * on phones/accounts with different UI content.
     */
    public static PointF detectPikminFilter(Bitmap b, PilotConfig.PikminType type) {
        int w=b.getWidth(), h=b.getHeight();
        RectF vp=activeContentRect(b);
        double minDim=Math.max(1.0,Math.min(vp.width(),vp.height()));
        int x0=Math.max(0,(int)(vp.left+vp.width()*0.12));
        int x1=Math.min(w,(int)(vp.right-vp.width()*0.03));
        int y0=Math.max(0,(int)(vp.top+vp.height()*0.22));
        int y1=Math.min(h,(int)(vp.top+vp.height()*0.66));

        boolean[] mask=new boolean[w*h];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++){
            int color=b.getPixel(x,y);
            Hsv v=hsv(color);
            int r=(color>>16)&255, g=(color>>8)&255, bl=color&255;
            boolean magenta=v.h>=278&&v.h<=332&&v.s>=0.16&&v.v>=0.58&&
                    r>g+22&&bl>g+8;
            if(magenta) mask[y*w+x]=true;
        }

        List<Component> candidates=new ArrayList<>();
        for(Component c:components(mask,w,h)){
            double wf=c.rect.width()/minDim, hf=c.rect.height()/minDim;
            if(c.count<minDim*minDim*0.00012) continue;
            if(wf<0.020||wf>0.115||hf<0.012||hf>0.090) continue;
            candidates.add(c);
        }

        PointF left=null,right=null;
        double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<candidates.size();i++) for(int j=i+1;j<candidates.size();j++){
            PointF a=candidates.get(i).center(), bb=candidates.get(j).center();
            if(a.x>bb.x){PointF t=a;a=bb;bb=t;}
            double dx=bb.x-a.x, dy=Math.abs(bb.y-a.y);
            if(dx<vp.width()*0.08||dx>vp.width()*0.30) continue;
            if(dy>Math.max(minDim*0.045,8)) continue;
            double spacingScore=Math.abs(dx/Math.max(1.0,vp.width())-0.166);
            double verticalScore=dy/Math.max(1.0,vp.height());
            double midY=(a.y+bb.y)*0.5;
            double middleBias=Math.abs((midY-vp.centerY())/Math.max(1.0,vp.height()))*0.12;
            double score=spacingScore+verticalScore*2.5+middleBias;
            if(score<best){best=score;left=a;right=bb;}
        }
        if(left==null||right==null) return null;

        float spacing=(right.x-left.x)*0.5f;
        float rowY=(left.y+right.y)*0.5f;
        float tx;
        switch(type){
            case PURPLE: tx=left.x; break;
            case WHITE: tx=left.x+spacing; break;
            case PINK: tx=right.x; break;
            case ROCK: tx=right.x+spacing; break;
            default: return null;
        }
        if(tx>=vp.right-Math.max(2,minDim*0.01)) return null;
        return new PointF(tx,rowY);
    }


    /**
     * Android-safe variant of the iOS magenta-pair detector.
     *
     * The iOS detector can search a broad vertical band because XCTest screenshots
     * are very consistent. Android selection sheets move more between devices and
     * accounts, and Pikmin/decor art below the filter strip can itself contain two
     * magenta components. Restrict the iOS purple/pink pair search to a row that was
     * independently located from the chip strip or the 飾品/自動 OCR anchor.
     */
    public static PointF detectPikminFilterNearRow(
            Bitmap b, PilotConfig.PikminType type, float rowY, float tolerance) {
        int w=b.getWidth(), h=b.getHeight();
        RectF vp=activeContentRect(b);
        double minDim=Math.max(1.0,Math.min(vp.width(),vp.height()));
        int x0=Math.max(0,(int)(vp.left+vp.width()*0.12));
        int x1=Math.min(w,(int)(vp.right-vp.width()*0.03));
        int y0=Math.max(0,(int)Math.floor(rowY-Math.max(10f,tolerance)));
        int y1=Math.min(h,(int)Math.ceil(rowY+Math.max(10f,tolerance)));
        // The colour-filter strip lives in the upper/middle selection sheet.
        int hardTop=Math.max(0,(int)(vp.top+vp.height()*0.20));
        int hardBottom=Math.min(h,(int)(vp.top+vp.height()*0.58));
        y0=Math.max(y0,hardTop);
        y1=Math.min(y1,hardBottom);
        if(y1<=y0) return null;

        boolean[] mask=new boolean[w*h];
        for(int y=y0;y<y1;y++) for(int x=x0;x<x1;x++){
            int color=b.getPixel(x,y);
            Hsv v=hsv(color);
            int r=(color>>16)&255, g=(color>>8)&255, bl=color&255;
            boolean magenta=v.h>=278&&v.h<=332&&v.s>=0.16&&v.v>=0.58&&
                    r>g+22&&bl>g+8;
            if(magenta) mask[y*w+x]=true;
        }

        List<Component> candidates=new ArrayList<>();
        for(Component c:components(mask,w,h)){
            double wf=c.rect.width()/minDim, hf=c.rect.height()/minDim;
            double aspect=c.rect.width()/Math.max(1.0,c.rect.height());
            double fill=c.count/Math.max(1.0,c.rect.width()*c.rect.height());
            if(c.count<minDim*minDim*0.00012) continue;
            if(wf<0.020||wf>0.100||hf<0.012||hf>0.078) continue;
            if(aspect<0.55||aspect>1.80||fill<0.18) continue;
            if(Math.abs(c.center().y-rowY)>Math.max(12f,tolerance)) continue;
            candidates.add(c);
        }

        PointF left=null,right=null;
        double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<candidates.size();i++) for(int j=i+1;j<candidates.size();j++){
            PointF a=candidates.get(i).center(), bb=candidates.get(j).center();
            if(a.x>bb.x){PointF t=a;a=bb;bb=t;}
            double dx=bb.x-a.x, dy=Math.abs(bb.y-a.y);
            if(dx<vp.width()*0.10||dx>vp.width()*0.25) continue;
            if(dy>Math.max(minDim*0.030,10)) continue;
            double spacingScore=Math.abs(dx/Math.max(1.0,vp.width())-0.166);
            double verticalScore=dy/Math.max(1.0,vp.height());
            double rowScore=Math.abs(((a.y+bb.y)*0.5-rowY))/Math.max(1.0,vp.height());
            double score=spacingScore+verticalScore*3.0+rowScore*5.0;
            if(score<best){best=score;left=a;right=bb;}
        }
        if(left==null||right==null) return null;

        float spacing=(right.x-left.x)*0.5f;
        float targetY=(left.y+right.y)*0.5f;
        float tx;
        switch(type){
            case PURPLE: tx=left.x; break;
            case WHITE: tx=left.x+spacing; break;
            case PINK: tx=right.x; break;
            case ROCK: tx=right.x+spacing; break;
            default: return null;
        }
        if(tx<vp.left+vp.width()*0.08f||tx>=vp.right-Math.max(2,minDim*0.01)) return null;
        return new PointF(tx,targetY);
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

    /**
     * Universal carrying-close detector. Primary path is the white X glyph plus
     * surrounding dark-green ring, ported from the later iOS universal detector.
     * Only if that structural proof fails do we use the older green-component
     * fallback. This sharply reduces false positives from grass/flowers.
     */
    public static PointF detectCarryingClose(Bitmap b) {
        PointF glyph=detectCarryingCloseGlyph(b);
        return glyph!=null?glyph:detectCarryingCloseLegacy(b);
    }

    public static PointF detectCarryingCloseGlyph(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(); RectF vp=activeContentRect(b);
        if(vp.width()<=1||vp.height()<=1)return null;
        int x0=Math.max(0,(int)Math.floor(vp.left+vp.width()*0.02f));
        int x1=Math.min(w,(int)Math.ceil(vp.left+vp.width()*0.24f));
        int y0=Math.max(0,(int)Math.floor(vp.top+vp.height()*0.82f));
        int y1=Math.min(h,(int)Math.ceil(vp.top+vp.height()*0.985f));
        if(x1<=x0||y1<=y0)return null;

        int rw=x1-x0,rh=y1-y0; boolean[] white=new boolean[rw*rh];
        for(int y=y0;y<y1;y++)for(int x=x0;x<x1;x++){
            Hsv v=hsv(b.getPixel(x,y));
            if(v.s<=0.22&&v.v>=0.80)white[(y-y0)*rw+(x-x0)]=true;
        }

        double vpArea=Math.max(1.0,vp.width()*vp.height());
        double shortEdge=Math.max(1.0,Math.min(vp.width(),vp.height()));
        PointF bestPoint=null; double bestScore=-1e9;
        for(Component local:components(white,rw,rh)){
            RectF rect=new RectF(local.rect); rect.offset(x0,y0);
            PointF center=new PointF(rect.centerX(),rect.centerY());
            double nx=(center.x-vp.left)/Math.max(1.0,vp.width());
            double ny=(center.y-vp.top)/Math.max(1.0,vp.height());
            if(nx<0.025||nx>0.24||ny<0.82||ny>0.985)continue;
            double wf=rect.width()/Math.max(1.0,vp.width()),hf=rect.height()/Math.max(1.0,vp.height());
            if(wf<0.008||wf>0.055||hf<0.006||hf>0.055)continue;
            double aspect=rect.width()/Math.max(1.0,rect.height());
            if(aspect<0.48||aspect>1.85)continue;
            double rectArea=Math.max(1.0,rect.width()*rect.height());
            double fill=local.count/rectArea,areaFraction=local.count/vpArea;
            if(fill<0.12||fill>0.72||areaFraction<0.000008||areaFraction>0.0010)continue;

            int rx0=Math.max(0,(int)Math.floor(rect.left)),rx1=Math.min(w,(int)Math.ceil(rect.right));
            int ry0=Math.max(0,(int)Math.floor(rect.top)),ry1=Math.min(h,(int)Math.ceil(rect.bottom));
            int glyphWhite=0,diagA=0,diagB=0;
            for(int y=ry0;y<ry1;y++)for(int x=rx0;x<rx1;x++){
                Hsv v=hsv(b.getPixel(x,y)); if(v.s>0.22||v.v<0.80)continue; glyphWhite++;
                double u=(x+0.5-rect.left)/Math.max(1.0,rect.width());
                double vv=(y+0.5-rect.top)/Math.max(1.0,rect.height());
                if(Math.abs(vv-u)<=0.19)diagA++;
                if(Math.abs(vv-(1.0-u))<=0.19)diagB++;
            }
            if(glyphWhite<=0)continue;
            double da=(double)diagA/glyphWhite,db=(double)diagB/glyphWhite;
            if(da<0.34||db<0.34)continue;

            double glyphScale=Math.max(rect.width(),rect.height());
            double inner=Math.max(glyphScale*0.62,shortEdge*0.007);
            double outer=Math.max(glyphScale*2.45,shortEdge*0.045);
            double inner2=inner*inner,outer2=outer*outer;
            int sx0=Math.max(0,(int)Math.floor(center.x-outer)),sx1=Math.min(w-1,(int)Math.ceil(center.x+outer));
            int sy0=Math.max(0,(int)Math.floor(center.y-outer)),sy1=Math.min(h-1,(int)Math.ceil(center.y+outer));
            int step=Math.max(1,(int)(shortEdge/900.0));
            int total=0,green=0;
            for(int y=sy0;y<=sy1;y+=step)for(int x=sx0;x<=sx1;x+=step){
                double dx=x+0.5-center.x,dy=y+0.5-center.y,d2=dx*dx+dy*dy;
                if(d2<inner2||d2>outer2)continue;
                Hsv v=hsv(b.getPixel(x,y));
                if(v.h>=105&&v.h<=205&&v.s>=0.22&&v.v>=0.14&&v.v<=0.92)green++;
                total++;
            }
            if(total<=0)continue; double gf=(double)green/total; if(gf<0.72)continue;
            double score=gf*2.0+Math.min(da,db)+fill*0.20-nx*0.08-Math.abs(ny-0.93)*0.08;
            if(score>bestScore){bestScore=score;bestPoint=center;}
        }
        return bestPoint;
    }

    private static PointF detectCarryingCloseLegacy(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(); RectF vp=activeContentRect(b);
        int x0=Math.max(0,(int)(vp.left+vp.width()*0.02)),x1=Math.min(w,(int)(vp.left+vp.width()*0.24));
        int y0=Math.max(0,(int)(vp.top+vp.height()*0.82)),y1=Math.min(h,(int)(vp.top+vp.height()*0.985));
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
            if(nx<0.025||nx>0.24||ny<0.82||ny>0.985)continue;
            int white=0,sampled=0;
            for(int yy=Math.max(0,(int)c.rect.top);yy<=Math.min(h-1,(int)c.rect.bottom);yy+=2)
                for(int xx=Math.max(0,(int)c.rect.left);xx<=Math.min(w-1,(int)c.rect.right);xx+=2){
                    Hsv v=hsv(b.getPixel(xx,yy)); if(v.s<=0.20&&v.v>=0.82)white++; sampled++;
                }
            double wfraction=sampled>0?(double)white/sampled:0; if(wfraction<0.006)continue;
            double score=Math.abs(Math.log(Math.max(0.001,aspect)))+nx*0.055+Math.abs(ny-0.93)*0.030-
                    Math.min(0.20,c.count/area*14.0)-Math.min(0.10,wfraction*2.5);
            if(score<best){best=score;bestPoint=center;}
        }
        return bestPoint;
    }

    /**
     * Refine the coloured-background component centre to the white X itself.
     * Gradient fills can make the green mask occupy only one side of the round
     * button on some displays; the white cross is a better tap target.
     */
    public static PointF refineCarryingCloseTapPoint(Bitmap b, PointF approx) {
        if(approx==null) return null;
        int w=b.getWidth(), h=b.getHeight();
        int radius=Math.max(20,(int)(Math.min(w,h)*0.070));
        int x0=Math.max(0,(int)approx.x-radius), x1=Math.min(w-1,(int)approx.x+radius);
        int y0=Math.max(0,(int)approx.y-radius), y1=Math.min(h-1,(int)approx.y+radius);
        double sx=0,sy=0,sw=0;
        for(int y=y0;y<=y1;y++) for(int x=x0;x<=x1;x++) {
            Hsv v=hsv(b.getPixel(x,y));
            if(v.s<=0.22&&v.v>=0.78) {
                double dx=x-approx.x,dy=y-approx.y;
                double d2=dx*dx+dy*dy;
                if(d2>radius*radius) continue;
                // Prefer white pixels close to the detected green component so
                // nearby flowers/text do not pull the point away from the X.
                double weight=1.0/(1.0+d2/(radius*radius*0.20));
                sx+=x*weight; sy+=y*weight; sw+=weight;
            }
        }
        if(sw<8.0) return approx;
        PointF refined=new PointF((float)(sx/sw),(float)(sy/sw));
        float maxShift=radius*0.50f;
        float dx=refined.x-approx.x,dy=refined.y-approx.y;
        if(dx*dx+dy*dy>maxShift*maxShift) return approx;
        return refined;
    }

    /**
     * Adaptive 5x3 Pikmin grid whose TAP POINTS are geometric cell centres.
     *
     * We still measure each row Y from the live screenshot, but we deliberately
     * do NOT chase the visually strongest point inside each cell.  Oversized
     * Decor (airplanes, cups, hats, etc.) can extend far away from a Pikmin body
     * and used to drag slot 6 off target on some accounts.
     *
     * Row discovery uses a trimmed five-column score (middle three values), so
     * one unusually large Decor in a row cannot dominate the row-Y estimate.
     */
    public static List<PointF> detectPikminSelectionGrid(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight();
        RectF vp=activeContentRect(b);
        double[] cols={0.129,0.313,0.492,0.672,0.849};

        double aspect=vp.width()/Math.max(1,vp.height());
        double t=Math.min(1,Math.max(0,(aspect-0.48)/(0.70-0.48)));
        double[] phone={0.517,0.662,0.801},tablet={0.550,0.720,0.885};
        double[] rows=new double[3];
        int radius=Math.max(5,(int)(Math.min(vp.width(),vp.height())*0.018));
        int collectiveStep=Math.max(3,(int)(vp.height()/260.0));

        for(int r=0;r<3;r++){
            double seed=phone[r]+(tablet[r]-phone[r])*t;
            double seedY=vp.top+vp.height()*seed;
            double bestY=seedY,bestScore=-1e9;

            for(int cy=(int)(seedY-vp.height()*0.060);cy<=(int)(seedY+vp.height()*0.060);cy+=collectiveStep){
                double[] scores=new double[5];
                int used=0;
                for(double col:cols){
                    int cx=(int)(vp.left+vp.width()*col);
                    if(cx>1&&cx<w-2&&cy>1&&cy<h-2) scores[used++]=visualScore(b,cx,cy,radius);
                }
                if(used<3) continue;
                java.util.Arrays.sort(scores,0,used);
                double robust;
                if(used>=5) robust=(scores[1]+scores[2]+scores[3])/3.0;
                else if(used==4) robust=(scores[1]+scores[2])*0.5;
                else robust=scores[1];
                if(robust>bestScore){bestScore=robust;bestY=cy;}
            }
            rows[r]=(bestY-vp.top)/Math.max(1f,vp.height());
        }

        List<PointF> out=new ArrayList<>(15);
        for(double row:rows){
            float y=(float)(vp.top+vp.height()*row);
            for(double col:cols){
                float x=(float)(vp.left+vp.width()*col);
                out.add(new PointF(x,y));
            }
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
