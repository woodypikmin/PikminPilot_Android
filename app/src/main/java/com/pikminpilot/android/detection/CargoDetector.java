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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.TimeUnit;

/**
 * Android port of the list-classification path in FruitDetector.swift.
 * Safety semantics intentionally mirror the iOS build:
 * - BUSY / COMPLETE bordered cards are not tappable.
 * - fruit uses exclusion-first OCR: once an AVAILABLE 3-column object is found,
 *   any non-empty nearby label is fruit unless it is explicitly a seedling/gift.
 *   This avoids depending on an ever-growing positive fruit dictionary.
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

    public static final class SelectionObserved {
        public final int selected;
        public final int maximum;
        public final String source;
        public SelectionObserved(int selected,int maximum,String source){
            this.selected=selected;this.maximum=maximum;this.source=source;
        }
        @Override public String toString(){return selected+"/"+maximum+" ("+source+")";}
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
        public final List<String> diagnostics;
        public final boolean navGuardProven;
        public final float contentTopY;
        public final String navGuardSource;
        public Result(List<Candidate> fruits, List<Candidate> seedlings, List<Candidate> blocked,
                      List<StatusCard> cards, List<OcrItem> ocr, List<String> diagnostics,
                      boolean navGuardProven, float contentTopY, String navGuardSource) {
            this.fruits=fruits; this.seedlings=seedlings; this.blocked=blocked; this.cards=cards; this.ocr=ocr;
            this.diagnostics=diagnostics; this.navGuardProven=navGuardProven; this.contentTopY=contentTopY;
            this.navGuardSource=navGuardSource;
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

    // Reuse one ML Kit recognizer for the lifetime of the process. Creating and
    // closing a recognizer on every screenshot caused multi-second stalls on
    // some phones and made round-to-round timing wildly inconsistent. The Pilot
    // worker is single-threaded, so one shared recognizer is safe here.
    private static final TextRecognizer OCR_RECOGNIZER =
            TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());

    public static List<OcrItem> recognize(Bitmap bitmap) throws Exception {
        Text result=Tasks.await(OCR_RECOGNIZER.process(InputImage.fromBitmap(bitmap,0)), 8, TimeUnit.SECONDS);
        List<OcrItem> out=new ArrayList<>();
        for(Text.TextBlock block:result.getTextBlocks()) {
            for(Text.Line line:block.getLines()) {
                Rect r=line.getBoundingBox();
                if(r!=null && line.getText()!=null && !line.getText().trim().isEmpty())
                    out.add(new OcrItem(line.getText(),new RectF(r)));
            }
        }
        return out;
    }

    public static Result scan(Bitmap b) throws Exception {
        int w=b.getWidth(), h=b.getHeight();
        List<OcrItem> ocr=recognize(b);
        NavGuard navGuard=detectExpeditionNavGuard(ocr,w,h);
        List<Band> rawStatusBands=horizontalBands(b);
        List<Band> tolerantStatusBands=horizontalBandsTolerant(b);
        List<StatusCard> cards=detectStatusCards(b,rawStatusBands);
        // Android screenshots can shift the very pale BUSY/COMPLETE border by a
        // few RGB values depending on device colour management. Merge a second,
        // slightly tolerant border pass, but only when it reconstructs a real
        // paired/partial card shape. This is safer than simply lowering every
        // pixel threshold and avoids both duplicate dispatches and broad false blocks.
        mergeStatusCards(cards,detectStatusCardsTolerant(b,tolerantStatusBands));
        // A carried item can be clipped by the top navigation sheet. In that case
        // the top border is off-screen, so a full paired-card detector cannot fire.
        // Recover ONLY top-clipped cards by requiring a tolerant bottom border plus
        // real vertical side rails from the dynamic contentTopY down to that border.
        // The recovered rectangle stops at the border, so nearby AVAILABLE rows
        // below it are never blocked merely for sharing the same column.
        List<StatusCard> topClippedCards=detectTopClippedStatusCardsTolerant(b,navGuard.contentTopY,tolerantStatusBands);
        mergeStatusCards(cards,topClippedCards);
        // Symmetric protection for the bottom edge. A BUSY/COMPLETE card can
        // enter from below with only its TOP border visible. Without this guard,
        // the fruit artwork inside that clipped card can look AVAILABLE.
        List<StatusCard> bottomClippedCards=detectBottomClippedStatusCardsTolerant(b,navGuard.contentTopY,tolerantStatusBands);
        mergeStatusCards(cards,bottomClippedCards);
        List<String> diagnostics=new ArrayList<>();
        diagnostics.add((navGuard.proven?"NAV-GUARD[OK] ":"NAV-GUARD[MISS] ")+
                "source="+navGuard.source+" contentTopY="+Math.round(navGuard.contentTopY)+
                " ("+String.format(java.util.Locale.US,"%.3f",navGuard.contentTopY/Math.max(1f,h))+"H)");
        if(!topClippedCards.isEmpty()||!bottomClippedCards.isEmpty())
            diagnostics.add("STATUS-GUARD[CLIPPED] top="+topClippedCards.size()+" bottom="+bottomClippedCards.size());
        // rawStatusBands was computed once above and is reused both for card
        // reconstruction and local candidate blocking. Avoiding a second full
        // image border pass materially shortens every list scan.
        List<Candidate> fruit=new ArrayList<>(), seed=new ArrayList<>(), blocked=new ArrayList<>();

        boolean[] mask=new boolean[w*h];
        int startY=(int)Math.max(h*0.16f,navGuard.contentTopY), endY=(int)(h*0.96);
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
            if(center.y<navGuard.contentTopY) {
                diagnostics.add("SKIP[NAV_GUARD] object @("+Math.round(center.x)+","+Math.round(center.y)+")");
                continue;
            }
            if(insideAnyCard(center,cards)) {
                diagnostics.add("SKIP[STATUS_CARD] object @("+Math.round(center.x)+","+Math.round(center.y)+")");
                continue;
            }
            int col=Math.min(2,Math.max(0,(int)(center.x/colWidth)));
            // Candidate-local lower-edge safety net.  Some phones crop the
            // bottom of a BUSY card under the navigation/overlay area so the
            // global card reconstruction can miss the card even though its
            // pastel top border and descending side rail are visible.  Only
            // reject when the status border is ABOVE this exact candidate and
            // the rail evidence continues downward toward the candidate.  This
            // directional test avoids the old bug where a BUSY card in the row
            // above blocked a different AVAILABLE item below it.
            if(hasBottomClippedStatusEvidence(b,tolerantStatusBands,col,center,navGuard.contentTopY)) {
                diagnostics.add("SKIP[STATUS_CLIPPED_LOWER] object @("+Math.round(center.x)+","+Math.round(center.y)+") col="+col);
                continue;
            }
            double expected=(col+0.5)*colWidth;
            if(Math.abs(center.x-expected)>colWidth*0.30) continue;

            String label=nearbyLabel(c.rect,w,h,ocr);
            // iOS FruitDetector is card-first: any BUSY/COMPLETE/partial-card
            // evidence wins over object/OCR classification.  Keep the paired-card
            // test above, and also reject a candidate when a raw status border in
            // the same column sits inside this item's vertical envelope.  This is
            // deliberately conservative for already-carried seedlings.
            // Match the iOS card-first rule exactly: only a reconstructed
            // BUSY/COMPLETE/partial card can block this item. A lone horizontal
            // border in the same column is not enough because it can belong to
            // the carried card directly above/below a different valid seedling.
            boolean seedlingLabel=isSeedlingLabel(label);
            boolean excludedNonFruit=isExcludedNonFruitLabel(label);
            boolean knownFruit=isKnownFruitLabel(label);
            boolean lemonRepair=isLemonOcrAlias(label);
            boolean hasText=label!=null&&!normalize(label).isEmpty();

            // Exclusion-first fruit classification.  Expedition cargo in this
            // 3-column section is overwhelmingly fruit / seedling / gift.  The
            // old positive fruit dictionary made Android depend on exact OCR
            // spelling (檸檬→檸樣, 青蘋果→責蘋果, ...).  Preserve seedling
            // handling, explicitly reject gift/seedling text, and treat every
            // other non-empty nearby label as fruit.
            Kind kind;
            if(seedlingLabel) kind=Kind.SEEDLING;
            else if(excludedNonFruit || !hasText) kind=Kind.UNKNOWN;
            else kind=Kind.FRUIT;

            Candidate candidate=new Candidate(center,c.rect,kind,label);
            if(kind==Kind.FRUIT) {
                fruit.add(candidate);
                String source=knownFruit?(lemonRepair?"LEMON_OCR_REPAIR":"KNOWN_TEXT"):"BY_EXCLUSION";
                diagnostics.add("ACCEPT[FRUIT:"+source+"] "+label+
                        " @("+Math.round(center.x)+","+Math.round(center.y)+")");
            }
            else if(kind==Kind.SEEDLING) {
                seed.add(candidate);
                diagnostics.add("ACCEPT[SEEDLING] "+label+" @("+Math.round(center.x)+","+Math.round(center.y)+")");
            }
            else {
                blocked.add(candidate);
                if(hasText) {
                    String reason=excludedNonFruit?"NON_FRUIT_TEXT":"UNKNOWN_LABEL";
                    diagnostics.add("SKIP["+reason+"] "+label+" @("+Math.round(center.x)+","+Math.round(center.y)+")");
                }
            }
        }

        // OCR-first seedling fallback. In addition to direct one-line labels, Android OCR
        // sometimes splits 冰藍花苗 into "冰藍" + "花苗", or 大花苗 into
        // "大" + "花苗". Re-join nearby OCR lines within the same grid column.
        // This keeps plain top-level 花苗 rejected while recovering the two special labels.
        for(OcrItem item:seedlingLabelItems(ocr,w,h,navGuard.contentTopY)) {
            int col=Math.min(2,Math.max(0,(int)(item.rect.centerX()/colWidth)));
            float cx=(float)((col+0.5)*colWidth);
            float cy=(float)Math.max(navGuard.contentTopY+h*0.020f, item.rect.top-h*0.070);
            PointF center=new PointF(cx,cy);
            if(insideAnyCardOrLabel(center,item.rect,cards)) {
                diagnostics.add("SKIP[SEEDLING_STATUS_CARD] "+item.text+" col="+col+" y="+Math.round(center.y));
                continue;
            }
            // Do not apply a broad same-column status-band exclusion here.
            // iOS only blocks OCR seedlings when the inferred centre/label
            // overlaps a reconstructed status card.
            RectF rect=new RectF((float)(cx-w*0.060),(float)(cy-h*0.045),(float)(cx+w*0.060),(float)(cy+h*0.045));
            Candidate candidate=new Candidate(center,rect,Kind.SEEDLING,item.text);
            if(!containsNear(seed,candidate,w*0.10,h*0.08)) {
                seed.add(candidate);
                diagnostics.add("ACCEPT[SEEDLING_OCR] "+item.text+" @("+Math.round(center.x)+","+Math.round(center.y)+")");
            }
        }

        return new Result(dedup(fruit,w,h),dedup(seed,w,h),dedup(blocked,w,h),cards,ocr,diagnostics,
                navGuard.proven,navGuard.contentTopY,navGuard.source);
    }

    public static boolean isSeedlingLabel(String text) {
        String n=normalizeSeedlingOcr(text);
        if(n.equals("花苗")) return false;

        // A real expedition seedling label contains exactly one 花苗 token.
        // If Android OCR glues the top navigation tab "花苗" to a cargo label
        // below it, the merged string contains two 花苗 tokens. Reject that
        // outright instead of ever turning a navigation control into cargo.
        if(countOccurrences(n,"花苗")!=1) return false;

        // Normal color-qualified seedlings, plus the two real labels that do not
        // contain 色花苗. normalizeSeedlingOcr() absorbs common Android OCR
        // variants such as 冰蓝 / 冰籃 and 花苖.
        return n.contains("色花苗") || n.contains("冰藍花苗") || n.contains("大花苗");
    }

    private static int countOccurrences(String s,String token) {
        if(s==null||token==null||token.isEmpty()) return 0;
        int count=0,from=0;
        while((from=s.indexOf(token,from))>=0){count++;from+=token.length();}
        return count;
    }

    /**
     * Returns OCR items that safely identify expedition seedlings.
     * Direct labels are accepted first. Then nearby OCR lines in the same 3-column
     * cell are joined, which catches e.g. "冰藍" + "花苗" and "大" + "花苗".
     * The navigation label 花苗 is above the expedition content region and remains
     * rejected even if it appears by itself.
     */
    private static List<OcrItem> seedlingLabelItems(List<OcrItem> ocr,int w,int h,float contentTopY) {
        List<OcrItem> out=new ArrayList<>();
        double colWidth=w/3.0;

        for(OcrItem item:ocr) {
            if(item.rect.centerY()<contentTopY) continue;
            if(isSeedlingLabel(item.text)) addOcrUnique(out,item,w,h);
        }

        for(int col=0;col<3;col++) {
            final int targetCol=col;
            List<OcrItem> local=new ArrayList<>();
            for(OcrItem item:ocr) {
                if(item.rect.centerY()<contentTopY) continue;
                int itemCol=Math.min(2,Math.max(0,(int)(item.rect.centerX()/colWidth)));
                if(itemCol==targetCol) local.add(item);
            }
            local.sort(Comparator.comparingDouble(i->i.rect.top));

            for(int i=0;i<local.size();i++) {
                RectF union=new RectF(local.get(i).rect);
                StringBuilder joined=new StringBuilder(local.get(i).text);
                float lastBottom=local.get(i).rect.bottom;

                // Up to three adjacent OCR lines/tokens is enough for the item label
                // while avoiding broad joins across unrelated expedition rows.
                for(int j=i+1;j<local.size() && j<=i+2;j++) {
                    OcrItem next=local.get(j);
                    float gap=next.rect.top-lastBottom;
                    if(gap>h*0.040f) break;
                    if(Math.abs(next.rect.centerX()-union.centerX())>colWidth*0.38) break;

                    joined.append(next.text);
                    union.union(next.rect);
                    lastBottom=Math.max(lastBottom,next.rect.bottom);

                    String candidate=joined.toString();
                    if(isSeedlingLabel(candidate))
                        addOcrUnique(out,new OcrItem(candidate,new RectF(union)),w,h);
                }
            }
        }
        return out;
    }

    private static void addOcrUnique(List<OcrItem> out,OcrItem item,int w,int h) {
        String normalized=normalizeSeedlingOcr(item.text);
        for(OcrItem old:out) {
            if(normalizeSeedlingOcr(old.text).equals(normalized) &&
                    Math.abs(old.rect.centerX()-item.rect.centerX())<w*0.08f &&
                    Math.abs(old.rect.centerY()-item.rect.centerY())<h*0.05f) return;
        }
        out.add(item);
    }

    private static final class NavGuard {
        final boolean proven; final float contentTopY; final String source;
        NavGuard(boolean proven,float contentTopY,String source){this.proven=proven;this.contentTopY=contentTopY;this.source=source;}
    }

    /**
     * Device-independent safety boundary for the Expedition list.  Different
     * phones place the bottom sheet / tab row at very different Y coordinates,
     * so a fixed 0.16H crop is unsafe.  Anchor to the visible navigation row
     * (記錄 / 皮克敏 / 花苗 / 探險 / 明信片) and never expose cargo above it.
     */
    private static NavGuard detectExpeditionNavGuard(List<OcrItem> items,int w,int h) {
        List<String> tabNames=Arrays.asList("記錄","记录","皮克敏","花苗","探險","探险","明信片");
        OcrItem expedition=null;
        for(OcrItem i:items){
            String t=normalize(i.text);
            if((t.equals("探險")||t.equals("探险")) && i.rect.centerY()>h*0.08f && i.rect.centerY()<h*0.78f){
                expedition=i; break;
            }
        }

        float rowY=-1f; String source="none";
        if(expedition!=null){ rowY=expedition.rect.centerY(); source="expedition-tab"; }
        else {
            // Fallback: find the Y cluster containing the most known navigation labels.
            int best=0; float bestY=-1f;
            for(OcrItem anchor:items){
                String a=normalize(anchor.text); if(!tabNames.contains(a)) continue;
                if(anchor.rect.centerY()<h*0.08f||anchor.rect.centerY()>h*0.78f) continue;
                int count=0;
                for(OcrItem j:items){
                    if(!tabNames.contains(normalize(j.text))) continue;
                    if(Math.abs(j.rect.centerY()-anchor.rect.centerY())<=h*0.035f) count++;
                }
                if(count>best){best=count;bestY=anchor.rect.centerY();}
            }
            if(best>=2){rowY=bestY;source="tab-cluster-"+best;}
        }

        if(rowY<0) return new NavGuard(false,h*0.20f,"unproven");

        float maxBottom=0f; int rowTabs=0;
        for(OcrItem i:items){
            String t=normalize(i.text);
            if(tabNames.contains(t)&&Math.abs(i.rect.centerY()-rowY)<=h*0.040f){
                maxBottom=Math.max(maxBottom,i.rect.bottom); rowTabs++;
            }
        }
        float contentTop=maxBottom+h*0.030f;

        // When the section heading is visible just below the tabs, it is an even
        // stronger lower boundary. Keep cargo below that heading as well.
        for(OcrItem i:items){
            String t=normalize(i.text);
            if((t.contains("花苗和水果")||t.contains("水果和花苗")) && i.rect.centerY()>rowY && i.rect.centerY()<rowY+h*0.22f){
                contentTop=Math.max(contentTop,i.rect.bottom+h*0.018f);
                source += "+section-heading";
            }
        }
        contentTop=Math.min(contentTop,h*0.82f);
        return new NavGuard(rowTabs>=1,contentTop,source);
    }

    private static String normalizeSeedlingOcr(String s) {
        return normalize(s)
                .replace("蓝","藍")
                .replace("籃","藍")
                .replace("苖","苗")
                .replace("氷","冰")
                .replace("丨","")
                .replace("|","");
    }

    public static boolean isKnownFruitLabel(String text) {
        if(isSeedlingLabel(text)) return false;
        if(text==null) return false;
        for(String s:KNOWN_FRUITS) if(text.contains(s)) return true;
        return isLemonOcrAlias(text);
    }

    /**
     * Negative cargo dictionary.  We intentionally do NOT try to enumerate all
     * fruit names here.  Anything with nearby OCR text is treated as fruit after
     * these known non-fruit classes are removed.
     *
     * A single 禮/礼 or 贈/赠 catches 紅色禮品 / 禮物 / 禮盒 / 稀有贈禮 and
     * future wording variants.  花苗 is normalized separately so 花苖 / 冰蓝
     * style Android OCR damage cannot fall through and become fruit.
     */
    private static boolean isExcludedNonFruitLabel(String text) {
        if(text==null) return false;
        String seed=normalizeSeedlingOcr(text);
        String n=normalize(text).toLowerCase(java.util.Locale.ROOT);
        if(seed.contains("花苗")) return true;
        if(n.contains("禮")||n.contains("礼")||n.contains("贈")||n.contains("赠")||n.contains("稀有")) return true;
        if(n.contains("gift")||n.contains("present")) return true;
        // Not expected inside 花苗和水果, but never let navigation/postcard text
        // become a fruit if OCR boxes overlap at the sheet boundary.
        if(n.contains("明信片")||n.contains("postcard")) return true;
        return false;
    }

    /**
     * ML Kit repeatedly reads 檸檬 as 檸樣 or even 樟樣 on some Android
     * devices/fonts.  Both variants were observed in real Pilot logs on the
     * exact fruit slot, e.g. `檸樣:水仙` / `樟樣:水仙`.  Keep this repair very
     * narrow: it is only consulted after the image detector has already found a
     * fruit-shaped component and nearbyLabel() has tied this text to that cell.
     */
    private static boolean isLemonOcrAlias(String text) {
        if(text==null) return false;
        String n=normalize(text)
                .replace("|","").replace("丨","")
                .replace("柠","檸").replace("样","樣");
        return n.contains("檸樣") || n.contains("樟樣") || n.contains("檸様") || n.contains("樟様");
    }

    public static boolean hasExpeditionCta(List<OcrItem> items) {
        return expeditionCtaPoint(items)!=null;
    }

    /**
     * OCR-first CTA locator with tolerance for traditional/simplified Chinese,
     * one-character OCR damage, and ML Kit splitting "前往" / "探險" into
     * neighbouring line boxes.
     */
    public static PointF expeditionCtaPoint(List<OcrItem> items) {
        for(OcrItem i:items) {
            if(isExpeditionCtaText(i.text)) return new PointF(i.rect.centerX(),i.rect.centerY());
        }

        float maxB=maxBottom(items), maxR=1;
        for(OcrItem i:items) maxR=Math.max(maxR,i.rect.right);
        for(int a=0;a<items.size();a++) for(int b=a+1;b<items.size();b++) {
            OcrItem x=items.get(a), y=items.get(b);
            float dy=Math.abs(x.rect.centerY()-y.rect.centerY());
            if(dy>Math.max(18,maxB*0.035f)) continue;
            float gap=Math.max(0,Math.max(x.rect.left,y.rect.left)-Math.min(x.rect.right,y.rect.right));
            if(gap>Math.max(70,maxR*0.20f)) continue;
            OcrItem left=x.rect.centerX()<=y.rect.centerX()?x:y;
            OcrItem right=left==x?y:x;
            if(isExpeditionCtaText(left.text+right.text)) {
                RectF u=new RectF(left.rect); u.union(right.rect);
                return new PointF(u.centerX(),u.centerY());
            }
        }
        return null;
    }

    private static boolean isExpeditionCtaText(String raw) {
        String t=normalizeCta(raw);
        if(t.contains("前往探險")||t.contains("gotoexpedition")||t.contains("探検へ")||t.contains("探險へ")) return true;
        if(t.contains("前往")&&t.contains("探險")) return true;
        // The Chinese target is only four characters.  Accept one damaged glyph
        // after normalisation, but not two, to keep this safe on detail pages.
        if(t.length()>=3&&t.length()<=6&&editDistance(t,"前往探險")<=1) return true;
        return false;
    }

    private static String normalizeCta(String s) {
        return normalize(s).toLowerCase()
                .replace("险","險")
                .replace("徃","往")
                .replace("探検","探險")
                .replace("探险","探險")
                .replace("丨","")
                .replace("|","");
    }

    private static int editDistance(String a,String b) {
        int[] prev=new int[b.length()+1],cur=new int[b.length()+1];
        for(int j=0;j<=b.length();j++)prev[j]=j;
        for(int i=1;i<=a.length();i++){
            cur[0]=i;
            for(int j=1;j<=b.length();j++){
                int cost=a.charAt(i-1)==b.charAt(j-1)?0:1;
                cur[j]=Math.min(Math.min(cur[j-1]+1,prev[j]+1),prev[j-1]+cost);
            }
            int[] tmp=prev;prev=cur;cur=tmp;
        }
        return prev[b.length()];
    }

    public static boolean hasSelectionHeader(List<OcrItem> items) {
        for(OcrItem i:items) {
            String t=normalize(i.text).toLowerCase();
            if(t.contains("可以選擇最多")||t.contains("可以选择最多")||t.contains("選擇最多")||t.contains("选择最多")||t.contains("selectupto")) return true;
        }
        return false;
    }

    /**
     * Read Pikmin Bloom's live selection counter, e.g. "(6/10)".  This is
     * deliberately independent from the grid detector: a gesture being
     * dispatched does not prove the game accepted the tap.
     */
    public static SelectionObserved selectionObserved(List<OcrItem> items) {
        Pattern p=Pattern.compile("(\\d{1,2})\\s*/\\s*(\\d{1,2})");
        SelectionObserved fallback=null;
        float maxY=Math.max(1f,maxBottom(items));
        for(OcrItem i:items) {
            String raw=i.text==null?"":i.text;
            Matcher m=p.matcher(raw);
            while(m.find()) {
                int selected,max;
                try { selected=Integer.parseInt(m.group(1)); max=Integer.parseInt(m.group(2)); }
                catch(Exception ignored) { continue; }
                if(selected<0||max<1||max>40||selected>max) continue;
                String n=normalize(raw);
                boolean strong=n.contains("可以選擇最多")||n.contains("可以选择最多")||
                        n.contains("最多")||n.contains("皮克敏");
                SelectionObserved v=new SelectionObserved(selected,max,strong?"OCR-HEADER":"OCR-RATIO");
                if(strong) return v;
                if(i.rect.centerY()<=maxY*0.62f) fallback=v;
            }
        }
        return fallback;
    }

    public static Integer selectedPikminCount(List<OcrItem> items) {
        SelectionObserved v=selectionObserved(items);
        return v==null?null:v.selected;
    }

    public static PointF cancelPoint(List<OcrItem> items) {
        float maxY=Math.max(1f,maxBottom(items));
        for(OcrItem i:items){
            String t=normalize(i.text).toLowerCase();
            if(!(t.equals("取消")||t.equals("cancel"))) continue;
            if(i.rect.centerY()<maxY*0.58f) continue;
            if(i.rect.centerX()>maxRight(items)*0.48f) continue;
            return new PointF(i.rect.centerX(),i.rect.centerY());
        }
        return null;
    }

    public static boolean hasBusyToast(List<OcrItem> items){
        for(OcrItem i:items){
            String t=normalize(i.text);
            if(t.contains("似乎很忙")||t.contains("很忙")||t.toLowerCase().contains("busy")) return true;
        }
        return false;
    }

    private static float maxRight(List<OcrItem> items){float m=1;for(OcrItem i:items)m=Math.max(m,i.rect.right);return m;}

    /** OCR fallback for locating the horizontal Pikmin colour-filter strip. */
    public static PointF filterRowHintPoint(List<OcrItem> items) {
        OcrItem automatic=null;
        float bottom=Math.max(1f,maxBottom(items));
        for(OcrItem i:items) {
            String t=normalize(i.text).toLowerCase();
            float ny=i.rect.centerY()/bottom;
            // The real filter controls are in the middle selection strip.  Do not
            // confuse the bottom-right "飾品一覽" button with the short "飾品" chip
            // control; that mistake previously produced absurd row hints near 95% H.
            if(ny<0.24f||ny>0.64f) continue;
            boolean decor=t.equals("飾品")||t.equals("饰品")||t.equals("decor")||
                    t.equals("飾品▼")||t.equals("饰品▼");
            if(decor) return new PointF(i.rect.centerX(),i.rect.centerY());
            if(t.equals("自動")||t.equals("自动")||t.equals("auto")) automatic=i;
        }
        return automatic==null?null:new PointF(automatic.rect.centerX(),automatic.rect.centerY());
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

    /**
     * Conservative Android reinforcement of the iOS card-first rule.  The iOS
     * detector already treats an unpaired BUSY/COMPLETE horizontal border as a
     * possible clipped card.  On some Android screenshots the matching vertical
     * edge is anti-aliased enough that the full partial-card reconstruction can
     * miss.  A raw same-column status border close to the candidate is still
     * sufficient reason NOT to tap it.
     */
    private static boolean hasLocalStatusBorderEvidence(List<Band> bands,int h,int col,PointF center,RectF labelRect){
        float top=Math.min(center.y,labelRect==null?center.y:labelRect.top)-h*0.095f;
        float bottom=Math.max(center.y,labelRect==null?center.y:labelRect.bottom)+h*0.075f;
        for(Band band:bands){
            if(band.col!=col) continue;
            double y=band.center();
            if(y>=top&&y<=bottom) return true;
        }
        return false;
    }

    private static RectF labelRectNearObject(RectF object,int w,int h,List<OcrItem> ocr){
        double colWidth=w/3.0;
        int col=Math.min(2,Math.max(0,(int)(object.centerX()/colWidth)));
        double x0=col*colWidth,x1=(col+1)*colWidth;
        double y0=object.bottom-h*0.004,y1=Math.min(h,object.bottom+h*0.070);
        RectF union=null;
        for(OcrItem i:ocr){
            if(i.rect.centerX()<x0||i.rect.centerX()>x1||i.rect.centerY()<y0||i.rect.centerY()>y1) continue;
            if(union==null) union=new RectF(i.rect); else union.union(i.rect);
        }
        return union==null?new RectF(object):union;
    }

    private static boolean insideAnyCard(PointF p,List<StatusCard> cards){for(StatusCard c:cards){RectF r=new RectF(c.rect);r.inset(-8,-8);if(r.contains(p.x,p.y))return true;}return false;}
    private static boolean insideAnyCardOrLabel(PointF p,RectF label,List<StatusCard> cards){for(StatusCard c:cards){RectF r=new RectF(c.rect);r.inset(-8,-8);if(r.contains(p.x,p.y)||RectF.intersects(r,label))return true;}return false;}
    private static boolean containsNear(List<Candidate> list,Candidate x,double dx,double dy){for(Candidate a:list)if(Math.abs(a.center.x-x.center.x)<dx&&Math.abs(a.center.y-x.center.y)<dy)return true;return false;}
    private static List<Candidate> dedup(List<Candidate> in,int w,int h){in.sort(CANDIDATE_ORDER);List<Candidate> o=new ArrayList<>();for(Candidate x:in)if(!containsNear(o,x,w*0.045,h*0.035))o.add(x);return o;}

    // --- status-card detection ported from FruitDetector.swift ---
    private static final class Band { final CardState state; final int col,y0,y1; Band(CardState s,int c,int a,int b){state=s;col=c;y0=a;y1=b;} double center(){return(y0+y1)/2.0;} }

    private static void mergeStatusCards(List<StatusCard> base,List<StatusCard> extra){
        for(StatusCard c:extra){
            boolean duplicate=false;
            for(StatusCard old:base){
                RectF inter=new RectF();
                if(inter.setIntersect(old.rect,c.rect)){
                    double smaller=Math.min(old.rect.width()*old.rect.height(),c.rect.width()*c.rect.height());
                    if(smaller>0&&inter.width()*inter.height()/smaller>0.72){duplicate=true;break;}
                }
            }
            if(!duplicate)base.add(c);
        }
        base.sort(Comparator.comparingDouble(c->c.rect.top));
    }

    private static List<StatusCard> detectTopClippedStatusCardsTolerant(Bitmap b,float contentTopY,List<Band> bands){
        int w=b.getWidth(),h=b.getHeight();
        List<StatusCard> out=new ArrayList<>();
        float maxBottom=contentTopY+h*0.22f;
        for(Band band:bands){
            if(band.center()<contentTopY+h*0.025f||band.center()>maxBottom) continue;
            int y1=(int)band.center();
            int y0=Math.max((int)contentTopY,y1-(int)(h*0.18f));
            int sideRows=verticalRowsTolerant(b,band.state,band.col,y0,y1-2);
            int need=Math.max(10,(int)(h*0.022f));
            int bandThickness=Math.max(1,band.y1-band.y0+1);
            // A strong horizontal status border near the clipped top edge is
            // sufficient by itself because the recovered block ends AT that
            // border; it cannot suppress the available row underneath. Side
            // rails remain stronger evidence when visible, but overlays/notches
            // can hide one rail on real phones.
            if(sideRows>=need || bandThickness>=2){
                out.add(new StatusCard(CardState.BLOCKED,cardRect(band.col,contentTopY,band.y1,w)));
            }
        }
        return out;
    }

    /**
     * Recover BUSY/COMPLETE cards clipped by the BOTTOM of the screenshot.
     *
     * Real list screenshots often show only the top pastel border and one long
     * side rail of an in-transit card at the bottom edge. Requiring a full
     * top+bottom pair therefore misses the fruit inside that card. We only
     * activate this guard in the lower part of the screen and require the top
     * border plus rails that continue toward the physical bottom edge.
     */
    private static List<StatusCard> detectBottomClippedStatusCardsTolerant(Bitmap b,float contentTopY,List<Band> bands){
        int w=b.getWidth(),h=b.getHeight();
        List<StatusCard> out=new ArrayList<>();
        float minTop=Math.max(contentTopY+h*0.18f,h*0.68f);
        for(Band band:bands){
            float cy=(float)band.center();
            if(cy<minTop||cy>h*0.965f) continue;

            int railStart=Math.min(h-2,band.y1+2);
            int[] rails=verticalRailRowsTolerant(b,band.state,band.col,railStart,h-2);
            int deepStart=Math.max(railStart,(int)(h*0.90f));
            int[] deep=verticalRailRowsTolerant(b,band.state,band.col,deepStart,h-2);

            int longRail=Math.max(18,(int)(h*0.045f));
            int bothRails=Math.max(8,(int)(h*0.016f));
            int deepNeed=Math.max(3,(int)(h*0.004f));

            boolean oneLongToEdge=Math.max(rails[0],rails[1])>=longRail &&
                    Math.max(deep[0],deep[1])>=deepNeed;
            boolean bothVisible=Math.min(rails[0],rails[1])>=bothRails &&
                    Math.max(deep[0],deep[1])>=deepNeed;

            if(oneLongToEdge||bothVisible){
                out.add(new StatusCard(CardState.BLOCKED,cardRect(band.col,band.y0,h,w)));
            }
        }
        return out;
    }

    /**
     * Local proof that a candidate belongs to a BUSY/COMPLETE card whose lower
     * half is clipped by the screenshot.  A status band alone is NOT enough:
     * the side rail must continue below that band toward this candidate, and
     * downward rail evidence must be stronger than the rail above the band.
     * This keeps the guard local to the clipped card instead of poisoning the
     * whole column.
     */
    private static boolean hasBottomClippedStatusEvidence(Bitmap b,List<Band> bands,int col,PointF center,float contentTopY){
        int h=b.getHeight();
        if(center.y<Math.max(contentTopY+h*0.20f,h*0.72f)) return false;
        for(Band band:bands){
            if(band.col!=col) continue;
            float by=(float)band.center();
            if(by>=center.y) continue;
            float delta=center.y-by;
            if(delta<h*0.018f||delta>h*0.185f) continue;

            // If there is a normal matching bottom border below this band, this
            // is a full card and the ordinary card detector owns it.
            boolean paired=false;
            for(Band other:bands){
                if(other==band||other.col!=col||other.state!=band.state) continue;
                float d=(float)(other.center()-band.center());
                if(d>=h*0.095f&&d<=h*0.185f){ paired=true; break; }
            }
            if(paired) continue;

            int down0=Math.min(h-2,band.y1+2);
            int down1=Math.min(h-2,(int)Math.max(center.y+h*0.035f,by+h*0.075f));
            int up1=Math.max(1,band.y0-2);
            int up0=Math.max(0,up1-(int)(h*0.090f));
            if(down1<=down0) continue;
            int[] down=verticalRailRowsTolerant(b,band.state,col,down0,down1);
            int[] up=verticalRailRowsTolerant(b,band.state,col,up0,up1);

            int downStrong=Math.max(down[0],down[1]);
            int upStrong=Math.max(up[0],up[1]);
            int downNeed=Math.max(10,(int)((down1-down0)*0.16f));

            int near0=Math.max(down0,(int)(center.y-h*0.050f));
            int near1=Math.min(h-2,(int)(center.y+h*0.045f));
            int[] near=verticalRailRowsTolerant(b,band.state,col,near0,near1);
            int nearStrong=Math.max(near[0],near[1]);

            boolean descendsTowardCandidate=downStrong>=downNeed && nearStrong>=Math.max(2,(int)(h*0.0025f));
            boolean directional=downStrong>=upStrong+Math.max(3,(int)(h*0.004f)) || by>=h*0.82f;
            if(descendsTowardCandidate&&directional) return true;
        }
        return false;
    }

    /** Return {leftRailRows,rightRailRows} for tolerant BUSY/COMPLETE side rails. */
    private static int[] verticalRailRowsTolerant(Bitmap b,CardState state,int col,int y0,int y1){
        if(y1<=y0)return new int[]{0,0};
        int w=b.getWidth(),h=b.getHeight();double cw=w/3.0;
        double left=col*cw+w*0.030,right=(col+1)*cw-w*0.030;
        int strip=Math.max(3,(int)(w*0.010));
        int lx0=Math.max(0,(int)left-strip),lx1=Math.min(w-1,(int)left+strip);
        int rx0=Math.max(0,(int)right-strip),rx1=Math.min(w-1,(int)right+strip);
        int yy0=Math.max(0,y0),yy1=Math.min(h-1,y1),leftRows=0,rightRows=0;
        for(int y=yy0;y<=yy1;y++){
            int leftHits=0,rightHits=0;
            for(int x=lx0;x<=lx1;x++) if(tolerantBorder(state,b.getPixel(x,y))) leftHits++;
            for(int x=rx0;x<=rx1;x++) if(tolerantBorder(state,b.getPixel(x,y))) rightHits++;
            if(leftHits>=1) leftRows++;
            if(rightHits>=1) rightRows++;
        }
        return new int[]{leftRows,rightRows};
    }

    private static int verticalRowsTolerant(Bitmap b,CardState state,int col,int y0,int y1){
        if(y1<=y0)return 0;
        int w=b.getWidth(),h=b.getHeight();double cw=w/3.0;
        double left=col*cw+w*0.030,right=(col+1)*cw-w*0.030;
        int strip=Math.max(3,(int)(w*0.010));
        int lx0=Math.max(0,(int)left-strip),lx1=Math.min(w-1,(int)left+strip);
        int rx0=Math.max(0,(int)right-strip),rx1=Math.min(w-1,(int)right+strip);
        int yy0=Math.max(0,y0),yy1=Math.min(h-1,y1),rows=0;
        for(int y=yy0;y<=yy1;y++){
            int leftHits=0,rightHits=0;
            for(int x=lx0;x<=lx1;x++) if(tolerantBorder(state,b.getPixel(x,y))) leftHits++;
            for(int x=rx0;x<=rx1;x++) if(tolerantBorder(state,b.getPixel(x,y))) rightHits++;
            if(leftHits>=1&&rightHits>=1) rows++;
        }
        return rows;
    }

    private static boolean tolerantBorder(CardState state,int p){
        return state==CardState.BUSY?isBusyTolerant(p):isCompleteTolerant(p);
    }

    /**
     * Device-colour tolerant backup for BUSY/COMPLETE borders. The original iOS
     * RGB thresholds remain the primary detector. This backup is deliberately
     * allowed to block only when TWO long pastel borders reconstruct a plausible
     * card height, which keeps the looser colour range from blocking ordinary
     * expedition artwork.
     */
    private static List<StatusCard> detectStatusCardsTolerant(Bitmap b,List<Band> bands){
        int w=b.getWidth(),h=b.getHeight();
        List<StatusCard> cards=new ArrayList<>();
        for(CardState state:new CardState[]{CardState.BUSY,CardState.COMPLETE}) for(int col=0;col<3;col++){
            List<Band> local=new ArrayList<>();
            for(Band x:bands)if(x.state==state&&x.col==col)local.add(x);
            local.sort(Comparator.comparingDouble(Band::center));
            for(int i=0;i<local.size();i++){
                for(int j=i+1;j<local.size();j++){
                    double d=local.get(j).center()-local.get(i).center();
                    if(d>h*0.180)break;
                    if(d>=h*0.100&&d<=h*0.180){
                        cards.add(new StatusCard(CardState.BLOCKED,cardRect(col,local.get(i).y0,local.get(j).y1,w)));
                        break;
                    }
                }
            }
        }
        return cards;
    }

    private static List<Band> horizontalBandsTolerant(Bitmap b){
        int w=b.getWidth(),h=b.getHeight();double cw=w/3.0;List<Object[]> hits=new ArrayList<>();
        for(int y=(int)(h*0.16);y<(int)(h*0.98);y++)for(int col=0;col<3;col++){
            int x0=Math.max(0,(int)(col*cw+w*0.030)),x1=Math.min(w-1,(int)((col+1)*cw-w*0.030));
            if(x1<=x0)continue;int total=x1-x0+1,busy=0,complete=0;
            for(int x=x0;x<=x1;x++){int p=b.getPixel(x,y);if(isBusyTolerant(p))busy++;if(isCompleteTolerant(p))complete++;}
            if((double)busy/total>=0.42)hits.add(new Object[]{CardState.BUSY,col,y});
            if((double)complete/total>=0.42)hits.add(new Object[]{CardState.COMPLETE,col,y});
        }
        List<Band> out=new ArrayList<>();
        for(CardState state:new CardState[]{CardState.BUSY,CardState.COMPLETE})for(int col=0;col<3;col++){
            List<Integer> ys=new ArrayList<>();for(Object[] z:hits)if(z[0]==state&&((Integer)z[1])==col)ys.add(((Integer)z[2]));
            ys.sort(Integer::compareTo);if(ys.isEmpty())continue;int start=ys.get(0),prev=start;
            for(int k=1;k<ys.size();k++){int y=ys.get(k);if(y<=prev+1)prev=y;else{out.add(new Band(state,col,start,prev));start=prev=y;}}
            out.add(new Band(state,col,start,prev));
        }
        return out;
    }

    private static boolean isBusyTolerant(int p){
        int r=(p>>16)&255,g=(p>>8)&255,b=p&255,mx=Math.max(r,Math.max(g,b)),mn=Math.min(r,Math.min(g,b));
        return r>=218&&g>=205&&b>=205&&r>=g+2&&r>=b+1&&mx-mn<=52;
    }
    private static boolean isCompleteTolerant(int p){
        int r=(p>>16)&255,g=(p>>8)&255,b=p&255,mx=Math.max(r,Math.max(g,b)),mn=Math.min(r,Math.min(g,b));
        return g>=212&&r>=192&&b>=192&&g>=r+2&&g>=b+1&&mx-mn<=58;
    }

    private static List<StatusCard> detectStatusCards(Bitmap b,List<Band> bands) {
        int w=b.getWidth(),h=b.getHeight(); List<StatusCard> cards=new ArrayList<>();
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
