package com.tahanerino.sculpt3d;

import javafx.scene.Group;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.shape.DrawMode;
import javafx.scene.shape.MeshView;
import javafx.scene.shape.TriangleMesh;
import javafx.scene.shape.VertexFormat;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Scale;
import javafx.scene.transform.Translate;

import java.util.ArrayList;
import java.util.List;

/**
 * A sculptable, paintable triangle mesh.
 *
 * Positions/UVs/faces are kept in plain Java lists so sculpt brushes can read & write vertices
 * directly. JavaFX's own TriangleMesh is treated as a render target we resync after edits --
 * {@link #pushPositionsToGpuBuffer()} is the cheap path used every sculpt drag frame (geometry
 * only, no topology change); {@link #rebuildAll()} is the expensive path used once, right after
 * construction (or if topology ever changes, which extrude handles by building a brand new mesh
 * instead of mutating an existing one).
 *
 * Vertex normals here are *our own* approximation (area-weighted-ish average of adjacent face
 * normals) used only to know which way is "out" for sculpt push/pull -- they are NOT fed to
 * JavaFX for shading. Shading normals are computed by the renderer itself from geometry, via
 * face smoothing groups (see {@link #rebuildAll()}).
 */
public class EditableMesh {

    /** Display name for the object list / status bar. App assigns something like "Sphere 1". */
    public String label = "Object";

    public final List<Vec3> positions;
    public final List<double[]> uvs;      // one [u, v] per vertex, shares index with positions
    public final List<int[]> faces;       // each is {v0, v1, v2}

    private final int textureSize;
    private final WritableImage texture;
    private final PhongMaterial material;

    private final TriangleMesh triMesh = new TriangleMesh(VertexFormat.POINT_TEXCOORD);
    private final MeshView view = new MeshView(triMesh);

    // Selection highlight: a second MeshView sharing the SAME TriangleMesh (so sculpt edits show
    // up on it automatically, no extra sync needed), drawn as wireframe, hidden unless selected.
    // It's mouse-transparent so it never steals picks away from `view`.
    private final MeshView outlineView = new MeshView(triMesh);

    // Per-object transform, applied to a Group wrapping both views. Order in getTransforms()
    // matters: JavaFX applies the LAST entry to the point FIRST, so listing
    // [translate, rotateZ, rotateY, rotateX, scale] means a point is scaled, then rotated
    // (X then Y then Z), then translated into place -- standard TRS.
    //
    // Because `view` itself carries no transform of its own, PickResult.getIntersectedPoint()
    // (which JavaFX defines as "local coordinate of the picked node") comes back in the same
    // untransformed space as `positions` no matter how this object has been moved/rotated/scaled
    // -- sculpt and paint logic needs zero changes to work correctly on a transformed object.
    public final Translate translate = new Translate(0, 0, 0);
    public final Rotate rotateX = new Rotate(0, Rotate.X_AXIS);
    public final Rotate rotateY = new Rotate(0, Rotate.Y_AXIS);
    public final Rotate rotateZ = new Rotate(0, Rotate.Z_AXIS);
    public final Scale scale = new Scale(1, 1, 1);
    private final Group node = new Group(view, outlineView);

    /** faces touching each vertex, indices into {@link #faces} */
    private List<List<Integer>> vertexFaces;
    /** vertices directly edge-connected to each vertex, indices into {@link #positions} */
    private List<List<Integer>> vertexNeighbors;

    public EditableMesh(List<Vec3> positions, List<double[]> uvs, List<int[]> faces, int textureSize) {
        this.positions = positions;
        this.uvs = uvs;
        this.faces = faces;
        this.textureSize = textureSize;
        this.texture = new WritableImage(textureSize, textureSize);
        this.material = new PhongMaterial(Color.web("#b8b0a4"));
        this.material.setSpecularColor(Color.web("#2a2a2a"));
        this.material.setDiffuseMap(texture);
        view.setMaterial(material);
        view.setCullFace(javafx.scene.shape.CullFace.BACK);

        outlineView.setDrawMode(DrawMode.LINE);
        outlineView.setCullFace(javafx.scene.shape.CullFace.NONE);
        outlineView.setMaterial(new PhongMaterial(Color.web("#ffd23f")));
        outlineView.setMouseTransparent(true);
        outlineView.setVisible(false);

        node.getTransforms().addAll(translate, rotateZ, rotateY, rotateX, scale);

        fillTexture(Color.web("#c9c1b4"));
        buildAdjacency();
        rebuildAll();
    }

    public MeshView getView() { return view; }
    public Group getNode() { return node; }
    public WritableImage getTexture() { return texture; }
    public int getTextureSize() { return textureSize; }
    public int vertexCount() { return positions.size(); }

    public void setSelected(boolean selected) { outlineView.setVisible(selected); }

    public void resetTransform() {
        translate.setX(0); translate.setY(0); translate.setZ(0);
        rotateX.setAngle(0); rotateY.setAngle(0); rotateZ.setAngle(0);
        scale.setX(1); scale.setY(1); scale.setZ(1);
    }

    @Override
    public String toString() { return label + "  (" + vertexCount() + " verts)"; }

    // ---------------------------------------------------------------- undo/redo snapshots

    public Vec3[] snapshotPositions() {
        Vec3[] snap = new Vec3[positions.size()];
        for (int i = 0; i < snap.length; i++) snap[i] = new Vec3(positions.get(i));
        return snap;
    }

    public void restorePositions(Vec3[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) positions.get(i).setFrom(snapshot[i]);
        pushPositionsToGpuBuffer();
    }

    public int[] snapshotTexturePixels() {
        int[] buf = new int[textureSize * textureSize];
        texture.getPixelReader().getPixels(0, 0, textureSize, textureSize,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), buf, 0, textureSize);
        return buf;
    }

    public void restoreTexturePixels(int[] pixels) {
        texture.getPixelWriter().setPixels(0, 0, textureSize, textureSize,
                javafx.scene.image.PixelFormat.getIntArgbInstance(), pixels, 0, textureSize);
    }

    public double[] snapshotTransform() {
        return new double[]{
                translate.getX(), translate.getY(), translate.getZ(),
                rotateX.getAngle(), rotateY.getAngle(), rotateZ.getAngle(),
                scale.getX(), scale.getY(), scale.getZ()
        };
    }

    public void restoreTransform(double[] t) {
        translate.setX(t[0]); translate.setY(t[1]); translate.setZ(t[2]);
        rotateX.setAngle(t[3]); rotateY.setAngle(t[4]); rotateZ.setAngle(t[5]);
        scale.setX(t[6]); scale.setY(t[7]); scale.setZ(t[8]);
    }

    // ---------------------------------------------------------------- adjacency / normals

    public void buildAdjacency() {
        int n = positions.size();
        vertexFaces = new ArrayList<>(n);
        List<java.util.LinkedHashSet<Integer>> neighborSets = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            vertexFaces.add(new ArrayList<>());
            neighborSets.add(new java.util.LinkedHashSet<>());
        }
        for (int fi = 0; fi < faces.size(); fi++) {
            int[] f = faces.get(fi);
            for (int k = 0; k < 3; k++) {
                int vi = f[k];
                vertexFaces.get(vi).add(fi);
                for (int j = 0; j < 3; j++) {
                    if (j == k) continue;
                    neighborSets.get(vi).add(f[j]); // Set dedupes in O(1) instead of List.contains' O(degree)
                }
            }
        }
        vertexNeighbors = new ArrayList<>(n);
        for (java.util.LinkedHashSet<Integer> s : neighborSets) vertexNeighbors.add(new ArrayList<>(s));
    }

    public List<Integer> neighborsOf(int vertexIndex) { return vertexNeighbors.get(vertexIndex); }

    private Vec3 faceNormal(int[] f) {
        Vec3 a = positions.get(f[0]);
        Vec3 b = positions.get(f[1]);
        Vec3 c = positions.get(f[2]);
        return b.sub(a).cross(c.sub(a)).normalized();
    }

    /** Our own approximate vertex normal -- average of the normals of adjacent faces. */
    public Vec3 vertexNormal(int vi) {
        Vec3 sum = new Vec3(0, 0, 0);
        for (int fi : vertexFaces.get(vi)) {
            sum.addInPlace(faceNormal(faces.get(fi)));
        }
        return sum.normalized();
    }

    /**
     * Same as {@link #vertexNormal(int)} but computed against a snapshot array instead of the
     * live (possibly mid-edit) positions. SculptTool uses this to derive a single stable brush
     * direction from the mesh as it was *before* the current dab, instead of from geometry that's
     * actively being deformed underneath it -- that decoupling is what keeps push/pull from
     * spiraling into jittery, self-amplifying noise on a dense mesh.
     */
    public Vec3 vertexNormalFromSnapshot(int vi, Vec3[] snapshot) {
        Vec3 sum = new Vec3(0, 0, 0);
        for (int fi : vertexFaces.get(vi)) {
            int[] f = faces.get(fi);
            Vec3 a = snapshot[f[0]], b = snapshot[f[1]], c = snapshot[f[2]];
            sum.addInPlace(b.sub(a).cross(c.sub(a)));
        }
        return sum.normalized();
    }

    // ---------------------------------------------------------------- geometry sync

    /** Full resync: points, texcoords, faces, smoothing groups. Call once after construction. */
    public void rebuildAll() {
        float[] pts = new float[positions.size() * 3];
        for (int i = 0; i < positions.size(); i++) {
            Vec3 p = positions.get(i);
            pts[i * 3] = (float) p.x;
            pts[i * 3 + 1] = (float) p.y;
            pts[i * 3 + 2] = (float) p.z;
        }
        float[] tex = new float[uvs.size() * 2];
        for (int i = 0; i < uvs.size(); i++) {
            double[] uv = uvs.get(i);
            tex[i * 2] = (float) uv[0];
            tex[i * 2 + 1] = (float) uv[1];
        }
        int[] faceArr = new int[faces.size() * 6];
        int[] smoothing = new int[faces.size()];
        for (int i = 0; i < faces.size(); i++) {
            int[] f = faces.get(i);
            faceArr[i * 6] = f[0];
            faceArr[i * 6 + 1] = f[0];
            faceArr[i * 6 + 2] = f[1];
            faceArr[i * 6 + 3] = f[1];
            faceArr[i * 6 + 4] = f[2];
            faceArr[i * 6 + 5] = f[2];
            smoothing[i] = 1; // one shared group => smooth shading across the whole mesh
        }

        triMesh.getPoints().setAll(pts);
        triMesh.getTexCoords().setAll(tex);
        triMesh.getFaces().setAll(faceArr);
        triMesh.getFaceSmoothingGroups().setAll(smoothing);
    }

    /** Cheap path for sculpting: geometry changed, topology (faces/UVs/vertex count) did not. */
    public void pushPositionsToGpuBuffer() {
        float[] pts = new float[positions.size() * 3];
        for (int i = 0; i < positions.size(); i++) {
            Vec3 p = positions.get(i);
            pts[i * 3] = (float) p.x;
            pts[i * 3 + 1] = (float) p.y;
            pts[i * 3 + 2] = (float) p.z;
        }
        triMesh.getPoints().set(0, pts, 0, pts.length);
    }

    // ---------------------------------------------------------------- texture painting

    public void fillTexture(Color color) {
        PixelWriter pw = texture.getPixelWriter();
        for (int y = 0; y < textureSize; y++) {
            for (int x = 0; x < textureSize; x++) {
                pw.setColor(x, y, color);
            }
        }
    }
}
