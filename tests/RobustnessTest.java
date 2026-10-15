package cn.weaknet.lab;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

public final class RobustnessTest {
    static void check(boolean value,String label) {CoreTest.check(value,label);}
    static void until(BooleanSupplier condition,String name) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while(!condition.getAsBoolean()) {if(System.nanoTime()-end>=0)throw new AssertionError("timeout: "+name);Thread.sleep(5);}
    }
    static final class FakePort implements PacketBridge.Port {
        final BlockingQueue<byte[]> input=new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> output=new LinkedBlockingQueue<>();
        final List<Long> times=Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger consumed=new AtomicInteger();
        volatile boolean writable=true,broken;
        private boolean previousRead;
        public int read(byte[] buffer)throws IOException {
            if(previousRead) {consumed.incrementAndGet();previousRead=false;}
            if(broken) throw new IOException("injected read failure");
            try {
                byte[] packet=input.poll(100,TimeUnit.MILLISECONDS);
                if(packet==null)return 0;
                System.arraycopy(packet,0,buffer,0,packet.length);previousRead=true;return packet.length;
            } catch(InterruptedException e) {Thread.currentThread().interrupt();throw new IOException(e);}
        }
        public boolean write(byte[] packet)throws IOException {
            if(broken)throw new IOException("injected write failure");
            if(!writable)return false;
            times.add(System.nanoTime());output.add(packet.clone());return true;
        }
    }
    static Profile profile(int rate,int delay,double loss,boolean offline) {return new Profile(rate,rate,delay,0,loss,offline);}
    static void clockEdges() {
        PacketSchedule s=new PacketSchedule();
        check(s.due(-9000000000L,1000,80,0)==-8900000000L,"negative nanoTime origin");
        s.reset();long t=Long.MAX_VALUE-50000000L;
        check(s.due(t,1000,80,0)-t==100000000L,"nanoTime overflow-safe first packet");
        check(s.due(t,1000,80,0)-t==200000000L,"nanoTime overflow-safe aggregate pacing");
    }
    static void bridgeTests()throws Exception {
        AtomicInteger failures=new AtomicInteger();FakePort tun=new FakePort(),net=new FakePort();
        try(PacketBridge bridge=new PacketBridge(tun,net,profile(0,0,0,false),failures::incrementAndGet)) {
            bridge.start();tun.input.add(new byte[]{1});net.input.add(new byte[]{2});
            check(Arrays.equals(net.output.poll(2,TimeUnit.SECONDS),new byte[]{1}) && Arrays.equals(tun.output.poll(2,TimeUnit.SECONDS),new byte[]{2}),"bridge bidirectional integrity");
            bridge.update(profile(0,0,100,false));tun.input.add(new byte[]{3});net.input.add(new byte[]{4});
            until(()->bridge.dropped.get()>=2,"100 percent loss");
            check(net.output.isEmpty() && tun.output.isEmpty(),"100 percent loss suppresses both directions");
            bridge.update(profile(0,10000,0,false));int before=tun.consumed.get();tun.input.add(new byte[]{5});
            until(()->tun.consumed.get()>before,"old packet queued");
            bridge.update(profile(0,0,0,false));tun.input.add(new byte[]{6});
            check(Arrays.equals(net.output.poll(2,TimeUnit.SECONDS),new byte[]{6}),"profile update cancels queued and in-flight old packets");
            bridge.update(profile(0,0,0,true));long dropped=bridge.dropped.get();tun.input.add(new byte[]{7});
            until(()->bridge.dropped.get()>dropped,"offline packet");
            check(net.output.isEmpty(),"offline applies immediately");
            bridge.update(profile(0,0,0,false));tun.input.add(new byte[]{8});
            check(Arrays.equals(net.output.poll(2,TimeUnit.SECONDS),new byte[]{8}),"resume from offline");
        }
        check(failures.get()==0,"normal shutdown never reports spurious failure");
        FakePort burstIn=new FakePort(),burstOut=new FakePort();burstOut.writable=false;
        try(PacketBridge bridge=new PacketBridge(burstIn,burstOut,profile(80,0,0,false),failures::incrementAndGet)) {
            bridge.start();for(int i=0;i<4;i++)burstIn.input.add(new byte[1000]);
            until(()->burstIn.consumed.get()==4,"backpressure packets queued");Thread.sleep(550);burstOut.writable=true;
            until(()->burstOut.times.size()==4,"paced backlog");
            List<Long> times=burstOut.times;
            check(times.get(3)-times.get(0)>=290000000L,"backpressure recovery cannot bypass rate cap");
        }
        FakePort overflowIn=new FakePort(),overflowOut=new FakePort();
        try(PacketBridge bridge=new PacketBridge(overflowIn,overflowOut,profile(0,10000,0,false),failures::incrementAndGet)) {
            bridge.start();for(int i=0;i<700;i++)overflowIn.input.add(new byte[1500]);
            until(()->overflowIn.consumed.get()==700,"bounded queue ingress");
            check(bridge.dropped.get()>=187,"queue overflow drops packets at fixed bound");
        }
        FakePort stopIn=new FakePort(),stopOut=new FakePort();stopOut.writable=false;
        PacketBridge stopped=new PacketBridge(stopIn,stopOut,profile(0,0,0,false),failures::incrementAndGet);
        stopped.start();stopIn.input.add(new byte[32]);until(()->stopIn.consumed.get()==1,"blocked writer");
        long start=System.nanoTime();stopped.close();stopped.close();
        check(System.nanoTime()-start<1000000000L,"idempotent shutdown joins workers under backpressure");
        stopOut.writable=true;check(stopOut.output.isEmpty(),"no writes escape shutdown");
        FakePort failedIn=new FakePort(),failedOut=new FakePort();failedIn.broken=failedOut.broken=true;
        AtomicInteger notified=new AtomicInteger();
        try(PacketBridge bridge=new PacketBridge(failedIn,failedOut,profile(0,0,0,false),notified::incrementAndGet)) {
            bridge.start();until(()->notified.get()>0,"failure callback");
        }
        check(notified.get()==1,"concurrent I/O failures notify once");
        check(failures.get()==0,"stress paths preserve normal bridge lifecycle");
    }
    static void resourceRace()throws Exception {
        for(int round=0;round<100;round++) {
            ResourceGroup group=new ResourceGroup();AtomicInteger created=new AtomicInteger(),closed=new AtomicInteger();
            CountDownLatch go=new CountDownLatch(1);
            Thread creator=new Thread(()->{try {go.await();for(int n=0;n<100;n++) {
                created.incrementAndGet();AtomicBoolean once=new AtomicBoolean();
                try {group.add((Closeable)()->{if(once.compareAndSet(false,true))closed.incrementAndGet();});}catch(IOException ignored){}
            }}catch(InterruptedException e){throw new RuntimeException(e);}});
            creator.start();go.countDown();group.close();creator.join();group.close();
            if(created.get()!=closed.get() || group.size()!=0)throw new AssertionError("resource registration/close race");
        }
        check(true,"100 concurrent register/shutdown rounds release every resource");
    }
    static LocalSocks proxy(boolean protect)throws IOException {
        return new LocalSocks(new LocalSocks.Protector(){public boolean protect(Socket s){return protect;}public boolean protect(DatagramSocket s){return protect;}});
    }
    static byte[] udpFrame(int port)throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
        out.write(new byte[]{0,0,0,1,127,0,0,1});out.writeShort(port);out.writeUTF("survives malformed datagrams");return bytes.toByteArray();
    }
    static void malformedUdp()throws Exception {
        LocalSocks proxy=proxy(true);
        try(DatagramSocket echo=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));
            DatagramSocket sender=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));
            DatagramSocket stranger=new DatagramSocket(new InetSocketAddress("127.0.0.1",0));
            Socket control=CoreTest.socks(proxy.port(),3,InetAddress.getByName("0.0.0.0"),0)) {
            sender.setSoTimeout(3000);echo.setSoTimeout(3000);int relay=CoreTest.response(control);
            InetSocketAddress target=new InetSocketAddress("127.0.0.1",relay);
            stranger.send(new DatagramPacket(new byte[]{0},1,target));
            byte[] frame=udpFrame(echo.getLocalPort());
            byte[][] malformed={new byte[]{0},new byte[]{0,0,0,4,1},new byte[]{0,0,1,1,127,0,0,1,0,53},new byte[]{0,0,0,99}};
            for(byte[] bad:malformed)sender.send(new DatagramPacket(bad,bad.length,target));
            sender.send(new DatagramPacket(frame,frame.length,target));
            DatagramPacket payload=new DatagramPacket(new byte[1024],1024);echo.receive(payload);echo.send(payload);
            DatagramPacket received=new DatagramPacket(new byte[1024],1024);sender.receive(received);
            check(Arrays.equals(frame,Arrays.copyOf(received.getData(),received.getLength())),"malformed UDP neither kills nor hijacks association");
        } finally {proxy.close();check(proxy.awaitTermination(3,TimeUnit.SECONDS),"UDP shutdown drains all workers");}
        check(proxy.resourceCount()==0,"UDP association leaves no tracked resources");
    }
    static void tcpFailureAndShutdown()throws Exception {
        LocalSocks denied=proxy(false);
        try(Socket client=CoreTest.socks(denied.port(),1,InetAddress.getByName("127.0.0.1"),80)) {
            byte[] response=new byte[10];new DataInputStream(client.getInputStream()).readFully(response);
            check(response[1]!=0,"failed protect explicitly rejects TCP connection");
        } finally {denied.close();check(denied.awaitTermination(3,TimeUnit.SECONDS),"protect failure releases workers");}
        for(int round=0;round<15;round++) {
            LocalSocks proxy=proxy(true);List<Socket> clients=new ArrayList<>();
            try {
                for(int n=0;n<8;n++)clients.add(new Socket("127.0.0.1",proxy.port()));
                proxy.close();proxy.close();
                if(!proxy.awaitTermination(3,TimeUnit.SECONDS) || proxy.resourceCount()!=0)throw new AssertionError("incomplete SOCKS close");
            } finally {proxy.close();for(Socket client:clients)client.close();}
        }
        check(true,"15 rapid proxy cycles close stalled handshakes without leaks");
    }
    public static void main(String[] args)throws Exception {
        clockEdges();resourceRace();bridgeTests();malformedUdp();tcpFailureAndShutdown();
        System.out.println("ALL ROBUSTNESS TESTS PASSED");
    }
}
