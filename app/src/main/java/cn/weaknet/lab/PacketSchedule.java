package cn.weaknet.lab;

/** Shared per direction; accounts for IP bytes, including headers and retransmissions. */
public final class PacketSchedule {
    private long available;
    private long lastDue;
    private boolean initialized;
    static long serializationNanos(int bytes, int kbps) {
        return kbps == 0 ? 0 : (long)Math.ceil(bytes * 8000000.0 / kbps);
    }
    public synchronized long due(long now, int bytes, int kbps, int delayMs) {
        long serialization = serializationNanos(bytes, kbps);
        // nanoTime has an arbitrary origin and may be negative or wrap around.
        available = (initialized && available - now > 0 ? available : now) + serialization;
        long candidate = available + delayMs * 1000000L;
        lastDue = initialized && lastDue - candidate > 0 ? lastDue : candidate;
        initialized = true;
        return lastDue;
    }
    public synchronized void reset() { initialized = false; }
}
