package cn.weaknet.lab;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import java.io.FileDescriptor;
import java.io.IOException;

final class AndroidPacketPort implements PacketBridge.Port {
    private final FileDescriptor fd;
    private final StructPollfd poll = new StructPollfd();
    private final StructPollfd[] polls = {poll};
    AndroidPacketPort(FileDescriptor fd) {
        this.fd = fd; poll.fd = fd; poll.events = (short)OsConstants.POLLIN;
    }
    @Override public int read(byte[] buffer) throws IOException {
        try {
            if (Os.poll(polls, 250) == 0) return 0;
            if ((poll.revents & (OsConstants.POLLERR | OsConstants.POLLHUP | OsConstants.POLLNVAL)) != 0)
                throw new IOException("Packet descriptor unavailable");
            int length = Os.read(fd, buffer, 0, buffer.length);
            if (length <= 0) throw new IOException("Packet descriptor closed");
            return length;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) return 0;
            throw new IOException("Packet read failed", e);
        }
    }
    @Override public boolean write(byte[] packet) throws IOException {
        try {
            if (Os.write(fd, packet, 0, packet.length) != packet.length)
                throw new IOException("Short packet write");
            return true;
        } catch (ErrnoException e) {
            if (e.errno == OsConstants.EAGAIN || e.errno == OsConstants.EINTR) return false;
            throw new IOException("Packet write failed", e);
        }
    }
}
