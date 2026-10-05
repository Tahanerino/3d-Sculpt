package com.tahanerino.sculpt3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Classic O(n^2) ear-clipping triangulation for a simple (non-self-intersecting) 2D polygon.
 * Good enough for freehand-drawn shapes, which are small (tens of points, not thousands).
 *
 * Input points are (x, z) pairs -- everything drawn freehand happens on the ground plane,
 * so y is constant and irrelevant to triangulation.
 *
 * Output: triangles as index triples into the *original* points list, in CCW order.
 */
public class Triangulator {

    public static List<int[]> triangulate(List<double[]> points2d) {
        int n = points2d.size();
        List<int[]> result = new ArrayList<>();
        if (n < 3) return result;

        List<Integer> idx = new ArrayList<>(n);
        for (int i = 0; i < n; i++) idx.add(i);

        if (signedArea(points2d, idx) < 0) {
            // ear clipping below assumes CCW winding; flip traversal order if we got CW input
            List<Integer> reversed = new ArrayList<>(idx);
            java.util.Collections.reverse(reversed);
            idx = reversed;
        }

        int guard = 0;
        int maxIterations = n * n + 8; // safety net against degenerate/self-intersecting input
        while (idx.size() > 3 && guard++ < maxIterations) {
            boolean clippedAnEar = false;
            int m = idx.size();
            for (int i = 0; i < m; i++) {
                int prev = idx.get((i - 1 + m) % m);
                int curr = idx.get(i);
                int next = idx.get((i + 1) % m);

                if (!isConvex(points2d, prev, curr, next)) continue;
                if (anyPointInside(points2d, idx, prev, curr, next)) continue;

                result.add(new int[]{prev, curr, next});
                idx.remove(i);
                clippedAnEar = true;
                break;
            }
            if (!clippedAnEar) break; // degenerate polygon -- bail rather than infinite loop
        }
        if (idx.size() == 3) {
            result.add(new int[]{idx.get(0), idx.get(1), idx.get(2)});
        }
        return result;
    }

    private static double signedArea(List<double[]> pts, List<Integer> idx) {
        double area = 0;
        int m = idx.size();
        for (int i = 0; i < m; i++) {
            double[] a = pts.get(idx.get(i));
            double[] b = pts.get(idx.get((i + 1) % m));
            area += a[0] * b[1] - b[0] * a[1];
        }
        return area / 2.0;
    }

    private static boolean isConvex(List<double[]> pts, int prev, int curr, int next) {
        double[] a = pts.get(prev), b = pts.get(curr), c = pts.get(next);
        double cross = (b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]);
        return cross > 1e-9;
    }

    private static boolean anyPointInside(List<double[]> pts, List<Integer> idx, int a, int b, int c) {
        for (int candidate : idx) {
            if (candidate == a || candidate == b || candidate == c) continue;
            if (pointInTriangle(pts.get(candidate), pts.get(a), pts.get(b), pts.get(c))) return true;
        }
        return false;
    }

    private static boolean pointInTriangle(double[] p, double[] a, double[] b, double[] c) {
        double d1 = cross(p, a, b);
        double d2 = cross(p, b, c);
        double d3 = cross(p, c, a);
        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    private static double cross(double[] p, double[] a, double[] b) {
        return (a[0] - p[0]) * (b[1] - p[1]) - (a[1] - p[1]) * (b[0] - p[0]);
    }
}
