package cn.weaknet.lab;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;

/** Loopback-only SOCKS5 relay. No remote proxy, interception, or payload logging. */
public final class LocalSocks implements AutoCloseable {
    public interface Protector { boolean protect(Socket socket); boolean protect(DatagramSocket socket); }
    private final Protector protector;
    private final ServerSocket server;
    private final ResourceGroup resources=new ResourceGroup();
    private final ExecutorService pool=new ThreadPoolExecutor(0,192,30,TimeUnit.SECONDS,
            new SynchronousQueue<>(),r->{Thread t=new Thread(r,"socks");t.setDaemon(true);return t;});
    private volatile boolean closed;
    public LocalSocks(Protector protector) throws IOException {
        this.protector=protector;
        server=resources.add(new ServerSocket(0,64,InetAddress.getByName("127.0.0.1")));
        pool.execute(()->{
            while(!closed) try {
                Socket client=resources.add(server.accept());
                try { pool.execute(()->handle(client)); }
                catch(RejectedExecutionException e) { resources.drop(client); }
            } catch(IOException e) { if(!closed) close(); }
        });
    }
    public int port() { return server.getLocalPort(); }
    boolean isHealthy() { return !closed && !server.isClosed() && !pool.isShutdown(); }
    int resourceCount() { return resources.size(); }
    boolean awaitTermination(long timeout,TimeUnit unit) throws InterruptedException { return pool.awaitTermination(timeout,unit); }
    private static InetAddress address(DataInputStream in,int type) throws IOException {
        int length=type==1?4:type==4?16:-1;
        if(length<0) throw new IOException("Literal IP required");
        byte[] bytes=new byte[length];in.readFully(bytes);return InetAddress.getByAddress(bytes);
    }
    private static void reply(OutputStream out,int status,int port) throws IOException {
        out.write(new byte[]{5,(byte)status,0,1,127,0,0,1,(byte)(port>>8),(byte)port});out.flush();
    }
    private void handle(Socket client) {
        try {
            client.setSoTimeout(15000);
            DataInputStream in=new DataInputStream(client.getInputStream());OutputStream out=client.getOutputStream();
            if(in.readUnsignedByte()!=5) return;
            int count=in.readUnsignedByte();boolean anonymous=false;
            for(int i=0;i<count;i++) anonymous|=in.readUnsignedByte()==0;
            out.write(new byte[]{5,(byte)(anonymous?0:255)});out.flush();
            if(!anonymous || in.readUnsignedByte()!=5) return;
            int command=in.readUnsignedByte();if(in.readUnsignedByte()!=0) return;
            InetAddress destination;
            try { destination=address(in,in.readUnsignedByte()); }
            catch(IOException e) { reply(out,8,0);return; }
            int port=in.readUnsignedShort();
            if(command==1) tcp(client,in,out,destination,port);
            else if(command==3) udp(client,in,out);
            else reply(out,7,0);
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        catch(IOException | RuntimeException ignored) { /* Isolate each client. Never log payloads. */ }
        finally { resources.drop(client); }
    }
    private void tcp(Socket client,InputStream in,OutputStream out,InetAddress destination,int port) throws IOException,InterruptedException {
        Socket remote=resources.add(new Socket());
        try {
            try {
                if(!protector.protect(remote)) throw new IOException("protect failed");
                remote.connect(new InetSocketAddress(destination,port),15000);
            } catch(IOException | RuntimeException e) {
                reply(out,e instanceof ConnectException?5:1,0);return;
            }
            remote.setTcpNoDelay(true);client.setTcpNoDelay(true);client.setSoTimeout(0);
            CountDownLatch downstreamDone=new CountDownLatch(1);
            reply(out,0,0);
            pool.execute(()->{
                try { copy(remote.getInputStream(),out);client.shutdownOutput(); }
                catch(IOException e) { resources.drop(client);resources.drop(remote); }
                finally { downstreamDone.countDown(); }
            });
            copy(in,remote.getOutputStream());remote.shutdownOutput();
            downstreamDone.await();
        } finally { resources.drop(remote); }
    }
    private static void copy(InputStream in,OutputStream out) throws IOException {
        byte[] buffer=new byte[32768];int count;
        while((count=in.read(buffer))!=-1) out.write(buffer,0,count);
    }
    private static final class UdpFrame {
        final InetSocketAddress target; final int offset,length;
        UdpFrame(InetSocketAddress target,int offset,int length) {this.target=target;this.offset=offset;this.length=length;}
    }
    private static UdpFrame parse(byte[] bytes,int length) throws IOException {
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(bytes,0,length));
        if(in.readUnsignedShort()!=0 || in.readUnsignedByte()!=0) throw new IOException("Fragmented/invalid UDP frame");
        InetAddress destination=address(in,in.readUnsignedByte());int port=in.readUnsignedShort();
        if(port==0) throw new IOException("Invalid UDP port");
        return new UdpFrame(new InetSocketAddress(destination,port),length-in.available(),in.available());
    }
    private void udp(Socket control,InputStream in,OutputStream out) throws IOException {
        ResourceGroup association=resources.add(new ResourceGroup());
        try {
            DatagramSocket relay=association.add(new DatagramSocket(new InetSocketAddress("127.0.0.1",0)));
            reply(out,0,relay.getLocalPort());control.setSoTimeout(0);
            pool.execute(()->receiveUdp(control,relay,association));
            while(in.read()!=-1) { }
        } finally { resources.drop(association); }
    }
    private void receiveUdp(Socket control,DatagramSocket relay,ResourceGroup association) {
        Map<InetSocketAddress,DatagramSocket> remotes=new ConcurrentHashMap<>();
        InetSocketAddress peer=null;
        byte[] buffer=new byte[65535];
        try {
            while(!closed && !relay.isClosed()) {
                DatagramPacket packet=new DatagramPacket(buffer,buffer.length);relay.receive(packet);
                InetSocketAddress source=(InetSocketAddress)packet.getSocketAddress();
                if(!source.getAddress().isLoopbackAddress() || (peer!=null && !peer.equals(source))) continue;
                UdpFrame frame;
                try { frame=parse(buffer,packet.getLength()); }
                catch(IOException | RuntimeException e) { continue; } // Malformed datagram is not a broken association.
                if(peer==null) peer=source; // Invalid packets cannot claim the association's endpoint.
                DatagramSocket remote=remotes.get(frame.target);
                if(remote==null) {
                    if(remotes.size()>=64) continue;
                    remote=association.add(new DatagramSocket());
                    boolean registered=false;
                    try {
                        if(!protector.protect(remote)) continue;
                        remote.connect(frame.target);remote.setSoTimeout(30000);
                        DatagramSocket current=remote;InetSocketAddress replyTo=peer;
                        remotes.put(frame.target,current);
                        pool.execute(()->returnUdp(current,relay,replyTo,frame.target,association,remotes));
                        registered=true;
                    } catch(IOException | RuntimeException ignored) { }
                    finally {
                        if(!registered) {remotes.remove(frame.target,remote);association.drop(remote);}
                    }
                    if(!registered) continue;
                }
                try { remote.send(new DatagramPacket(buffer,frame.offset,frame.length)); }
                catch(IOException e) { remotes.remove(frame.target,remote);association.drop(remote); }
            }
        } catch(IOException | RuntimeException ignored) { }
        finally { resources.drop(control);resources.drop(association); }
    }
    private void returnUdp(DatagramSocket remote,DatagramSocket relay,InetSocketAddress peer,
                           InetSocketAddress target,ResourceGroup association,Map<InetSocketAddress,DatagramSocket> remotes) {
        try {
            byte[] buffer=new byte[65507];byte[] ip=target.getAddress().getAddress();
            while(!closed) {
                DatagramPacket packet=new DatagramPacket(buffer,buffer.length);remote.receive(packet);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();DataOutputStream out=new DataOutputStream(bytes);
                out.writeShort(0);out.writeByte(0);out.writeByte(ip.length==4?1:4);out.write(ip);out.writeShort(target.getPort());
                out.write(buffer,0,packet.getLength());byte[] response=bytes.toByteArray();
                // SOCKS framing itself must also fit into a UDP datagram.
                if(response.length<=65507) relay.send(new DatagramPacket(response,response.length,peer));
            }
        } catch(IOException | RuntimeException ignored) { }
        finally {remotes.remove(target,remote);association.drop(remote);}
    }
    @Override public void close() {
        closed=true;resources.close();pool.shutdownNow();
    }
}
