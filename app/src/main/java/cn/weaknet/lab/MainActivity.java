package cn.weaknet.lab;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.Locale;

public final class MainActivity extends Activity {
    private final int ink=Color.rgb(24,37,62), blue=Color.rgb(23,92,255);
    private EditText down,up,delay,jitter,loss;
    private Switch offline;
    private TextView state,stats;
    private Button start;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private long lastUp,lastDown,lastTime;
    private PacketBridge sampledBridge;
    private final Runnable refresh=new Runnable() { public void run() {
        state.setText(WeakVpnService.status); start.setText(WeakVpnService.running?"应用新参数":"开始弱网测试");
        PacketBridge b=WeakVpnService.current; long now=System.nanoTime();
        if(b!=null) {
            long u=b.uploaded.get(),d=b.downloaded.get(); double seconds=(now-lastTime)/1e9;
            if(sampledBridge!=b) {lastUp=u;lastDown=d;sampledBridge=b;}
            stats.setText(String.format(Locale.CHINA,"↓ %.1f Kbps     ↑ %.1f Kbps\n累计 ↓ %.2f MB   ↑ %.2f MB   丢包 %d",
                    seconds>0?Math.max(0,d-lastDown)*8/1000.0/seconds:0,seconds>0?Math.max(0,u-lastUp)*8/1000.0/seconds:0,d/1000000.0,u/1000000.0,b.dropped.get()));
            lastUp=u;lastDown=d;
        } else { stats.setText("开启后显示实时流量和丢包计数"); lastUp=lastDown=0;sampledBridge=null; }
        lastTime=now; handler.postDelayed(this,1000);
    }};
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private TextView text(String s,int size,int color){TextView v=new TextView(this);v.setText(s);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(6),0,dp(6));return v;}
    private GradientDrawable background(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(16));return d;}
    private LinearLayout card(LinearLayout parent){LinearLayout c=new LinearLayout(this);c.setOrientation(1);c.setPadding(dp(18),dp(12),dp(18),dp(12));c.setBackground(background(Color.WHITE));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(12),0,0);parent.addView(c,p);return c;}
    private EditText field(LinearLayout c,String label,String value,boolean decimal){c.addView(text(label,14,ink));EditText e=new EditText(this);e.setText(value);e.setTextSize(20);e.setSingleLine();e.setInputType(InputType.TYPE_CLASS_NUMBER|(decimal?InputType.TYPE_NUMBER_FLAG_DECIMAL:0));c.addView(e,new LinearLayout.LayoutParams(-1,dp(48)));return e;}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);
        getWindow().setStatusBarColor(Color.rgb(242,245,251));getWindow().setNavigationBarColor(Color.rgb(242,245,251));
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(Color.rgb(242,245,251));
        LinearLayout root=new LinearLayout(this);root.setOrientation(1);root.setPadding(dp(22),dp(22),dp(22),dp(28));scroll.addView(root);setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v,insets)-> { v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets.consumeSystemWindowInsets(); });
        root.addView(text("NETWORK / TEST LAB",12,blue));TextView title=text("弱网实验室",30,ink);title.setTypeface(null,Typeface.BOLD);root.addView(title);
        root.addView(text("在手机上，重现不理想的网络。",15,Color.GRAY));
        LinearLayout overview=card(root);state=text(WeakVpnService.status,18,blue);overview.addView(state);stats=text("",13,ink);overview.addView(stats);
        LinearLayout presets=card(root);presets.addView(text("快速场景",17,ink));
        LinearLayout row=new LinearLayout(this);presets.addView(row);
        String[] names={"慢速","拥堵","高延迟"};int[][] values={{256,64,200,50,1},{1024,256,100,80,3},{2048,512,500,100,0}};
        for(int i=0;i<3;i++){final int n=i;Button b=new Button(this);b.setText(names[i]);row.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));b.setOnClickListener(v->{int[] p=values[n];down.setText(""+p[0]);up.setText(""+p[1]);delay.setText(""+p[2]);jitter.setText(""+p[3]);loss.setText(""+p[4]);offline.setChecked(false);});}
        Profile p=WeakVpnService.read(this);LinearLayout fields=card(root);fields.addView(text("自定义网络参数",17,ink));
        down=field(fields,"下载上限 · Kbps（0 = 不限速）",""+p.downKbps,false);
        up=field(fields,"上传上限 · Kbps（0 = 不限速）",""+p.upKbps,false);
        delay=field(fields,"每方向新增延迟 · ms（0–10000）",""+p.delayMs,false);
        jitter=field(fields,"延迟抖动 · ±ms（0–10000）",""+p.jitterMs,false);
        loss=field(fields,"每方向随机丢包 · %（0–100）",""+p.loss,true);
        offline=new Switch(this);offline.setText("模拟断网（丢弃全部数据包）");offline.setTextSize(14);offline.setChecked(p.offline);offline.setPadding(0,dp(14),0,dp(14));fields.addView(offline);
        start=new Button(this);start.setText("开始弱网测试");start.setTextColor(Color.WHITE);start.setBackgroundTintList(android.content.res.ColorStateList.valueOf(blue));root.addView(start,new LinearLayout.LayoutParams(-1,dp(56)));start.setOnClickListener(v->begin());
        Button stop=new Button(this);stop.setText("停止并恢复网络");root.addView(stop,new LinearLayout.LayoutParams(-1,dp(52)));stop.setOnClickListener(v->stopService(new Intent(this,WeakVpnService.class)));
        root.addView(text("无需 root · Wi-Fi / 移动数据\n首次使用需允许系统 VPN 连接。与其他 VPN 互斥。参数填好后点击开始或应用。\n\n1000 Kbps ≈ 125 KB/s。延迟分别施加到上下行，往返新增约为设定值的两倍。限速统计包含 IP 头及重传；实际应用速度可能更低。\n\n流量仅在本机转发，不解密 HTTPS，不记录访问内容。DNS 使用 223.5.5.5 / 1.1.1.1。不支持 ICMP ping、热点客户端限速；不能提升原始网速。",12,Color.GRAY));
        if(saved!=null) {
            down.setText(saved.getString("draftDown",down.getText().toString()));up.setText(saved.getString("draftUp",up.getText().toString()));
            delay.setText(saved.getString("draftDelay",delay.getText().toString()));jitter.setText(saved.getString("draftJitter",jitter.getText().toString()));
            loss.setText(saved.getString("draftLoss",loss.getText().toString()));offline.setChecked(saved.getBoolean("draftOffline",offline.isChecked()));
        }
        if(Build.VERSION.SDK_INT>=33 && saved==null && checkSelfPermission("android.permission.POST_NOTIFICATIONS")!=0) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},2);
    }
    private void begin(){try{
        Profile p=new Profile(Integer.parseInt(down.getText().toString()),Integer.parseInt(up.getText().toString()),Integer.parseInt(delay.getText().toString()),Integer.parseInt(jitter.getText().toString()),Double.parseDouble(loss.getText().toString()),offline.isChecked());
        getSharedPreferences("profile",0).edit().putInt("down",p.downKbps).putInt("up",p.upKbps).putInt("delay",p.delayMs).putInt("jitter",p.jitterMs).putFloat("loss",(float)p.loss).putBoolean("offline",p.offline).apply();
        Intent request=VpnService.prepare(this);if(request!=null)startActivityForResult(request,1);else launch();
    }catch(IllegalArgumentException e){new AlertDialog.Builder(this).setTitle("请检查参数").setMessage("速率：0–1000000 Kbps\n延迟 / 抖动：0–10000 ms\n丢包：0–100%，所有字段必填。").setPositiveButton("知道了",null).show();}
    catch(RuntimeException e){new AlertDialog.Builder(this).setTitle("无法申请 VPN 权限").setMessage("系统未能打开 VPN 授权，请检查设备限制后重试。\n"+e.getMessage()).setPositiveButton("知道了",null).show();}}
    private void launch(){try {startForegroundService(new Intent(this,WeakVpnService.class));}
        catch(RuntimeException e) {new AlertDialog.Builder(this).setTitle("无法启动测试").setMessage("请保持应用在前台，并检查系统 VPN 权限。\n"+e.getMessage()).setPositiveButton("知道了",null).show();}}
    @Override protected void onSaveInstanceState(Bundle out) {
        out.putString("draftDown",down.getText().toString());out.putString("draftUp",up.getText().toString());
        out.putString("draftDelay",delay.getText().toString());out.putString("draftJitter",jitter.getText().toString());
        out.putString("draftLoss",loss.getText().toString());out.putBoolean("draftOffline",offline.isChecked());super.onSaveInstanceState(out);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==1){if(result==RESULT_OK)launch();else Toast.makeText(this,"未授权 VPN，网络未改变",Toast.LENGTH_SHORT).show();}}
    @Override protected void onResume(){super.onResume();lastTime=System.nanoTime();sampledBridge=null;handler.post(refresh);}
    @Override protected void onPause(){handler.removeCallbacks(refresh);super.onPause();}
}
