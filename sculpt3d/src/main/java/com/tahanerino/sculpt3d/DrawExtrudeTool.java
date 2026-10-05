package com.tahanerino.sculpt3d;

import javafx.geometry.Point3D;
import javafx.scene.Group;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Cylinder;
import javafx.scene.shape.Sphere;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.List;

/**
 * Freehand shape tool: click points on the ground plane to trace a polygon footprint, then
 * extrude it into a solid of the given height. The result is a brand new {@link EditableMesh}
 * -- fully sculptable and paintable afterward, same as the starting sphere.
 *
 * UV mapping is a single planar (x,z) projection shared by both caps and the walls. That's a
 * deliberate simplification: professional-grade unwrapping is a whole project on its own, and a
 * planar projection is enough to paint and eyeball-texture a freehand shape.
 */
public class DrawExtrudeTool {

    private final List<Vec3> points = new ArrayList<>();
    private final Group previewGroup = new Group();
    private static final PhongMaterial PREVIEW_MATERIAL = new PhongMaterial(Color.web("#3fb0ff"));

    public Group getPreviewGroup() { return previewGroup; }

    public boolean hasPoints() { return !points.isEmpty(); }

    public void addPoint(Point3D groundHit) {
        Vec3 p = new Vec3(groundHit.getX(), 0, groundHit.getZ());
        points.add(p);
        refreshPreview();
    }

    public void cancel() {
        points.clear();
        refreshPreview();
    }

    private void refreshPreview() {
        previewGroup.getChildren().clear();
        for (Vec3 p : points) {
            Sphere s = new Sphere(0.06);
            s.setMaterial(PREVIEW_MATERIAL);
            s.setTranslateX(p.x);
            s.setTranslateY(p.y);
            s.setTranslateZ(p.z);
            previewGroup.getChildren().add(s);
        }
        for (int i = 0; i + 1 < points.size(); i++) {
            previewGroup.getChildren().add(segment(points.get(i), points.get(i + 1)));
        }
        if (points.size() > 2) {
            // faint closing edge so it's clear where "finish" will seal the loop
            previewGroup.getChildren().add(segment(points.get(points.size() - 1), points.get(0)));
        }
    }

    private Cylinder segment(Vec3 a, Vec3 b) {
        Point3D origin = new Point3D(a.x, a.y, a.z);
        Point3D target = new Point3D(b.x, b.y, b.z);
        Point3D yAxis = new Point3D(0, 1, 0);
        Point3D diff = target.subtract(origin);
        double height = Math.max(diff.magnitude(), 1e-6);
        Point3D mid = target.midpoint(origin);

        Cylinder line = new Cylinder(0.015, height);
        line.setMaterial(PREVIEW_MATERIAL);

        Point3D axis = diff.crossProduct(yAxis);
        double angle = Math.acos(clamp(diff.normalize().dotProduct(yAxis), -1, 1));
        Translate move = new Translate(mid.getX(), mid.getY(), mid.getZ());
        if (axis.magnitude() < 1e-6) {
            line.getTransforms().add(move);
        } else {
            Rotate rotate = new Rotate(-Math.toDegrees(angle), axis);
            line.getTransforms().addAll(move, rotate);
        }
        return line;
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    /** Builds the extruded solid and clears the in-progress drawing. Returns null if too few points. */
    public EditableMesh finishAndExtrude(double height, int textureSize) {
        int n = points.size();
        if (n < 3) return null;

        List<double[]> points2d = new ArrayList<>(n);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (Vec3 p : points) {
            points2d.add(new double[]{p.x, p.z});
            minX = Math.min(minX, p.x); maxX = Math.max(maxX, p.x);
            minZ = Math.min(minZ, p.z); maxZ = Math.max(maxZ, p.z);
        }
        List<int[]> capTris = Triangulator.triangulate(points2d);
        if (capTris.isEmpty()) { cancel(); return null; }

        List<Vec3> positions = new ArrayList<>(2 * n);
        List<double[]> uvs = new ArrayList<>(2 * n);
        double spanX = Math.max(1e-6, maxX - minX);
        double spanZ = Math.max(1e-6, maxZ - minZ);
        for (Vec3 p : points) {
            positions.add(new Vec3(p.x, 0, p.z));
            uvs.add(new double[]{(p.x - minX) / spanX, (p.z - minZ) / spanZ});
        }
        for (Vec3 p : points) {
            positions.add(new Vec3(p.x, height, p.z));
            uvs.add(new double[]{(p.x - minX) / spanX, (p.z - minZ) / spanZ});
        }

        List<int[]> faces = new ArrayList<>();
        for (int[] t : capTris) faces.add(new int[]{t[0], t[1], t[2]});             // bottom cap
        for (int[] t : capTris) faces.add(new int[]{n + t[0], n + t[1], n + t[2]}); // top cap
        for (int i = 0; i < n; i++) {
            int i2 = (i + 1) % n;
            faces.add(new int[]{i, i2, n + i2});
            faces.add(new int[]{i, n + i2, n + i});
        }

        // Force every face normal to point away from the solid's centroid, regardless of
        // whichever winding the triangulator/wall-building happened to produce.
        Vec3 centroid = new Vec3(0, 0, 0);
        for (Vec3 p : positions) centroid.addInPlace(p);
        centroid = centroid.scale(1.0 / positions.size());

        for (int[] f : faces) {
            Vec3 a = positions.get(f[0]), b = positions.get(f[1]), c = positions.get(f[2]);
            Vec3 normal = b.sub(a).cross(c.sub(a));
            Vec3 faceCenter = a.add(b).add(c).scale(1.0 / 3.0);
            if (normal.dot(faceCenter.sub(centroid)) < 0) {
                int tmp = f[1]; f[1] = f[2]; f[2] = tmp;
            }
        }

        // Extrusion starts out low-poly (flat caps + flat wall quads) -- subdivide so there's
        // enough vertex density for sculpt brushes to deform it smoothly afterward. Winding is
        // preserved by subdivision (each sub-triangle keeps its parent's orientation), so the
        // outward-normal fix-up above still holds after this.
        MeshSubdivider.Result subdivided = MeshSubdivider.subdivide(positions, uvs, faces, 3);

        cancel();
        return new EditableMesh(subdivided.positions, subdivided.uvs, subdivided.faces, textureSize);
    }
}
