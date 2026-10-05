package com.tahanerino.sculpt3d;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds a subdivided icosahedron (an "icosphere"). This is a much better base mesh for
 * sculpting than a UV sphere: triangles stay roughly equal-sized everywhere, including at the
 * poles, so brush strokes feel consistent no matter where you sculpt.
 *
 * UVs are a simple spherical (lat/long) unwrap. That means there's a visible seam where
 * longitude wraps from 2*pi back to 0 and pinching at the poles -- fine for a paint-brush
 * prototype, just don't expect a print-perfect texture unwrap.
 */
public class IcoSphereBuilder {

    public static EditableMesh build(double radius, int subdivisions, int textureSize) {
        List<Vec3> verts = new ArrayList<>();
        List<int[]> tris = new ArrayList<>();

        double t = (1.0 + Math.sqrt(5.0)) / 2.0;
        double[][] raw = {
                {-1, t, 0}, {1, t, 0}, {-1, -t, 0}, {1, -t, 0},
                {0, -1, t}, {0, 1, t}, {0, -1, -t}, {0, 1, -t},
                {t, 0, -1}, {t, 0, 1}, {-t, 0, -1}, {-t, 0, 1}
        };
        for (double[] r : raw) verts.add(new Vec3(r[0], r[1], r[2]).normalized());

        int[][] faceIdx = {
                {0, 11, 5}, {0, 5, 1}, {0, 1, 7}, {0, 7, 10}, {0, 10, 11},
                {1, 5, 9}, {5, 11, 4}, {11, 10, 2}, {10, 7, 6}, {7, 1, 8},
                {3, 9, 4}, {3, 4, 2}, {3, 2, 6}, {3, 6, 8}, {3, 8, 9},
                {4, 9, 5}, {2, 4, 11}, {6, 2, 10}, {8, 6, 7}, {9, 8, 1}
        };
        for (int[] f : faceIdx) tris.add(new int[]{f[0], f[1], f[2]});

        Map<Long, Integer> midpointCache = new HashMap<>();
        for (int s = 0; s < subdivisions; s++) {
            List<int[]> next = new ArrayList<>(tris.size() * 4);
            midpointCache.clear();
            for (int[] f : tris) {
                int a = f[0], b = f[1], c = f[2];
                int ab = midpoint(a, b, verts, midpointCache);
                int bc = midpoint(b, c, verts, midpointCache);
                int ca = midpoint(c, a, verts, midpointCache);
                next.add(new int[]{a, ab, ca});
                next.add(new int[]{b, bc, ab});
                next.add(new int[]{c, ca, bc});
                next.add(new int[]{ab, bc, ca});
            }
            tris = next;
        }

        // scale to requested radius
        List<Vec3> positions = new ArrayList<>(verts.size());
        List<double[]> uvs = new ArrayList<>(verts.size());
        for (Vec3 v : verts) {
            positions.add(v.scale(radius));
            double u = 0.5 + Math.atan2(v.z, v.x) / (2 * Math.PI);
            double vv = 0.5 - Math.asin(clamp(v.y, -1, 1)) / Math.PI;
            uvs.add(new double[]{u, vv});
        }

        return new EditableMesh(positions, uvs, tris, textureSize);
    }

    private static double clamp(double x, double lo, double hi) { return Math.max(lo, Math.min(hi, x)); }

    private static int midpoint(int i1, int i2, List<Vec3> verts, Map<Long, Integer> cache) {
        long key = i1 < i2 ? ((long) i1 << 32) | (i2 & 0xffffffffL) : ((long) i2 << 32) | (i1 & 0xffffffffL);
        Integer existing = cache.get(key);
        if (existing != null) return existing;

        Vec3 a = verts.get(i1);
        Vec3 b = verts.get(i2);
        Vec3 mid = new Vec3((a.x + b.x) / 2, (a.y + b.y) / 2, (a.z + b.z) / 2).normalized();
        verts.add(mid);
        int idx = verts.size() - 1;
        cache.put(key, idx);
        return idx;
    }
}
