package com.frameflags.tpa.core;

/** Portable, immutable destination pose. Dimension is a stable string key. */
public final class TpaPose {
    public final double x;
    public final double y;
    public final double z;
    public final float yaw;
    public final float pitch;
    public final String dimension;

    public TpaPose(double x, double y, double z, float yaw, float pitch, String dimension) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.dimension = dimension == null ? "" : dimension;
    }

    public String format() {
        return String.format("%.2f, %.2f, %.2f (%s)", x, y, z, dimension);
    }
}
