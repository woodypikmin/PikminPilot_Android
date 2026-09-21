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
import android.widget.TextView;
import android.widget.Toast;

import com.pikminpilot.android.automation.PilotAccessibilityService;
import com.pikminpilot.android.automation.PilotController;
import com.pikminpilot.android.model.PilotConfig;

public class MainActivity extends Activity implements PilotController.Listener {
    private TextView serviceStatus,runStatus,logView,speedBadge,cargoHint,speedHint,countTitle,countHint,pikminCountView;
    private TextView summaryRun,summaryCargo,summaryPikmin;
    private Spinner runMode;
    private Spinner fallback1Type,fallback2Type,fallback3Type,fallback1Count,fallback2Count,fallback3Count;
    private CheckBox fallback1Enabled,fallback2Enabled,fallback3Enabled;
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
        runMode=findViewById(R.id.runMode);
        fallback1Enabled=findViewById(R.id.fallback1Enabled); fallback2Enabled=findViewById(R.id.fallback2Enabled); fallback3Enabled=findViewById(R.id.fallback3Enabled);
        fallback1Type=findViewById(R.id.fallback1Type); fallback2Type=findViewById(R.id.fallback2Type); fallback3Type=findViewById(R.id.fallback3Type);
        fallback1Count=findViewById(R.id.fallback1Count); fallback2Count=findViewById(R.id.fallback2Count); fallback3Count=findViewById(R.id.fallback3Count);
        cargoFruit=findViewById(R.id.cargoFruit);cargoSeedling=findViewById(R.id.cargoSeedling);cargoBoth=findViewById(R.id.cargoBoth);
        typePink=findViewById(R.id.typePink);typeWhite=findViewById(R.id.typeWhite);typePurple=findViewById(R.id.typePurple);typeRock=findViewById(R.id.typeRock);
        speedStable=findViewById(R.id.speedStable);speedFast=findViewById(R.id.speedFast);

        String[] runItems={"1","5","10","20","∞"};
        runMode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,runItems));
        runMode.setSelection(Math.max(0,Math.min(4,prefs.getInt("runMode",1))));
        runMode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener(){
            public void onItemSelected(android.widget.AdapterView<?> p,View v,int pos,long id){prefs.edit().putInt("runMode",pos).apply();refreshUi();}
            public void onNothingSelected(android.widget.AdapterView<?> p){}
        });

        try{cargoMode=PilotConfig.CargoMode.valueOf(prefs.getString("cargo","FRUIT"));}catch(Exception ignored){}
        try{pikminType=PilotConfig.PikminType.valueOf(prefs.getString("type","PINK"));}catch(Exception ignored){}
        fast=prefs.getBoolean("fast",false); pikminCount=prefs.getInt("count",12);
        enforceMinimum();
        setupFallbackUi();

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
        findViewById(R.id.clearLog).setOnClickListener(v->{log.setLength(0);logView.setText("");appendLog("BUILD 0.4.3-alpha25 • log cleared");});
        PilotController.get().setListener(this); refreshService(); refreshUi();
        appendLog("BUILD 0.4.3-alpha25 • bottom BUSY header guard • edge 2-frame AVAILABLE ACK • sticky fallback cursor • 24% swipe • 3000ms settle");
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
        else cargoText="水果採排除式 OCR；禮物/花苗排除。已搬運卡片仍以 card-first BUSY/COMPLETE 判定。";
        cargoHint.setText(cargoText);
        speedHint.setText(fast?"快速模式只縮短已驗證的等待與選取間隔；辨識重試與安全判定仍保留。":"穩定模式沿用目前已驗證的等待與辨識節奏。");
        int min=PilotConfig.minimumCount(pikminType);
        countTitle.setText("每輪 "+PilotConfig.pikminName(pikminType)+"皮克敏");countHint.setText("設定目標 "+pikminCount+" 隻；若遊戲已亮 GO，會接受較少但可合法出發的隊伍");pikminCountView.setText(pikminCount+" 隻");
        summaryRun.setText(runLabel());summaryCargo.setText(PilotConfig.cargoName(cargoMode));
        int enabledFallbacks=(fallback1Enabled!=null&&fallback1Enabled.isChecked()?1:0)+(fallback2Enabled!=null&&fallback2Enabled.isChecked()?1:0)+(fallback3Enabled!=null&&fallback3Enabled.isChecked()?1:0);
        summaryPikmin.setText(PilotConfig.pikminName(pikminType)+"皮×"+pikminCount+(enabledFallbacks>0?" + F"+enabledFallbacks:""));
    }

    private String runLabel(){int p=runMode==null?1:runMode.getSelectedItemPosition();return p==4?"無限循環":new String[]{"1 顆","5 顆","10 顆","20 顆","無限循環"}[p];}
    private int runTarget(){int p=runMode.getSelectedItemPosition();return p==4?0:new int[]{1,5,10,20,0}[p];}
    private void refreshService(){boolean on=PilotAccessibilityService.get()!=null;serviceStatus.setText(on?"Accessibility ✓":"Accessibility ! 尚未啟用");}
    private void openPikmin(){Intent i=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");if(i==null){Toast.makeText(this,"找不到 Pikmin Bloom (com.nianticlabs.pikmin)",Toast.LENGTH_LONG).show();return;}startActivity(i);}
    private void startPilot(){
        if(PilotAccessibilityService.get()==null){Toast.makeText(this,"請先啟用 Pikmin Pilot Automation 輔助使用服務",Toast.LENGTH_LONG).show();startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}
        java.util.List<PilotConfig.SelectionPlan> fallbacks=new java.util.ArrayList<>();
        fallbacks.add(readFallback(1,fallback1Enabled,fallback1Type,fallback1Count));
        fallbacks.add(readFallback(2,fallback2Enabled,fallback2Type,fallback2Count));
        fallbacks.add(readFallback(3,fallback3Enabled,fallback3Type,fallback3Count));
        PilotConfig cfg=new PilotConfig(pikminType,cargoMode,pikminCount,runTarget(),fast,fallbacks);
        appendLog("START • "+runLabel()+" • "+PilotConfig.cargoName(cargoMode)+" • "+PilotConfig.pikminName(pikminType)+"×"+pikminCount+" • fallbacks="+enabledFallbackSummary(cfg));
        openPikmin();
        // User workflow: leave Pikmin Bloom already open on the Expedition list,
        // switch back to Pilot, then press START.  Do not inspect the screen during
        // the Android app-switch animation: give Pikmin Bloom a full 3 seconds to
        // return from background to foreground before the first screenshot.
        new android.os.Handler(getMainLooper()).postDelayed(()->PilotController.get().start(cfg),3000);
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
