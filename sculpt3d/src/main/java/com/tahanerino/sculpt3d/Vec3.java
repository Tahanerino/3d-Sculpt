package com.tahanerino.sculpt3d;

/** Minimal mutable 3D vector. Nothing fancy on purpose -- this whole project favors
 *  readability over micro-optimization since it's a learning/prototype sculpting tool. */
public class Vec3 {
    public double x, y, z;

    public Vec3() { this(0, 0, 0); }
    public Vec3(double x, double y, double z) { this.x = x; this.y = y; this.z = z; }
    public Vec3(Vec3 o) { this(o.x, o.y, o.z); }

    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 scale(double s) { return new Vec3(x * s, y * s, z * s); }
    public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }

    public Vec3 cross(Vec3 o) {
        return new Vec3(
                y * o.z - z * o.y,
                z * o.x - x * o.z,
                x * o.y - y * o.x);
    }

    public double length() { return Math.sqrt(x * x + y * y + z * z); }

    public double distance(Vec3 o) { return sub(o).length(); }

    public Vec3 normalized() {
        double len = length();
        if (len < 1e-9) return new Vec3(0, 1, 0);
        return new Vec3(x / len, y / len, z / len);
    }

    public void set(double nx, double ny, double nz) { x = nx; y = ny; z = nz; }
    public void setFrom(Vec3 o) { x = o.x; y = o.y; z = o.z; }
    public void addInPlace(Vec3 o) { x += o.x; y += o.y; z += o.z; }
}
