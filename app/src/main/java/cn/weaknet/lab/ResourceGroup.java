package cn.weaknet.lab;

import java.io.Closeable;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Registration and shutdown are atomic: resources created during shutdown cannot escape. */
final class ResourceGroup implements Closeable {
    private final Set<Closeable> resources = new HashSet<>();
    private boolean closed;
    synchronized <T extends Closeable> T add(T resource) throws IOException {
        if (closed) {
            closeQuietly(resource);
            throw new IOException("Resource group closed");
        }
        resources.add(resource);
        return resource;
    }
    void drop(Closeable resource) {
        synchronized (this) { resources.remove(resource); }
        closeQuietly(resource);
    }
    synchronized int size() { return resources.size(); }
    @Override public void close() {
        Closeable[] snapshot;
        synchronized (this) {
            if (closed) return;
            closed = true;
            snapshot = resources.toArray(new Closeable[0]);
            resources.clear();
        }
        for (Closeable resource : snapshot) closeQuietly(resource);
    }
    private static void closeQuietly(Closeable resource) {
        try { resource.close(); } catch (IOException ignored) { }
    }
}
