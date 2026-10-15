package cn.weaknet.lab;

public final class Profile {
    public final int downKbps, upKbps, delayMs, jitterMs;
    public final double loss;
    public final boolean offline;
    public Profile(int down, int up, int delay, int jitter, double loss, boolean offline) {
        if (down < 0 || up < 0 || down > 1000000 || up > 1000000 || delay < 0 || delay > 10000
                || jitter < 0 || jitter > 10000 || !Double.isFinite(loss) || loss < 0 || loss > 100)
            throw new IllegalArgumentException("参数超出范围");
        this.downKbps = down; this.upKbps = up; this.delayMs = delay; this.jitterMs = jitter;
        this.loss = loss; this.offline = offline;
    }
}
