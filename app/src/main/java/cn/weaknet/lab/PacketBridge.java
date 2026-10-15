package cn.weaknet.lab;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Packet scheduling is platform independent so shutdown/update races can be tested on the JVM. */
final class PacketBridge implements AutoCloseable {
    interface Port {
        /** A packet, or zero on timeout. Must return within 250 ms even on a silent port. */
        int read(byte[] buffer) throws IOException;
        /** Nonblocking, all-or-nothing datagram write; false means backpressure. */
        boolean write(byte[] packet) throws IOException;
    }
    private final Object lock = new Object();
    private final List<Thread> threads = new ArrayList<>();
    private final List<Direction> directions = new ArrayList<>();
    private final Runnable failure;
    private Profile profile;
    private long generation;
    private volatile boolean closed;
    private boolean started, failed;
    final AtomicLong uploaded = new AtomicLong(), downloaded = new AtomicLong(), dropped = new AtomicLong();
    private static final class Packet {
        final byte[] data;
        final long due, generation;
        Packet(byte[] data, long due, long generation) { this.data=data; this.due=due; this.generation=generation; }
    }
    private static final class Direction {
        final ArrayBlockingQueue<Packet> queue = new ArrayBlockingQueue<>(512);
        final PacketSchedule schedule = new PacketSchedule();
        boolean hasWritten;
        long lastWritten;
    }
    PacketBridge(Port tun, Port bridge, Profile profile, Runnable failure) {
        this.profile=profile; this.failure=failure;
        direction(tun, bridge, true); direction(bridge, tun, false);
    }
    void start() {
        synchronized(lock) {
            if(closed || started) throw new IllegalStateException("Bridge already started or closed");
            started=true;
            for(Thread thread:threads) thread.start();
        }
    }
    void update(Profile next) {
        synchronized(lock) {
            if(closed) return;
            profile=next; generation++;
            for(Direction d:directions) {
                dropped.addAndGet(d.queue.size()); d.queue.clear(); d.schedule.reset(); d.hasWritten=false;
            }
        }
    }
    private void reportFailure() {
        synchronized(lock) {
            if(closed || failed) return;
            failed=true; closed=true;
        }
        for(Thread thread:threads) thread.interrupt();
        failure.run();
    }
    private void worker(Runnable work) {
        Thread thread=new Thread(work,"packet-shaper"); thread.setDaemon(true); threads.add(thread);
    }
    private void direction(Port input, Port output, boolean up) {
        Direction d=new Direction(); directions.add(d);
        worker(() -> {
            byte[] buffer=new byte[65535];
            try {
                while(!closed) {
                    int length=input.read(buffer);
                    if(length==0) continue;
                    if(length<0 || length>buffer.length) throw new IOException("Invalid packet length");
                    synchronized(lock) {
                        if(closed) return;
                        Profile p=profile;
                        if(p.offline || ThreadLocalRandom.current().nextDouble(100)<p.loss || d.queue.remainingCapacity()==0) {
                            dropped.incrementAndGet(); continue;
                        }
                        int jitter=p.jitterMs==0?0:ThreadLocalRandom.current().nextInt(-p.jitterMs,p.jitterMs+1);
                        long due=d.schedule.due(System.nanoTime(),length,up?p.upKbps:p.downKbps,Math.max(0,p.delayMs+jitter));
                        d.queue.add(new Packet(Arrays.copyOf(buffer,length),due,generation));
                    }
                }
            } catch(Exception e) { reportFailure(); }
        });
        worker(() -> {
            try {
                while(!closed) {
                    Packet packet=d.queue.poll(250,TimeUnit.MILLISECONDS);
                    if(packet==null) continue;
                    while(!closed) {
                        long wait;
                        synchronized(lock) {
                            if(closed) return;
                            if(packet.generation!=generation || profile.offline) { dropped.incrementAndGet(); break; }
                            long now=System.nanoTime(); wait=packet.due-now;
                            if(d.hasWritten) {
                                // Don't flush overdue packets as an unlimited burst after a scheduler pause.
                                long spacing=PacketSchedule.serializationNanos(packet.data.length,up?profile.upKbps:profile.downKbps);
                                wait=Math.max(wait,d.lastWritten+spacing-now);
                            }
                            if(wait<=0) {
                                if(output.write(packet.data)) {
                                    d.lastWritten=System.nanoTime(); d.hasWritten=true;
                                    (up?uploaded:downloaded).addAndGet(packet.data.length); break;
                                }
                                wait=10000000L;
                            }
                        }
                        TimeUnit.NANOSECONDS.sleep(Math.min(wait,50000000L));
                    }
                }
            } catch(Exception e) { reportFailure(); }
        });
    }
    @Override public void close() {
        synchronized(lock) { closed=true; }
        for(Thread thread:threads) thread.interrupt();
        boolean interrupted=false;
        for(Thread thread:threads) {
            if(thread==Thread.currentThread()) continue;
            while(thread.isAlive()) try { thread.join(); } catch(InterruptedException e) { interrupted=true; }
        }
        for(Direction d:directions) d.queue.clear();
        if(interrupted) Thread.currentThread().interrupt();
    }
}
