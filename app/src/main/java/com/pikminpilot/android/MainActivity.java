package com.pikminpilot.android;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.pikminpilot.android.automation.PilotAccessibilityService;
import com.pikminpilot.android.automation.PilotController;
import com.pikminpilot.android.model.PilotConfig;

public class MainActivity extends Activity implements PilotController.Listener {
    private TextView serviceStatus,runStatus,logView;
    private Spinner pikminType;
    private EditText pikminCount,dispatchCount;
    private CheckBox fastMode;
    private final StringBuilder log=new StringBuilder();

    @Override protected void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);
        serviceStatus=findViewById(R.id.serviceStatus);runStatus=findViewById(R.id.runStatus);logView=findViewById(R.id.logView);
        pikminType=findViewById(R.id.pikminType);pikminCount=findViewById(R.id.pikminCount);dispatchCount=findViewById(R.id.dispatchCount);fastMode=findViewById(R.id.fastMode);
        pikminType.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"紫皮克敏","白皮克敏","粉紅皮克敏","岩石皮克敏"}));
        findViewById(R.id.openAccessibility).setOnClickListener(v->startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        findViewById(R.id.openPikmin).setOnClickListener(v->openPikmin());
        findViewById(R.id.startPilot).setOnClickListener(v->startPilot());
        findViewById(R.id.stopPilot).setOnClickListener(v->PilotController.get().stop());
        findViewById(R.id.testScreenshot).setOnClickListener(v->PilotController.get().testScreenshot(this::appendLog));
        PilotController.get().setListener(this);refreshService();
    }

    @Override protected void onResume(){super.onResume();PilotController.get().setListener(this);refreshService();}
    @Override protected void onPause(){super.onPause();PilotController.get().setListener(null);}

    private void refreshService(){boolean on=PilotAccessibilityService.get()!=null;serviceStatus.setText(on?"Accessibility: 已啟用 ✅":"Accessibility: 尚未啟用");}
    private void openPikmin(){Intent i=getPackageManager().getLaunchIntentForPackage("com.nianticlabs.pikmin");if(i==null){Toast.makeText(this,"找不到 Pikmin Bloom (com.nianticlabs.pikmin)",Toast.LENGTH_LONG).show();return;}startActivity(i);}
    private int intValue(EditText e,int fallback){try{return Integer.parseInt(e.getText().toString().trim());}catch(Exception x){return fallback;}}
    private void startPilot(){if(PilotAccessibilityService.get()==null){Toast.makeText(this,"請先啟用 Pikmin Pilot Automation 輔助使用服務",Toast.LENGTH_LONG).show();startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));return;}
        PilotConfig.PikminType[] t={PilotConfig.PikminType.PURPLE,PilotConfig.PikminType.WHITE,PilotConfig.PikminType.PINK,PilotConfig.PikminType.ROCK};
        PilotConfig cfg=new PilotConfig(t[pikminType.getSelectedItemPosition()],intValue(pikminCount,12),intValue(dispatchCount,5),fastMode.isChecked());
        openPikmin();new android.os.Handler(getMainLooper()).postDelayed(()->PilotController.get().start(cfg),900);
    }

    private void appendLog(String s){if(log.length()>12000)log.delete(0,4000);log.append(s).append('\n');logView.setText(log.toString());}
    @Override public void onStatus(String status){runStatus.setText(status);}
    @Override public void onLog(String line){appendLog(line);}
}
