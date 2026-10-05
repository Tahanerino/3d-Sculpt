package com.tahanerino.sculpt3d;

import java.util.List;

/**
 * Push/Pull/Smooth sculpting.
 *
 * The previous version displaced every vertex along *its own* per-vertex normal, recomputed from
 * live (already-moving) geometry on every single dab. That's an unstable feedback loop: as soon
 * as the surface gets slightly uneven, neighboring vertices' normals start pointing in
 * increasingly different directions, so each dab pushes them further apart instead of together --
 * a few dozen dabs (one stroke, post-interpolation) and the surface turns into spiky noise.
 *
 * This version fixes that two ways:
 *   1. Every dab computes ONE brush direction (the average normal of the patch under the brush,
 *      taken from a snapshot of the geometry as it was before this dab) and moves every affected
 *      vertex along THAT shared direction, scaled only by per-vertex falloff. The whole patch
 *      moves together as a coherent bump/dent instead of each point drifting on its own.
 *   2. A small Laplacian "relax" term is blended in on every push/pull dab, nudging each touched
 *      vertex back toward the average of its neighbors. This is what actually keeps a recognizable
 *      shape under repeated sculpting instead of accumulating jitter -- same idea as the
 *      "shape preservation" / auto-smooth option in real sculpting tools.
 */
public class SculptTool {

    public enum Mode { PUSH, PULL, SMOOTH }

    public double radius = 1.2;
    public double strength = 0.35;
    /** 0 = pure displacement (can get noisy over many strokes), 1 = mostly relax/no sculpting.
     *  Blended in on every push/pull dab; keeps the surface coherent instead of spiky. */
    public double shapePreservation = 0.35;
    public Mode mode = Mode.PULL;

    /** One brush dab, centered at a world-space point (typically the mouse's pick point). */
    public void apply(EditableMesh mesh, Vec3 center) {
        dab(mesh, center);
        mesh.pushPositionsToGpuBuffer();
    }

    /**
     * A whole stroke between two points, sampled densely enough that fast mouse movement doesn't
     * leave gaps between drag events. Each sub-step is its own independent dab (fresh snapshot,
     * fresh brush direction), which is what keeps a fast stroke from being any less stable than a
     * slow one.
     */
    public void applyStroke(EditableMesh mesh, Vec3 from, Vec3 to) {
        double dist = from.distance(to);
        double step = Math.max(radius * 0.35, 0.02);
        int steps = Math.max(1, (int) Math.ceil(dist / step));
        for (int s = 1; s <= steps; s++) {
            double t = (double) s / steps;
            Vec3 p = from.add(to.sub(from).scale(t));
            dab(mesh, p);
        }
        mesh.pushPositionsToGpuBuffer();
    }

    private void dab(EditableMesh mesh, Vec3 center) {
        int n = mesh.vertexCount();
        Vec3[] snapshot = mesh.snapshotPositions(); // fixed reference state for this dab only

        Vec3 brushNormal = (mode == Mode.PUSH || mode == Mode.PULL)
                ? averageNormalNear(mesh, snapshot, center)
                : null;

        for (int i = 0; i < n; i++) {
            double dist = snapshot[i].distance(center);
            if (dist > radius) continue;
            double t = 1.0 - (dist / radius);
            double falloff = t * t * t * (t * (t * 6 - 15) + 10); // smootherstep -- soft brush edge
            Vec3 p = mesh.positions.get(i);

            switch (mode) {
                case PUSH -> p.addInPlace(brushNormal.scale(-strength * falloff));
                case PULL -> p.addInPlace(brushNormal.scale(strength * falloff));
                case SMOOTH -> {
                    List<Integer> nbrs = mesh.neighborsOf(i);
                    if (nbrs.isEmpty()) continue;
                    Vec3 avg = neighborAverage(nbrs, snapshot);
                    p.addInPlace(avg.sub(snapshot[i]).scale(falloff * 0.5));
                }
            }

            if (mode != Mode.SMOOTH && shapePreservation > 0) {
                List<Integer> nbrs = mesh.neighborsOf(i);
                if (!nbrs.isEmpty()) {
                    Vec3 avg = neighborAverage(nbrs, snapshot);
                    Vec3 towardAvg = avg.sub(snapshot[i]);
                    p.addInPlace(towardAvg.scale(falloff * shapePreservation * 0.5));
                }
            }
        }
    }

    private Vec3 neighborAverage(List<Integer> nbrs, Vec3[] snapshot) {
        Vec3 avg = new Vec3(0, 0, 0);
        for (int ni : nbrs) avg.addInPlace(snapshot[ni]);
        return avg.scale(1.0 / nbrs.size());
    }

    /** Average vertex normal of every vertex under the brush, from a fixed snapshot. This is the
     *  single direction the whole dab pushes/pulls along -- see class docs for why. */
    private Vec3 averageNormalNear(EditableMesh mesh, Vec3[] snapshot, Vec3 center) {
        Vec3 sum = new Vec3(0, 0, 0);
        int count = 0;
        int nearest = -1;
        double nearestDist = Double.MAX_VALUE;
        for (int i = 0; i < snapshot.length; i++) {
            double d = snapshot[i].distance(center);
            if (d < nearestDist) { nearestDist = d; nearest = i; }
            if (d <= radius) {
                sum.addInPlace(mesh.vertexNormalFromSnapshot(i, snapshot));
                count++;
            }
        }
        if (count == 0) return mesh.vertexNormalFromSnapshot(nearest, snapshot);
        return sum.normalized();
    }
}
