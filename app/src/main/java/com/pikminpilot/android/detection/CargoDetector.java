package com.pikminpilot.android.detection;

import android.graphics.Bitmap;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;
import com.pikminpilot.android.model.PilotConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Android port of the list-classification path in FruitDetector.swift.
 * Safety semantics intentionally mirror the iOS build:
 * - BUSY / COMPLETE bordered cards are not tappable.
 * - fruit requires a known fruit label near the detected object.
 * - seedling requires an OCR label containing 色花苗, or the two proven
 *   exceptions 冰藍花苗 / 大花苗. Plain 花苗 is explicitly rejected.
 */
public final class CargoDetector {
    private CargoDetector() {}

    public enum Kind { FRUIT, SEEDLING, UNKNOWN }
    public enum CardState { BUSY, COMPLETE, BLOCKED }

    public static final class OcrItem {
        public final String text;
        public final RectF rect;
        public OcrItem(String text, RectF rect) { this.text=text; this.rect=rect; }
    }

    public static final class Candidate {
        public final PointF center;
        public final RectF rect;
        public final Kind kind;
        public final String label;
        public Candidate(PointF center, RectF rect, Kind kind, String label) {
            this.center=center; this.rect=rect; this.kind=kind; this.label=label;
        }
    }

    public static final class StatusCard {
        public final CardState state;
        public final RectF rect;
        public StatusCard(CardState state, RectF rect) { this.state=state; this.rect=rect; }
    }

    public static final class Result {
        public final List<Candidate> fruits;
        public final List<Candidate> seedlings;
        public final List<Candidate> blocked;
        public final List<StatusCard> cards;
        public final List<OcrItem> ocr;
        public Result(List<Candidate> fruits, List<Candidate> seedlings, List<Candidate> blocked,
                      List<StatusCard> cards, List<OcrItem> ocr) {
            this.fruits=fruits; this.seedlings=seedlings; this.blocked=blocked; this.cards=cards; this.ocr=ocr;
        }
        public List<Candidate> matching(PilotConfig.CargoMode mode) {
            List<Candidate> out=new ArrayList<>();
            if(mode==PilotConfig.CargoMode.FRUIT || mode==PilotConfig.CargoMode.BOTH) out.addAll(fruits);
            if(mode==PilotConfig.CargoMode.SEEDLING || mode==PilotConfig.CargoMode.BOTH) out.addAll(seedlings);
            out.sort(CANDIDATE_ORDER);
            return out;
        }
    }

    private static final Comparator<Candidate> CANDIDATE_ORDER=(a,b)->
            Math.abs(a.center.y-b.center.y)>12 ? Float.compare(a.center.y,b.center.y) : Float.compare(a.center.x,b.center.x);

    private static final List<String> KNOWN_FRUITS=Arrays.asList(
            "青蘋果","蘋果","苹果","檸檬","柠檬","桃子","梅子","柳橙","橘子","橙子",
            "葡萄","青葡萄","草莓","櫻桃","樱桃","藍莓","蓝莓","萊姆","莱姆","酸橙","柚子"
    );

    private static TextRecognizer newRecognizer() {
        return TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
    }

    public static List<OcrItem> recognize(Bitmap bitmap) throws Exception {
        TextRecognizer recognizer=newRecognizer();
        try {
            Text result=Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap,0)), 8, TimeUnit.SECONDS);
            List<OcrItem> out=new ArrayList<>();
            for(Text.TextBlock block:result.getTextBlocks()) {
                for(Text.Line line:block.getLines()) {
                    Rect r=line.getBoundingBox();
                    if(r!=null && line.getText()!=null && !line.getText().trim().isEmpty())
                        out.add(new OcrItem(line.getText(),new RectF(r)));
                }
            }
            return out;
        } finally {
            recognizer.close();
        }
    }

    public static Result scan(Bitmap b) throws Exception {
        int w=b.getWidth(), h=b.getHeight();
        List<OcrItem> ocr=recognize(b);
        List<StatusCard> cards=detectStatusCards(b);
        List<Candidate> fruit=new ArrayList<>(), seed=new ArrayList<>(), blocked=new ArrayList<>();

        boolean[] mask=new boolean[w*h];
        int startY=(int)(h*0.16), endY=(int)(h*0.96);
        for(int y=startY;y<endY;y++) for(int x=0;x<w;x++) {
            Hsv v=hsv(b.getPixel(x,y));
            if(isFruitColor(v)) mask[y*w+x]=true;
        }

        double colWidth=w/3.0;
        for(Component c:components(mask,w,h)) {
            double bw=c.rect.width(), bh=c.rect.height();
            if(bw<w*0.040||bw>w*0.185||bh<h*0.018||bh>h*0.110) continue;
            double aspect=bw/Math.max(1,bh), fill=c.count/Math.max(1.0,bw*bh);
            if(aspect<0.48||aspect>2.0||fill<0.42) continue;
            PointF center=c.center();
            if(insideAnyCard(center,cards)) continue;
            int col=Math.min(2,Math.max(0,(int)(center.x/colWidth)));
            double expected=(col+0.5)*colWidth;
            if(Math.abs(center.x-expected)>colWidth*0.30) continue;

            String label=nearbyLabel(c.rect,w,h,ocr);
            Kind kind=isKnownFruitLabel(label)?Kind.FRUIT:(isSeedlingLabel(label)?Kind.SEEDLING:Kind.UNKNOWN);
            Candidate candidate=new Candidate(center,c.rect,kind,label);
            if(kind==Kind.FRUIT) fruit.add(candidate); else if(kind==Kind.SEEDLING) seed.add(candidate); else blocked.add(candidate);
        }

        // Same OCR-first seedling fallback as iOS: gray pots can disappear from the color mask.
        for(OcrItem item:ocr) if(isSeedlingLabel(item.text)) {
            int col=Math.min(2,Math.max(0,(int)(item.rect.centerX()/colWidth)));
            float cx=(float)((col+0.5)*colWidth);
            float cy=(float)Math.max(h*0.17, item.rect.top-h*0.070);
            PointF center=new PointF(cx,cy);
            if(insideAnyCardOrLabel(center,item.rect,cards)) continue;
            RectF rect=new RectF((float)(cx-w*0.060),(float)(cy-h*0.045),(float)(cx+w*0.060),(float)(cy+h*0.045));
            Candidate candidate=new Candidate(center,rect,Kind.SEEDLING,item.text);
            if(!containsNear(seed,candidate,w*0.10,h*0.08)) seed.add(candidate);
        }

        return new Result(dedup(fruit,w,h),dedup(seed,w,h),dedup(blocked,w,h),cards,ocr);
    }

    public static boolean isSeedlingLabel(String text) {
        String n=normalize(text);
        if(n.equals("花苗")) return false;
        return n.contains("色花苗") || n.contains("冰藍花苗") || n.contains("冰蓝花苗") || n.contains("大花苗");
    }

    public static boolean isKnownFruitLabel(String text) {
        if(isSeedlingLabel(text)) return false;
        for(String s:KNOWN_FRUITS) if(text!=null && text.contains(s)) return true;
        return false;
    }

    public static boolean hasExpeditionCta(List<OcrItem> items) {
        for(OcrItem i:items) {
            String t=normalize(i.text).toLowerCase();
            if(t.contains("前往探險")||t.contains("前往探险")||t.contains("gotoexpedition")||t.contains("探検へ")||t.contains("探險へ")) return true;
        }
        return false;
    }

    public static PointF expeditionCtaPoint(List<OcrItem> items) {
        for(OcrItem i:items) {
            String t=normalize(i.text).toLowerCase();
            if(t.contains("前往探險")||t.contains("前往探险")||t.contains("gotoexpedition")||t.contains("探検へ")||t.contains("探險へ"))
                return new PointF(i.rect.centerX(),i.rect.centerY());
        }
        return null;
    }

    public static boolean hasSelectionHeader(List<OcrItem> items) {
        for(OcrItem i:items) {
            String t=normalize(i.text).toLowerCase();
            if(t.contains("可以選擇最多")||t.contains("可以选择最多")||t.contains("選擇最多")||t.contains("选择最多")||t.contains("selectupto")) return true;
        }
        return false;
    }

    public static boolean hasExpeditionTab(List<OcrItem> items) {
        for(OcrItem i:items) {
            String t=normalize(i.text);
            if((t.equals("探險")||t.equals("探险")) && i.rect.centerY()<0.30f*maxBottom(items)) return true;
        }
        return false;
    }

    private static float maxBottom(List<OcrItem> items){float m=1;for(OcrItem i:items)m=Math.max(m,i.rect.bottom);return m;}
    private static String normalize(String s){return s==null?"":s.replace(" ","").replace("\n","").replace("\t","");}

    private static String nearbyLabel(RectF object,int w,int h,List<OcrItem> ocr) {
        double colWidth=w/3.0; int col=Math.min(2,Math.max(0,(int)(object.centerX()/colWidth)));
        double x0=col*colWidth,x1=(col+1)*colWidth,y0=object.bottom-h*0.004,y1=Math.min(h,object.bottom+h*0.070);
        List<OcrItem> found=new ArrayList<>();
        for(OcrItem i:ocr) if(i.rect.centerX()>=x0&&i.rect.centerX()<=x1&&i.rect.centerY()>=y0&&i.rect.centerY()<=y1) found.add(i);
        found.sort(Comparator.comparingDouble(i->i.rect.top));
        StringBuilder sb=new StringBuilder(); for(OcrItem i:found){if(sb.length()>0)sb.append(' ');sb.append(i.text);} return sb.toString();
    }

    private static boolean insideAnyCard(PointF p,List<StatusCard> cards){for(StatusCard c:cards){RectF r=new RectF(c.rect);r.inset(-8,-8);if(r.contains(p.x,p.y))return true;}return false;}
    private static boolean insideAnyCardOrLabel(PointF p,RectF label,List<StatusCard> cards){for(StatusCard c:cards){RectF r=new RectF(c.rect);r.inset(-8,-8);if(r.contains(p.x,p.y)||RectF.intersects(r,label))return true;}return false;}
    private static boolean containsNear(List<Candidate> list,Candidate x,double dx,double dy){for(Candidate a:list)if(Math.abs(a.center.x-x.center.x)<dx&&Math.abs(a.center.y-x.center.y)<dy)return true;return false;}
    private static List<Candidate> dedup(List<Candidate> in,int w,int h){in.sort(CANDIDATE_ORDER);List<Candidate> o=new ArrayList<>();for(Candidate x:in)if(!containsNear(o,x,w*0.045,h*0.035))o.add(x);return o;}

    // --- status-card detection ported from FruitDetector.swift ---
    private static final class Band { final CardState state; final int col,y0,y1; Band(CardState s,int c,int a,int b){state=s;col=c;y0=a;y1=b;} double center(){return(y0+y1)/2.0;} }

    private static List<StatusCard> detectStatusCards(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight(); List<Band> bands=horizontalBands(b); List<StatusCard> cards=new ArrayList<>();
        for(CardState state:new CardState[]{CardState.BUSY,CardState.COMPLETE}) for(int col=0;col<3;col++) {
            List<Band> local=new ArrayList<>();for(Band x:bands)if(x.state==state&&x.col==col)local.add(x);local.sort(Comparator.comparingDouble(Band::center));
            Set<Integer> used=new HashSet<>();
            for(int i=0;i<local.size();i++) if(!used.contains(i)) {
                Integer best=null; for(int j=i+1;j<local.size();j++) if(!used.contains(j)) {double d=local.get(j).center()-local.get(i).center();if(d>=h*0.105&&d<=h*0.175){best=j;break;}if(d>h*0.175)break;}
                if(best!=null){used.add(i);used.add(best);cards.add(new StatusCard(state,cardRect(col,local.get(i).y0,local.get(best).y1,w)));}
            }
            for(int i=0;i<local.size();i++) if(!used.contains(i)) {
                Band band=local.get(i);int y=(int)band.center(),span=(int)(h*0.17),gap=Math.max(3,(int)(h*0.004));
                int above=verticalRows(b,state,col,y-span,y-gap),below=verticalRows(b,state,col,y+gap,y+span),min=Math.max(10,(int)(h*0.018));
                if(above>=min&&above>below) cards.add(new StatusCard(CardState.BLOCKED,cardRect(col,Math.max(0,y-span),band.y1,w)));
                else if(below>=min) cards.add(new StatusCard(CardState.BLOCKED,cardRect(col,band.y0,Math.min(h,y+span),w)));
            }
        }
        List<StatusCard> dedup=new ArrayList<>();cards.sort(Comparator.comparingDouble(c->c.rect.top));
        for(StatusCard c:cards){boolean dup=false;for(StatusCard old:dedup){RectF inter=new RectF();if(inter.setIntersect(old.rect,c.rect)){double smaller=Math.min(old.rect.width()*old.rect.height(),c.rect.width()*c.rect.height());if(smaller>0&&inter.width()*inter.height()/smaller>0.78){dup=true;break;}}}if(!dup)dedup.add(c);}return dedup;
    }

    private static List<Band> horizontalBands(Bitmap b){int w=b.getWidth(),h=b.getHeight();double cw=w/3.0;List<Object[]> hits=new ArrayList<>();for(int y=(int)(h*0.16);y<(int)(h*0.98);y++)for(int col=0;col<3;col++){int x0=Math.max(0,(int)(col*cw+w*0.030)),x1=Math.min(w-1,(int)((col+1)*cw-w*0.030));if(x1<=x0)continue;int total=x1-x0+1,busy=0,complete=0;for(int x=x0;x<=x1;x++){int p=b.getPixel(x,y);if(isBusy(p))busy++;if(isComplete(p))complete++;}if((double)busy/total>=0.55)hits.add(new Object[]{CardState.BUSY,col,y});if((double)complete/total>=0.55)hits.add(new Object[]{CardState.COMPLETE,col,y});}
        List<Band> out=new ArrayList<>();for(CardState state:new CardState[]{CardState.BUSY,CardState.COMPLETE})for(int col=0;col<3;col++){List<Integer> ys=new ArrayList<>();for(Object[] h1:hits)if(h1[0]==state&&((Integer)h1[1])==col)ys.add(((Integer)h1[2]));ys.sort(Integer::compareTo);if(ys.isEmpty())continue;int s=ys.get(0),prev=s;for(int k=1;k<ys.size();k++){int y=ys.get(k);if(y<=prev+1)prev=y;else{out.add(new Band(state,col,s,prev));s=prev=y;}}out.add(new Band(state,col,s,prev));}return out;}
    private static int verticalRows(Bitmap b,CardState state,int col,int y0,int y1){if(y1<=y0)return 0;int w=b.getWidth(),h=b.getHeight();double cw=w/3.0,left=col*cw+w*0.030,right=(col+1)*cw-w*0.030;int strip=Math.max(3,(int)(w*0.010)),lx0=Math.max(0,(int)left-strip),lx1=Math.min(w-1,(int)left+strip),rx0=Math.max(0,(int)right-strip),rx1=Math.min(w-1,(int)right+strip),yy0=Math.max(0,y0),yy1=Math.min(h-1,y1),rows=0;if(yy1<=yy0)return 0;for(int y=yy0;y<=yy1;y++){int hits=0;for(int x=lx0;x<=lx1;x++)if(border(state,b.getPixel(x,y)))hits++;for(int x=rx0;x<=rx1;x++)if(border(state,b.getPixel(x,y)))hits++;if(hits>=2)rows++;}return rows;}
    private static RectF cardRect(int col,double y0,double y1,int w){double cw=w/3.0,x0=col*cw+w*0.024,x1=(col+1)*cw-w*0.024;return new RectF((float)x0,(float)y0,(float)x1,(float)Math.max(y0+1,y1));}
    private static boolean border(CardState s,int p){return s==CardState.BUSY?isBusy(p):s==CardState.COMPLETE&&isComplete(p);}
    private static boolean isBusy(int p){int r=(p>>16)&255,g=(p>>8)&255,bb=p&255,mx=Math.max(r,Math.max(g,bb)),mn=Math.min(r,Math.min(g,bb));return r>230&&g>220&&bb>220&&r-g>=3&&r-bb>=2&&mx-mn<35;}
    private static boolean isComplete(int p){int r=(p>>16)&255,g=(p>>8)&255,bb=p&255,mx=Math.max(r,Math.max(g,bb)),mn=Math.min(r,Math.min(g,bb));return g>220&&r>200&&bb>200&&g-r>=4&&g-bb>=2&&mx-mn<40;}

    private static boolean isFruitColor(Hsv v){boolean colorful=v.s>0.30&&v.v>0.24,purple=v.h>=235&&v.h<=335&&v.s>0.09&&v.v>0.11,red=(v.h<=28||v.h>=332)&&v.s>0.20&&v.v>0.18;return colorful||purple||red;}
    private static Hsv hsv(int color){float[] a=new float[3];android.graphics.Color.colorToHSV(color,a);return new Hsv(a[0],a[1],a[2]);}
    private static final class Hsv{final double h,s,v;Hsv(double h,double s,double v){this.h=h;this.s=s;this.v=v;}}
    private static final class Component{final RectF rect;final int count;Component(RectF r,int c){rect=r;count=c;}PointF center(){return new PointF(rect.centerX(),rect.centerY());}}
    private static List<Component> components(boolean[] mask,int w,int h){boolean[] vis=new boolean[mask.length];List<Component> out=new ArrayList<>();int[] dx={1,-1,0,0},dy={0,0,1,-1};for(int y=0;y<h;y++)for(int x=0;x<w;x++){int idx=y*w+x;if(vis[idx]||!mask[idx])continue;ArrayDeque<Integer> q=new ArrayDeque<>();q.add(idx);vis[idx]=true;int minx=x,maxx=x,miny=y,maxy=y,count=0;while(!q.isEmpty()){int z=q.removeFirst(),cx=z%w,cy=z/w;count++;minx=Math.min(minx,cx);maxx=Math.max(maxx,cx);miny=Math.min(miny,cy);maxy=Math.max(maxy,cy);for(int k=0;k<4;k++){int nx=cx+dx[k],ny=cy+dy[k];if(nx<0||ny<0||nx>=w||ny>=h)continue;int ni=ny*w+nx;if(!vis[ni]&&mask[ni]){vis[ni]=true;q.add(ni);}}}out.add(new Component(new RectF(minx,miny,maxx+1,maxy+1),count));}return out;}
}
