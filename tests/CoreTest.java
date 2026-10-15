package cn.weaknet.lab;

import java.io.*;
import java.net.*;
import java.util.Arrays;
import java.util.concurrent.*;

public final class CoreTest {
    static void check(boolean ok,String name) { if(!ok) throw new AssertionError(name); System.out.println("PASS "+name); }
    static Socket socks(int port,int command,InetAddress target,int targetPort) throws IOException {
        Socket s=new Socket("127.0.0.1",port); s.setSoTimeout(5000);
        DataOutputStream out=new DataOutputStream(s.getOutputStream()); DataInputStream in=new DataInputStream(s.getInputStream());
        out.write(new byte[]{5,1,0});check(in.readUnsignedShort()==0x0500,"SOCKS negotiation");
        out.write(new byte[]{5,(byte)command,0,(byte)(target.getAddress().length==4?1:4)});out.write(target.getAddress());out.writeShort(targetPort);
        return s;
    }
    static int response(Socket s) throws IOException {
        byte[] b=new byte[10];new DataInputStream(s.getInputStream()).readFully(b);check(b[1]==0,"SOCKS request accepted");return (b[8]&255)*256+(b[9]&255);
    }
    static void tcp(LocalSocks proxy, String ip) throws Exception {
        InetAddress address=InetAddress.getByName(ip);
        try(ServerSocket echo=new ServerSocket(0,1,address)) {
            ExecutorService worker=Executors.newSingleThreadExecutor();
            Future<?> f=worker.submit(()->{try(Socket s=echo.accept()) {byte[] all=s.getInputStream().readAllBytes();s.getOutputStream().write(all);s.shutdownOutput();}catch(IOException e){throw new UncheckedIOException(e);}});
            try(Socket s=socks(proxy.port(),1,address,echo.getLocalPort())) {
                response(s);byte[] payload=new byte[262144];new java.util.Random(42).nextBytes(payload);
                s.getOutputStream().write(payload);s.shutdownOutput();
                check(Arrays.equals(payload,s.getInputStream().readAllBytes()),"TCP integrity + half close "+ip);f.get(5,TimeUnit.SECONDS);
            } finally {worker.shutdownNow();}
        }
    }
    static void udp(LocalSocks proxy,String ip) throws Exception {
        InetAddress address=InetAddress.getByName(ip);
        try(DatagramSocket echo=new DatagramSocket(new InetSocketAddress(address,0));DatagramSocket client=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));Socket control=socks(proxy.port(),3,InetAddress.getByName("0.0.0.0"),0)) {
            int port=response(control);client.setSoTimeout(5000);echo.setSoTimeout(5000);
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
            out.writeShort(0);out.writeByte(0);out.writeByte(address.getAddress().length==4?1:4);out.write(address.getAddress());out.writeShort(echo.getLocalPort());out.writeUTF("UDP test "+ip);
            byte[] sent=bytes.toByteArray();client.send(new DatagramPacket(sent,sent.length,InetAddress.getByName("127.0.0.1"),port));
            DatagramPacket p=new DatagramPacket(new byte[4096],4096);echo.receive(p);echo.send(p);
            DatagramPacket received=new DatagramPacket(new byte[4096],4096);client.receive(received);
            check(Arrays.equals(sent,Arrays.copyOf(received.getData(),received.getLength())),"UDP round trip "+ip);
        }
    }
    public static void main(String[] args)throws Exception {
        PacketSchedule s=new PacketSchedule();long now=1000000000L;
        check(s.due(now,125000,1000,100)==2100000000L,"1000 Kbps = 125000 bytes/s plus latency");
        check(s.due(now,125000,1000,100)==3100000000L,"aggregate bandwidth across packets");
        s.reset();check(s.due(now,1500,0,0)==now,"zero means unlimited");
        s.reset();long first=s.due(now,100,1000,500);check(s.due(now,100,1000,0)>=first,"jitter preserves packet order");
        try {new Profile(1,1,1,1,Double.NaN,false);throw new AssertionError();}catch(IllegalArgumentException expected){System.out.println("PASS rejects NaN loss");}
        java.util.concurrent.atomic.AtomicInteger protectedCount=new java.util.concurrent.atomic.AtomicInteger();
        try(LocalSocks proxy=new LocalSocks(new LocalSocks.Protector(){public boolean protect(Socket s){protectedCount.incrementAndGet();return true;}public boolean protect(DatagramSocket s){protectedCount.incrementAndGet();return true;}})) {
            tcp(proxy,"127.0.0.1");udp(proxy,"127.0.0.1");tcp(proxy,"::1");udp(proxy,"::1");
            check(protectedCount.get()==4,"every upstream socket is protected");
        }
        System.out.println("ALL CORE TESTS PASSED");
    }
}
