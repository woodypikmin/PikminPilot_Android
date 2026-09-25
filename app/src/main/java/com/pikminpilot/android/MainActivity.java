package com.pikminpilot.android;

import android.app.Activity;
import android.content.Intent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.pikminpilot.android.automation.PilotAccessibilityService;
import com.pikminpilot.android.automation.PilotController;
import com.pikminpilot.android.model.PilotConfig;

public class MainActivity extends Activity implements PilotController.Listener {
    private TextView serviceStatus,runStatus,logView,speedBadge,cargoHint,speedHint,countTitle,countHint,pikminCountView;
    private TextView summaryRun,summaryCargo,summaryPikmin;
    private EditText runCountInput;
    private Spinner fallback1Type,fallback2Type,fallback3Type,fallback1Count,fallback2Count,fallback3Count;
    private CheckBox fallback1Enabled,fallback2Enabled,fallback3Enabled;
    private CheckBox fruitAll,fruitGreen,fruitYellow,fruitRed,fruitBlue;
    private Button cargoFruit,cargoSeedling,cargoBoth,typePink,typeWhite,typePurple,typeRock,speedStable,speedFast;
    private final StringBuilder log=new StringBuilder();
    private PilotConfig.CargoMode cargoMode=PilotConfig.CargoMode.FRUIT;
    private PilotConfig.PikminType pikminType=PilotConfig.PikminType.PINK;
    private boolean fast=false;
    private int pikminCount=12;
    private SharedPreferences prefs;

    @Override protected void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main); prefs=getSharedPreferences("pilot",MODE_PRIVATE);
        serviceStatus=findViewById(R.id.serviceStatus); runStatus=findViewById(R.id.runStatus); logView=findViewById(R.id.logView);
        speedBadge=findViewById(R.id.speedBadge); cargoHint=findViewById(R.id.cargoHint); speedHint=findViewById(R.id.speedHint);
        countTitle=findViewById(R.id.countTitle); countHint=findViewById(R.id.countHint); pikminCountView=findViewById(R.id.pikminCount);
        summaryRun=findViewById(R.id.summaryRun);summaryCargo=findViewById(R.id.summaryCargo);summaryPikmin=findViewById(R.id.summaryPikmin);
        runCountInput=findViewById(R.id.runCountInput);
        fallback1Enabled=findViewById(R.id.fallback1Enabled); fallback2Enabled=findViewById(R.id.fallback2Enabled); fallback3Enabled=findViewById(R.id.fallback3Enabled);
        fruitAll=findViewById(R.id.fruitAll); fruitGreen=findViewById(R.id.fruitGreen); fruitYellow=findViewById(R.id.fruitYellow);
        fruitRed=findViewById(R.id.fruitRed); fruitBlue=findViewById(R.id.fruitBlue);
        fallback1Type=findViewById(R.id.fallback1Type); fallback2Type=findViewById(R.id.fallback2Type); fallback3Type=findViewById(R.id.fallback3Type);
        fallback1Count=findViewById(R.id.fallback1Count); fallback2Count=findViewById(R.id.fallback2Count); fallback3Count=findViewById(R.id.fallback3Count);
        cargoFruit=findViewById(R.id.cargoFruit);cargoSeedling=findViewById(R.id.cargoSeedling);cargoBoth=findViewById(R.id.cargoBoth);
        typePink=findViewById(R.id.typePink);typeWhite=findViewById(R.id.typeWhite);typePurple=findViewById(R.id.typePurple);typeRock=findViewById(R.id.typeRock);
        speedStable=findViewById(R.id.speedStable);speedFast=findViewById(R.id.speedFast);

        // Free-form run count: user can enter any non-negative integer.
        // 0 means infinite. Migrate the old fixed Spinner once if needed.
        int initialRuns;
        if(prefs.contains("runCount")) initialRuns=Math.max(0,prefs.getInt("runCount",5));
        else {
            int oldMode=Math.max(0,Math.min(4,prefs.getInt("runMode",1)));
            initialRuns=new int[]{1,5,10,20,0}[oldMode];
            prefs.edit().putInt("runCount",initialRuns).apply();
        }
        runCountInput.setText(String.valueOf(initialRuns));
        runCountInput.addTextChangedListener(new android.text.TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){
                Integer v=parseRunCount(false);
                if(v!=null){prefs.edit().putInt("runCount",v).apply();refreshUi();}
            }
            public void afterTextChanged(android.text.Editable e){}
        });

        try{cargoMode=PilotConfig.CargoMode.valueOf(prefs.getString("cargo","FRUIT"));}catch(Exception ignored){}
        try{pikminType=PilotConfig.PikminType.valueOf(prefs.getString("type","PINK"));}catch(Exception ignored){}
        fast=prefs.getBoolean("fast",false); pikminCount=prefs.getInt("count",12);
        enforceMinimum();
        setupFallbackUi();
        setupFruitFilterUi();

        cargoFruit.setOnClickListener(v->selectCargo(PilotConfig.CargoMode.FRUIT));
        cargoSeedling.setOnClickListener(v->selectCargo(PilotConfig.CargoMode.SEEDLING));
        cargoBoth.setOnClickListener(v->selectCargo(PilotConfig.CargoMode.BOTH));
        typePink.setOnClickListener(v->selectType(PilotConfig.PikminType.PINK));
        typeWhite.setOnClickListener(v->selectType(PilotConfig.PikminType.WHITE));
        typePurple.setOnClickListener(v->selectType(PilotConfig.PikminType.PURPLE));
        typeRock.setOnClickListener(v->selectType(PilotConfig.PikminType.ROCK));
        speedStable.setOnClickListener(v->selectSpeed(false)); speedFast.setOnClickListener(v->selectSpeed(true));
        findViewById(R.id.countMinus).setOnClickListener(v->{pikminCount=Math.max(PilotConfig.minimumCount(pikminType),pikminCount-1);save();refreshUi();});
        findViewById(R.id.countPlus).setOnClickListener(v->{pikminCount=Math.min(12,pikminCount+1);save();refreshUi();});
        findViewById(R.id.openAccessibility).setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.openPikmin).setOnClickListener(v->openPikmin());
        findViewById(R.id.startPilot).setOnClickListener(v->startPilot());
        findViewById(R.id.stopPilot).setOnClickListener(v->PilotController.get().stop());
        findViewById(R.id.testScreenshot).setOnClickListener(v->PilotController.get().testScreenshot(this::appendLog));
        findViewById(R.id.copyLog).setOnClickListener(v->copyLog());
        findViewById(R.id.clearLog).setOnClickListener(v->{log.setLength(0);logView.setText("");appendLog("BUILD 0.4.8-alpha35 • log cleared");});
        PilotController.get().setListener(this); refreshService(); refreshUi();
        appendLog("BUILD 0.4.8-alpha35 • persistent loading GO-watch • fruit color filter • single-tap filter • hard GO lock • second-frame BUSY veto • free run-count • 24% swipe • 3000ms settle");
    }

    @Override protected void onResume(){super.onResume();PilotController.get().setListener(this);refreshService();}
    @Override protected void onPause(){
        // Keep the listener attached while Pikmin Bloom is in the foreground.
        // The Activity still exists, so status/log lines continue to accumulate and
        // are visible immediately when the user returns to Pilot.
        super.onPause();
    }
    @Override protected void onDestroy(){
        PilotController.get().setListener(null);
        super.onDestroy();
    }

    private void selectCargo(PilotConfig.CargoMode x){cargoMode=x;save();refreshUi();}
    private void selectType(PilotConfig.PikminType x){pikminType=x;enforceMinimum();save();refreshUi();}
    private void selectSpeed(boolean x){fast=x;save();refreshUi();}
    private void enforceMinimum(){pikminCount=Math.max(PilotConfig.minimumCount(pikminType),Math.min(12,pikminCount));}
    private void save(){
        SharedPreferences.Editor e=prefs.edit().putString("cargo",cargoMode.name()).putString("type",pikminType.name()).putBoolean("fast",fast).putInt("count",pikminCount);
        if(fruitAll!=null){
            e.putBoolean("fruitAll",fruitAll.isChecked())
                    .putBoolean("fruitGreen",fruitGreen.isChecked())
                    .putBoolean("fruitYellow",fruitYellow.isChecked())
                    .putBoolean("fruitRed",fruitRed.isChecked())
                    .putBoolean("fruitBlue",fruitBlue.isChecked());
        }
        if(fallback1Enabled!=null){
            saveFallback(e,1,fallback1Enabled,fallback1Type,fallback1Count);
            saveFallback(e,2,fallback2Enabled,fallback2Type,fallback2Count);
            saveFallback(e,3,fallback3Enabled,fallback3Type,fallback3Count);
        }
        e.apply();
    }

    private void selected(Button b,boolean on){b.setBackgroundResource(on?R.drawable.button_selected:R.drawable.button_unselected);}
    private void refreshUi(){
        selected(cargoFruit,cargoMode==PilotConfig.CargoMode.FRUIT); selected(cargoSeedling,cargoMode==PilotConfig.CargoMode.SEEDLING); selected(cargoBoth,cargoMode==PilotConfig.CargoMode.BOTH);
        selected(typePink,pikminType==PilotConfig.PikminType.PINK);selected(typeWhite,pikminType==PilotConfig.PikminType.WHITE);selected(typePurple,pikminType==PilotConfig.PikminType.PURPLE);selected(typeRock,pikminType==PilotConfig.PikminType.ROCK);
        selected(speedStable,!fast);selected(speedFast,fast); speedBadge.setText(fast?"FAST":"STABLE"); speedBadge.setTextColor(getColor(fast?R.color.pilot_orange:R.color.pilot_green));
        String cargoText;
        if(cargoMode==PilotConfig.CargoMode.SEEDLING) cargoText="花苗只接受「某色花苗」文字（必須含「色花苗」）；上方單獨的「花苗」分頁永遠不點。";
        else if(cargoMode==PilotConfig.CargoMode.BOTH) cargoText="水果＋花苗模式只接受 OCR 含「色花苗」的盆栽；上方單獨「花苗」分頁永遠排除。";
        else cargoText="水果採排除式 OCR；禮物/花苗排除。可勾選水果顏色群組；全拿時不做顏色過濾。";
        cargoHint.setText(cargoText);
        speedHint.setText(fast?"快速模式只縮短已驗證的等待與選取間隔；辨識重試與安全判定仍保留。":"穩定模式沿用目前已驗證的等待與辨識節奏。");
        int min=PilotConfig.minimumCount(pikminType);
        countTitle.setText("每輪 "+PilotConfig.pikminName(pikminType)+"皮克敏");countHint.setText("設定目標 "+pikminCount+" 隻；若遊戲已亮 GO，會接受較少但可合法出發的隊伍");pikminCountView.setText(pikminCount+" 隻");
        summaryRun.setText(runLabel());summaryCargo.setText(PilotConfig.cargoName(cargoMode));
        int enabledFallbacks=(fallback1Enabled!=null&&fallback1Enabled.isChecked()?1:0)+(fallback2Enabled!=null&&fallback2Enabled.isChecked()?1:0)+(fallback3Enabled!=null&&fallback3Enabled.isChecked()?1:0);
        summaryPikmin.setText(PilotConfig.pikminName(pikminType)+"皮×"+pikminCount+(enabledFallbacks>0?" + F"+enabledFallbacks:""));
    }

    private Integer parseRunCount(boolean toastOnError){
        if(runCountInput==null) return 5;
        String raw=runCountInput.getText()==null?"":runCountInput.getText().toString().trim();
        if(raw.isEmpty()){
            if(toastOnError) Toast.makeText(this,"請輸入搬運次數；0 代表無限",Toast.LENGTH_LONG).show();
            return null;
        }
        try{
            long v=Long.parseLong(raw);
            if(v<0||v>1000000L){
                if(toastOnError) Toast.makeText(this,"搬運次數請輸入 0～1,000,000",Toast.LENGTH_LONG).show();
                return null;
            }
            return (int)v;
        }catch(NumberFormatException e){
            if(toastOnError) Toast.makeText(this,"搬運次數格式不正確",Toast.LENGTH_LONG).show();
            return null;
        }
    }
    private String runLabel(){Integer v=parseRunCount(false);if(v==null)return "請輸入次數";return v==0?"無限循環":v+" 顆";}
    private int runTarget(){Integer v=parseRunCount(false);return v==null?-1:v;}
    private void refreshService(){boolean on=PilotAccessibilityService.get()!=null;serviceStatus.setText(on?"Accessibility ✓":"Accessibility ! 尚未啟用");}
    private void openPikmin(){Intent i=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");if(i==null){Toast.makeText(this,"找不到 Pikmin Bloom (com.nianticlabs.pikmin)",Toast.LENGTH_LONG).show();return;}startActivity(i);}
    private void startPilot(){
        if(PilotAccessibilityService.get()==null){Toast.makeText(this,"請先啟用 Pikmin Pilot Automation 輔助使用服務",Toast.LENGTH_LONG).show();startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}
        Integer requestedRuns=parseRunCount(true);
        if(requestedRuns==null) return;
        prefs.edit().putInt("runCount",requestedRuns).apply();
        java.util.List<PilotConfig.SelectionPlan> fallbacks=new java.util.ArrayList<>();
        fallbacks.add(readFallback(1,fallback1Enabled,fallback1Type,fallback1Count));
        fallbacks.add(readFallback(2,fallback2Enabled,fallback2Type,fallback2Count));
        fallbacks.add(readFallback(3,fallback3Enabled,fallback3Type,fallback3Count));
        java.util.EnumSet<PilotConfig.FruitGroup> fruitGroups=java.util.EnumSet.noneOf(PilotConfig.FruitGroup.class);
        if(fruitGreen.isChecked()) fruitGroups.add(PilotConfig.FruitGroup.GREEN);
        if(fruitYellow.isChecked()) fruitGroups.add(PilotConfig.FruitGroup.YELLOW);
        if(fruitRed.isChecked()) fruitGroups.add(PilotConfig.FruitGroup.RED);
        if(fruitBlue.isChecked()) fruitGroups.add(PilotConfig.FruitGroup.BLUE);
        boolean takeAllFruit=fruitAll.isChecked() || fruitGroups.isEmpty();
        PilotConfig cfg=new PilotConfig(pikminType,cargoMode,pikminCount,requestedRuns,fast,fallbacks,takeAllFruit,fruitGroups);
        appendLog("START • "+runLabel()+" • "+PilotConfig.cargoName(cargoMode)+" • "+PilotConfig.pikminName(pikminType)+"×"+pikminCount+
                " • fruit="+fruitFilterSummary(cfg)+" • fallbacks="+enabledFallbackSummary(cfg));
        openPikmin();
        // User workflow: leave Pikmin Bloom already open on the Expedition list,
        // switch back to Pilot, then press START.  Do not inspect the screen during
        // the Android app-switch animation: give Pikmin Bloom a full 3 seconds to
        // return from background to foreground before the first screenshot.
        new android.os.Handler(getMainLooper()).postDelayed(()->PilotController.get().start(cfg),3000);
    }

    private void setupFruitFilterUi(){
        boolean all=prefs.getBoolean("fruitAll",true);
        boolean g=prefs.getBoolean("fruitGreen",false);
        boolean y=prefs.getBoolean("fruitYellow",false);
        boolean r=prefs.getBoolean("fruitRed",false);
        boolean bl=prefs.getBoolean("fruitBlue",false);
        if(all || (!g&&!y&&!r&&!bl)){ all=true; g=y=r=bl=false; }
        fruitAll.setChecked(all);
        fruitGreen.setChecked(g);
        fruitYellow.setChecked(y);
        fruitRed.setChecked(r);
        fruitBlue.setChecked(bl);
        android.widget.CompoundButton.OnCheckedChangeListener groupListener=(button,isChecked)->{
            if(isChecked){
                fruitAll.setOnCheckedChangeListener(null);
                fruitAll.setChecked(false);
                fruitAll.setOnCheckedChangeListener((b,c)->onFruitAllChanged(c));
            } else if(!fruitGreen.isChecked()&&!fruitYellow.isChecked()&&!fruitRed.isChecked()&&!fruitBlue.isChecked()){
                fruitAll.setOnCheckedChangeListener(null);
                fruitAll.setChecked(true);
                fruitAll.setOnCheckedChangeListener((b,c)->onFruitAllChanged(c));
            }
            save(); refreshUi();
        };
        fruitGreen.setOnCheckedChangeListener(groupListener);
        fruitYellow.setOnCheckedChangeListener(groupListener);
        fruitRed.setOnCheckedChangeListener(groupListener);
        fruitBlue.setOnCheckedChangeListener(groupListener);
        fruitAll.setOnCheckedChangeListener((b,c)->onFruitAllChanged(c));
    }

    private void onFruitAllChanged(boolean checked){
        if(checked){
            fruitGreen.setOnCheckedChangeListener(null); fruitYellow.setOnCheckedChangeListener(null);
            fruitRed.setOnCheckedChangeListener(null); fruitBlue.setOnCheckedChangeListener(null);
            fruitGreen.setChecked(false); fruitYellow.setChecked(false); fruitRed.setChecked(false); fruitBlue.setChecked(false);
            setupFruitGroupListenersOnly();
        } else if(!fruitGreen.isChecked()&&!fruitYellow.isChecked()&&!fruitRed.isChecked()&&!fruitBlue.isChecked()){
            // Never leave the UI in a state that silently rejects every fruit.
            fruitAll.setOnCheckedChangeListener(null); fruitAll.setChecked(true);
            fruitAll.setOnCheckedChangeListener((b,c)->onFruitAllChanged(c));
        }
        save(); refreshUi();
    }

    private void setupFruitGroupListenersOnly(){
        android.widget.CompoundButton.OnCheckedChangeListener l=(button,isChecked)->{
            if(isChecked){
                fruitAll.setOnCheckedChangeListener(null); fruitAll.setChecked(false);
                fruitAll.setOnCheckedChangeListener((b,c)->onFruitAllChanged(c));
            } else if(!fruitGreen.isChecked()&&!fruitYellow.isChecked()&&!fruitRed.isChecked()&&!fruitBlue.isChecked()){
                fruitAll.setOnCheckedChangeListener(null); fruitAll.setChecked(true);
                fruitAll.setOnCheckedChangeListener((b,c)->onFruitAllChanged(c));
            }
            save(); refreshUi();
        };
        fruitGreen.setOnCheckedChangeListener(l); fruitYellow.setOnCheckedChangeListener(l);
        fruitRed.setOnCheckedChangeListener(l); fruitBlue.setOnCheckedChangeListener(l);
    }

    private String fruitFilterSummary(PilotConfig cfg){
        if(cfg.fruitAll) return "全拿";
        StringBuilder b=new StringBuilder();
        for(PilotConfig.FruitGroup g:cfg.fruitGroups){if(b.length()>0)b.append('/');b.append(PilotConfig.fruitGroupName(g));}
        return b.length()==0?"全拿":b.toString();
    }

    // Fallback is intentionally limited to the four special expedition types
    // used by the iOS workflow. Primary remains unchanged.
    private static final PilotConfig.PikminType[] FALLBACK_TYPES={
            PilotConfig.PikminType.ROCK,PilotConfig.PikminType.PURPLE,
            PilotConfig.PikminType.PINK,PilotConfig.PikminType.WHITE};

    private void setupFallbackUi(){
        String[] typeNames={"岩皮","紫皮","粉紅皮","白皮"};
        String[] counts=new String[12]; for(int i=0;i<12;i++)counts[i]=(i+1)+" 隻";
        Spinner[] ts={fallback1Type,fallback2Type,fallback3Type}; Spinner[] cs={fallback1Count,fallback2Count,fallback3Count}; CheckBox[] es={fallback1Enabled,fallback2Enabled,fallback3Enabled};
        for(int i=0;i<3;i++){
            ts[i].setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,typeNames));
            cs[i].setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,counts));
            int defaultType=i==0?0:(i==1?1:2); // rock, purple, pink
            String v2Key="fb"+(i+1)+"TypeV2";
            int savedType;
            if(prefs.contains(v2Key)) savedType=prefs.getInt(v2Key,defaultType);
            else {
                // Migrate alpha20's order: pink, white, purple, rock, red, yellow, blue.
                int oldType=prefs.getInt("fb"+(i+1)+"Type",-1);
                switch(oldType){
                    case 0: savedType=2; break; // pink
                    case 1: savedType=3; break; // white
                    case 2: savedType=1; break; // purple
                    case 3: savedType=0; break; // rock
                    default: savedType=defaultType; break; // red/yellow/blue -> safe default
                }
            }
            if(savedType<0||savedType>=FALLBACK_TYPES.length) savedType=defaultType;
            ts[i].setSelection(savedType);
            cs[i].setSelection(Math.max(0,Math.min(11,prefs.getInt("fb"+(i+1)+"Count",1))));
            es[i].setChecked(prefs.getBoolean("fb"+(i+1)+"Enabled",false));
            final int idx=i;
            android.widget.AdapterView.OnItemSelectedListener l=new android.widget.AdapterView.OnItemSelectedListener(){
                public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){save();refreshUi();}
                public void onNothingSelected(android.widget.AdapterView<?> p){}
            };
            ts[i].setOnItemSelectedListener(l); cs[i].setOnItemSelectedListener(l);
            es[i].setOnCheckedChangeListener((buttonView,isChecked)->{save();refreshUi();});
        }
    }

    private void saveFallback(SharedPreferences.Editor e,int n,CheckBox enabled,Spinner type,Spinner count){
        e.putBoolean("fb"+n+"Enabled",enabled.isChecked())
                .putInt("fb"+n+"TypeV2",type.getSelectedItemPosition())
                .putInt("fb"+n+"Count",count.getSelectedItemPosition());
    }

    private PilotConfig.SelectionPlan readFallback(int n,CheckBox enabled,Spinner type,Spinner count){
        int ti=Math.max(0,Math.min(FALLBACK_TYPES.length-1,type.getSelectedItemPosition()));
        int configured=Math.max(1,Math.min(12,count.getSelectedItemPosition()+1));
        return new PilotConfig.SelectionPlan("FALLBACK-"+n,FALLBACK_TYPES[ti],configured,enabled.isChecked());
    }

    private String enabledFallbackSummary(PilotConfig cfg){
        StringBuilder b=new StringBuilder();
        for(PilotConfig.SelectionPlan p:cfg.selectionPlans){if(!p.name.equals("PRIMARY")&&p.enabled){if(b.length()>0)b.append(",");b.append(p.name).append(':').append(PilotConfig.pikminName(p.type)).append('×').append(p.configuredCount);}}
        return b.length()==0?"none":b.toString();
    }

    private void copyLog(){
        ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
        String text=log.toString();
        cm.setPrimaryClip(ClipData.newPlainText("PikminPilot Log",text));
        Toast.makeText(this,"Log 已複製（"+text.length()+" 字元）",Toast.LENGTH_SHORT).show();
    }

    private void appendLog(String s){if(log.length()>250000)log.delete(0,50000);log.append(s).append('\n');logView.setText(log.toString());}
    @Override public void onStatus(String status){runStatus.setText(status);}
    @Override public void onLog(String line){appendLog(line);}
}
