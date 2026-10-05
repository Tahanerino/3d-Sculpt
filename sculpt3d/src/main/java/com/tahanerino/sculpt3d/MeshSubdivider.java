package com.tahanerino.sculpt3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic linear (non-smoothing) triangle subdivision: every triangle becomes 4 by inserting
 * edge midpoints, shared edges reuse the same new vertex via a cache so the mesh stays watertight.
 * Positions and UVs are both linearly interpolated.
 *
 * This does NOT round or smooth the shape (a flat wall stays flat) -- it only adds vertex
 * density, which is what sculpt brushes need to deform a surface smoothly instead of tenting
 * between a handful of widely-spaced points. {@link IcoSphereBuilder} does its own version of
 * this because it additionally re-projects new vertices onto a sphere; this class is for
 * everything else (freehand-extruded shapes, in particular).
 */
public class MeshSubdivider {

    public static class Result {
        public final List<Vec3> positions;
        public final List<double[]> uvs;
        public final List<int[]> faces;
        Result(List<Vec3> p, List<double[]> u, List<int[]> f) { positions = p; uvs = u; faces = f; }
    }

    public static Result subdivide(List<Vec3> positions, List<double[]> uvs, List<int[]> faces, int levels) {
        List<Vec3> pos = new ArrayList<>(positions);
        List<double[]> uv = new ArrayList<>(uvs);
        List<int[]> tris = new ArrayList<>(faces);

        for (int level = 0; level < levels; level++) {
            Map<Long, Integer> midpointCache = new HashMap<>();
            List<int[]> next = new ArrayList<>(tris.size() * 4);
            for (int[] f : tris) {
                int a = f[0], b = f[1], c = f[2];
                int ab = midpoint(a, b, pos, uv, midpointCache);
                int bc = midpoint(b, c, pos, uv, midpointCache);
                int ca = midpoint(c, a, pos, uv, midpointCache);
                next.add(new int[]{a, ab, ca});
                next.add(new int[]{b, bc, ab});
                next.add(new int[]{c, ca, bc});
                next.add(new int[]{ab, bc, ca});
            }
            tris = next;
        }
        return new Result(pos, uv, tris);
    }

    private static int midpoint(int i1, int i2, List<Vec3> pos, List<double[]> uv, Map<Long, Integer> cache) {
        long key = i1 < i2 ? ((long) i1 << 32) | (i2 & 0xffffffffL) : ((long) i2 << 32) | (i1 & 0xffffffffL);
        Integer existing = cache.get(key);
        if (existing != null) return existing;

        Vec3 a = pos.get(i1), b = pos.get(i2);
        pos.add(new Vec3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2));

        double[] ua = uv.get(i1), ub = uv.get(i2);
        uv.add(new double[]{(ua[0] + ub[0]) / 2, (ua[1] + ub[1]) / 2});

        int idx = pos.size() - 1;
        cache.put(key, idx);
        return idx;
    }
}
