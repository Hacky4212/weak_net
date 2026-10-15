package cn.weaknet.lab;

import android.app.*;
import android.content.*;
import android.net.VpnService;
import android.os.*;
import android.system.*;
import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import hev.htproxy.TProxyService;

public final class WeakVpnService extends VpnService {
    static volatile boolean running;
    static volatile String status="未启动 · 正常网络";
    static volatile PacketBridge current;
    // Shared across service instances: an old JNI teardown must finish before a new JNI start.
    private static final ScheduledExecutorService WORKER=Executors.newSingleThreadScheduledExecutor(r->{
        Thread thread=new Thread(r,"vpn-lifecycle");thread.setDaemon(true);return thread;
    });
    private volatile boolean destroyed,revoked;
    private Session session; // Accessed only on WORKER.
    private ScheduledFuture<?> health;
    private PendingIntent open;
    private static final class Session {
        ParcelFileDescriptor vpn,nativeSide;
        FileDescriptor bridgeFd;
        LocalSocks socks;
        PacketBridge bridge;
        boolean nativeStarted;
        int startId;
    }
    static Profile read(Context context) {
        SharedPreferences p=context.getSharedPreferences("profile",0);
        try {
            return new Profile(p.getInt("down",512),p.getInt("up",128),p.getInt("delay",150),p.getInt("jitter",30),p.getFloat("loss",0),p.getBoolean("offline",false));
        } catch(IllegalArgumentException | ClassCastException e) {
            // Corrupt or old preferences must not prevent opening the controls to recover.
            return new Profile(512,128,150,30,0,false);
        }
    }
    @Override public int onStartCommand(Intent intent,int flags,int startId) {
        if(intent==null || "STOP".equals(intent.getAction())) {
            WORKER.execute(()->{cleanup();status="已停止 · 正常网络";stopSelfResult(startId);});
            return START_NOT_STICKY;
        }
        final Profile profile=read(this);
        try {
            NotificationManager nm=getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("vpn","弱网测试",NotificationManager.IMPORTANCE_LOW));
            open=PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,WeakVpnService.class).setAction("STOP"),PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
            startForeground(1,new Notification.Builder(this,"vpn").setSmallIcon(cn.weaknet.lab.R.drawable.ic_network)
                    .setContentTitle("弱网实验室正在运行").setContentText("手机流量正在经过本地弱网模拟").setContentIntent(open)
                    .addAction(new Notification.Action.Builder(null,"停止并恢复网络",stop).build()).setOngoing(true).build());
            WORKER.execute(()->startOrUpdate(profile,startId));
        } catch(RuntimeException e) {
            WORKER.execute(()->fail(null,"无法启动前台服务："+e.getMessage(),startId));
        }
        return START_NOT_STICKY;
    }
    private void startOrUpdate(Profile profile,int startId) {
        if(destroyed || revoked) return;
        if(session!=null) {
            session.startId=startId;session.bridge.update(profile);status="运行中 · 参数已更新";return;
        }
        Session next=new Session();next.startId=startId;session=next;
        status="启动中 · 正在建立本地 VPN";
        try {
            next.socks=new LocalSocks(new LocalSocks.Protector() {
                public boolean protect(Socket socket) {return WeakVpnService.this.protect(socket);}
                public boolean protect(DatagramSocket socket) {return WeakVpnService.this.protect(socket);}
            });
            File config=new File(getFilesDir(),"tunnel.yml");
            try(FileWriter writer=new FileWriter(config)) {
                writer.write("tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fd00:1::1'\n  icmp: 'off'\nsocks5:\n  address: 127.0.0.1\n  port: "+next.socks.port()+"\n  udp: 'udp'\nmisc:\n  max-session-count: 256\n  log-level: error\n");
            }
            FileDescriptor a=new FileDescriptor(),b=new FileDescriptor();
            Os.socketpair(OsConstants.AF_UNIX,OsConstants.SOCK_DGRAM,0,a,b);
            next.bridgeFd=a;
            try {
                Os.fcntlInt(a,OsConstants.F_SETFL,Os.fcntlInt(a,OsConstants.F_GETFL,0)|OsConstants.O_NONBLOCK);
                next.nativeSide=ParcelFileDescriptor.dup(b);
            } finally {Os.close(b);}
            next.vpn=new Builder().setSession("弱网实验室").setMtu(1500).setBlocking(false)
                    .addAddress("198.18.0.1",32).addAddress("fd00:1::1",128)
                    .addRoute("0.0.0.0",0).addRoute("::",0).addDnsServer("223.5.5.5").addDnsServer("1.1.1.1")
                    .addDisallowedApplication(getPackageName()).setConfigureIntent(open).establish();
            if(next.vpn==null) throw new IOException("VPN 授权未完成");
            if(destroyed || revoked) {cleanup();return;}
            if(!TProxyService.TProxyStartService(config.getAbsolutePath(),next.nativeSide.getFd())) throw new IOException("无法启动转发内核");
            next.nativeStarted=true;
            next.bridge=new PacketBridge(new AndroidPacketPort(next.vpn.getFileDescriptor()),new AndroidPacketPort(next.bridgeFd),profile,
                    ()->WORKER.execute(()->fail(next,"数据通道异常，已释放 VPN",0)));
            next.bridge.start();
            if(destroyed || revoked) {cleanup();return;}
            current=next.bridge;running=true;status="运行中 · 全局弱网已启用";
            health=WORKER.scheduleWithFixedDelay(()->{
                if(session!=next || destroyed || revoked) return;
                try {
                    if(!TProxyService.TProxyIsRunning() || !next.socks.isHealthy()) fail(next,"转发服务已退出，已释放 VPN",0);
                } catch(RuntimeException | LinkageError e) {fail(next,"转发服务异常，已释放 VPN",0);}
            },1,1,TimeUnit.SECONDS);
        } catch(Exception | LinkageError e) {fail(next,"启动失败："+e.getMessage(),startId);}
    }
    private void fail(Session expected,String reason,int startId) {
        // Failure queued by a retired bridge must not stop its replacement.
        if(expected!=null && session!=expected) return;
        int failedStartId=startId>0?startId:expected!=null?expected.startId:0;
        cleanup();status=reason;
        if(failedStartId>0) stopSelfResult(failedStartId);else stopSelf();
    }
    private void cleanup() {
        Session old=session;session=null;
        if(health!=null) {health.cancel(false);health=null;}
        if(old==null) return;
        if(current==old.bridge) {current=null;running=false;}
        if(old.bridge!=null) old.bridge.close(); // Join workers before descriptor numbers can be reused.
        // Release the OS VPN before waiting for native teardown to avoid keeping traffic trapped.
        close(old.vpn);old.vpn=null;
        if(old.socks!=null) old.socks.close();
        try {
            if(old.nativeStarted) TProxyService.TProxyStopService();
        } catch(RuntimeException | LinkageError e) {
            android.util.Log.e("WeakNet","Native shutdown failed",e);
        } finally {
            close(old.nativeSide);
            if(old.bridgeFd!=null) try {Os.close(old.bridgeFd);}catch(ErrnoException ignored){}
        }
    }
    private static void close(ParcelFileDescriptor fd) {if(fd!=null) try{fd.close();}catch(IOException ignored){}}
    @Override public void onRevoke() {
        revoked=true;WORKER.execute(()->{cleanup();status="VPN 授权已撤销 · 已释放 VPN";stopSelf();});
    }
    @Override public void onDestroy() {
        destroyed=true;
        WORKER.execute(()->{
            boolean wasActive=session!=null;cleanup();
            if(wasActive) status="已停止 · 正常网络";
        });
        stopForeground(STOP_FOREGROUND_REMOVE);super.onDestroy();
    }
}
