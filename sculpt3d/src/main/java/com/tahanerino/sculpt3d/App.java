package com.tahanerino.sculpt3d;

import javafx.application.Application;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Point3D;
import javafx.geometry.Pos;
import javafx.scene.AmbientLight;
import javafx.scene.Group;
import javafx.scene.PointLight;
import javafx.scene.Scene;
import javafx.scene.SubScene;
import javafx.scene.SceneAntialiasing;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.paint.PhongMaterial;
import javafx.scene.shape.Box;
import javafx.scene.shape.CullFace;
import javafx.scene.shape.MeshView;
import javafx.stage.Stage;

import java.util.HashMap;
import java.util.Map;

/**
 * A small, single-window sculpting/modeling tool:
 *   - Sculpt: push / pull / smooth brush on the current mesh (shape-preserving -- see SculptTool)
 *   - Draw & Extrude: click points on the ground to trace a freehand polygon, then extrude it
 *     into a new solid you can sculpt and paint like anything else
 *   - Paint: brush strokes (soft/hard round, square) onto the object's texture
 *   - Fill: recolor an entire object's texture in one click
 *   - Transform: pick an object (click it, or pick it from the object list) and
 *     translate/rotate/scale it per axis
 *
 * Navigation: right-drag orbits, shift+right-drag (or middle-drag) pans, scroll zooms
 * (scroll up = in, scroll down = out).
 * Left click/drag is always the active tool. Ctrl/Cmd+Z undoes, Ctrl/Cmd+Shift+Z (or Ctrl+Y)
 * redoes -- covers sculpt strokes, paint strokes, fills, transforms, and adding new objects.
 */
public class App extends Application {

    private enum Mode { SCULPT, DRAW, PAINT, FILL, TRANSFORM }

    private static final int TEXTURE_SIZE = 512;
    private static final int SPHERE_SUBDIVISIONS = 5; // ~10k verts -- smooth enough to sculpt cleanly

    private final Group meshRoot = new Group();
    private final Map<MeshView, EditableMesh> meshByView = new HashMap<>();
    private final ObservableList<EditableMesh> allMeshes = FXCollections.observableArrayList();
    private final CameraRig cameraRig = new CameraRig();
    private final SculptTool sculptTool = new SculptTool();
    private final PaintTool paintTool = new PaintTool();
    private final DrawExtrudeTool drawTool = new DrawExtrudeTool();
    private final UndoManager undoManager = new UndoManager();

    private Box groundBox;
    private Mode mode = Mode.SCULPT;
    private double lastX, lastY;
    private Label statusLabel;
    private int sphereCounter = 0;
    private int shapeCounter = 0;

    // Sculpt-stroke continuity + undo: remembers the last dab point so drag events get connected
    // by interpolated dabs (see SculptTool.applyStroke), and the geometry as it was before the
    // stroke started so the whole stroke becomes one undo step.
    private EditableMesh strokeMesh;
    private Vec3 strokeLastPoint;
    private Vec3[] strokeUndoBefore;

    // Same idea for paint strokes, against the texture instead of vertex positions.
    private EditableMesh paintStrokeMesh;
    private int[] paintUndoBefore;

    // Transform panel state
    private EditableMesh selectedMesh;
    private Label selectionLabel;
    private ListView<EditableMesh> objectList;
    private Slider tx, ty, tz, rx, ry, rz, sx, sy, sz;
    private boolean syncingSliders = false;
    private EditableMesh pendingTransformMesh;
    private double[] pendingTransformBefore;

    @Override
    public void start(Stage stage) {
        Group root3d = new Group();
        root3d.getChildren().add(cameraRig.node);
        root3d.getChildren().add(buildLights());
        root3d.getChildren().add(buildGround());
        root3d.getChildren().add(meshRoot);
        root3d.getChildren().add(drawTool.getPreviewGroup());

        spawnSphere(); // start with something sculptable on screen

        SubScene subScene = new SubScene(root3d, 1024, 720, true, SceneAntialiasing.BALANCED);
        subScene.setFill(Color.web("#1c1e22"));
        subScene.setCamera(cameraRig.camera);

        StackPane viewportWrapper = new StackPane(subScene);
        subScene.widthProperty().bind(viewportWrapper.widthProperty());
        subScene.heightProperty().bind(viewportWrapper.heightProperty());

        wireMouseAndScroll(subScene);

        BorderPane layout = new BorderPane();
        layout.setCenter(viewportWrapper);
        layout.setTop(buildModeBar());
        layout.setRight(buildSettingsPanel());
        statusLabel = new Label();
        statusLabel.setPadding(new Insets(6, 10, 6, 10));
        layout.setBottom(statusLabel);
        updateStatus();

        Scene scene = new Scene(layout, 1360, 800);
        wireKeyboardShortcuts(scene);
        stage.setTitle("Sculpt3D");
        stage.setScene(scene);
        stage.show();
    }

    // ---------------------------------------------------------------- scene setup

    private Group buildLights() {
        Group g = new Group();
        AmbientLight ambient = new AmbientLight(Color.color(0.35, 0.35, 0.38));
        PointLight key = new PointLight(Color.color(0.9, 0.88, 0.85));
        key.setTranslateX(-8); key.setTranslateY(-12); key.setTranslateZ(-10);
        PointLight fill = new PointLight(Color.color(0.35, 0.4, 0.5));
        fill.setTranslateX(10); fill.setTranslateY(4); fill.setTranslateZ(8);
        g.getChildren().addAll(ambient, key, fill);
        return g;
    }

    /**
     * Invisible ground plane. It still needs to exist and stay pickable -- Draw & Extrude clicks
     * against it to know where on the XZ plane you clicked -- it just shouldn't be a big opaque
     * slab cluttering the view, so its material is fully transparent instead of hidden (a
     * genuinely hidden/invisible node stops being pickable in JavaFX).
     */
    private Box buildGround() {
        Box ground = new Box(60, 0.02, 60);
        ground.setTranslateY(0.01);
        PhongMaterial mat = new PhongMaterial(Color.TRANSPARENT);
        mat.setSpecularColor(Color.TRANSPARENT);
        ground.setMaterial(mat);
        ground.setCullFace(CullFace.BACK);
        this.groundBox = ground;
        return ground;
    }

    private void spawnSphere() {
        EditableMesh mesh = IcoSphereBuilder.build(2.0, SPHERE_SUBDIVISIONS, TEXTURE_SIZE);
        for (Vec3 p : mesh.positions) p.y += 2.0; // sit on top of the ground plane
        mesh.rebuildAll();
        mesh.label = "Sphere " + (++sphereCounter);
        registerNewMesh(mesh);
    }

    /** Raw scene-graph bookkeeping, shared by "just created it" and "redo-ing a creation". */
    private void addMeshToScene(EditableMesh mesh) {
        meshByView.put(mesh.getView(), mesh);
        meshRoot.getChildren().add(mesh.getNode());
        if (!allMeshes.contains(mesh)) allMeshes.add(mesh);
    }

    private void removeMeshFromScene(EditableMesh mesh) {
        meshByView.remove(mesh.getView());
        meshRoot.getChildren().remove(mesh.getNode());
        allMeshes.remove(mesh);
        if (selectedMesh == mesh) selectMesh(null);
    }

    /** A brand new object the user just created -- records an undo step for it. */
    private void registerNewMesh(EditableMesh mesh) {
        addMeshToScene(mesh);
        undoManager.push(new UndoManager.Action() {
            @Override public void undo() { removeMeshFromScene(mesh); }
            @Override public void redo() { addMeshToScene(mesh); }
        });
    }

    // ---------------------------------------------------------------- input

    private void wireMouseAndScroll(SubScene subScene) {
        subScene.setOnMousePressed(this::onPressed);
        subScene.setOnMouseDragged(this::onDragged);
        subScene.setOnMouseReleased(e -> {
            finalizeSculptStroke();
            finalizePaintStroke();
            strokeMesh = null;
            strokeLastPoint = null;
        });
        subScene.setOnScroll(this::onScroll);
    }

    private void wireKeyboardShortcuts(Scene scene) {
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN),
                () -> { undoManager.undo(); syncTransformSliders(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Z, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
                () -> { undoManager.redo(); syncTransformSliders(); });
        scene.getAccelerators().put(
                new KeyCodeCombination(KeyCode.Y, KeyCombination.SHORTCUT_DOWN),
                () -> { undoManager.redo(); syncTransformSliders(); });
    }

    private void onPressed(MouseEvent e) {
        lastX = e.getSceneX();
        lastY = e.getSceneY();
        if (e.getButton() == MouseButton.PRIMARY) {
            strokeMesh = null;
            strokeLastPoint = null;
            paintStrokeMesh = null;
            handleToolAction(e);
        }
    }

    private void onDragged(MouseEvent e) {
        double dx = e.getSceneX() - lastX;
        double dy = e.getSceneY() - lastY;
        lastX = e.getSceneX();
        lastY = e.getSceneY();

        if (e.getButton() == MouseButton.SECONDARY) {
            if (e.isShiftDown()) cameraRig.pan(dx, dy); else cameraRig.orbit(dx, dy);
        } else if (e.getButton() == MouseButton.MIDDLE) {
            cameraRig.pan(dx, dy);
        } else if (e.getButton() == MouseButton.PRIMARY) {
            handleToolAction(e);
        }
    }

    /** Scroll up (deltaY > 0) zooms IN, scroll down zooms OUT -- standard convention. */
    private void onScroll(ScrollEvent e) {
        cameraRig.zoom(e.getDeltaY() > 0 ? 1 : -1);
    }

    private void handleToolAction(MouseEvent e) {
        PickResult pick = e.getPickResult();
        if (pick == null || pick.getIntersectedNode() == null) return;

        switch (mode) {
            case DRAW -> {
                if (pick.getIntersectedNode() == groundBox) {
                    Point3D hit = pick.getIntersectedPoint();
                    drawTool.addPoint(new Point3D(hit.getX(), 0, hit.getZ()));
                    updateStatus();
                }
            }
            case SCULPT -> {
                EditableMesh mesh = meshByView.get(pick.getIntersectedNode());
                if (mesh != null) {
                    if (strokeMesh != mesh) strokeUndoBefore = mesh.snapshotPositions(); // stroke start
                    Point3D p = pick.getIntersectedPoint();
                    Vec3 current = new Vec3(p.getX(), p.getY(), p.getZ());
                    if (strokeMesh == mesh && strokeLastPoint != null) {
                        sculptTool.applyStroke(mesh, strokeLastPoint, current);
                    } else {
                        sculptTool.apply(mesh, current);
                    }
                    strokeMesh = mesh;
                    strokeLastPoint = current;
                }
            }
            case PAINT -> {
                EditableMesh mesh = meshByView.get(pick.getIntersectedNode());
                if (mesh != null && pick.getIntersectedTexCoord() != null) {
                    if (paintStrokeMesh != mesh) {
                        paintUndoBefore = mesh.snapshotTexturePixels(); // stroke start
                        paintStrokeMesh = mesh;
                    }
                    paintTool.paintAt(mesh, pick.getIntersectedTexCoord().getX(), pick.getIntersectedTexCoord().getY());
                }
            }
            case FILL -> {
                EditableMesh mesh = meshByView.get(pick.getIntersectedNode());
                if (mesh != null) {
                    int[] before = mesh.snapshotTexturePixels();
                    paintTool.fillAll(mesh);
                    int[] after = mesh.snapshotTexturePixels();
                    undoManager.push(new UndoManager.Action() {
                        @Override public void undo() { mesh.restoreTexturePixels(before); }
                        @Override public void redo() { mesh.restoreTexturePixels(after); }
                    });
                }
            }
            case TRANSFORM -> {
                if (e.getEventType() != MouseEvent.MOUSE_PRESSED) return; // select on click only
                EditableMesh mesh = meshByView.get(pick.getIntersectedNode());
                selectMesh(mesh); // null if they clicked the ground / nothing registered
            }
        }
    }

    private void finalizeSculptStroke() {
        if (strokeMesh != null && strokeUndoBefore != null) {
            EditableMesh mesh = strokeMesh;
            Vec3[] before = strokeUndoBefore;
            Vec3[] after = mesh.snapshotPositions();
            undoManager.push(new UndoManager.Action() {
                @Override public void undo() { mesh.restorePositions(before); }
                @Override public void redo() { mesh.restorePositions(after); }
            });
        }
        strokeUndoBefore = null;
    }

    private void finalizePaintStroke() {
        if (paintStrokeMesh != null && paintUndoBefore != null) {
            EditableMesh mesh = paintStrokeMesh;
            int[] before = paintUndoBefore;
            int[] after = mesh.snapshotTexturePixels();
            undoManager.push(new UndoManager.Action() {
                @Override public void undo() { mesh.restoreTexturePixels(before); }
                @Override public void redo() { mesh.restoreTexturePixels(after); }
            });
        }
        paintStrokeMesh = null;
        paintUndoBefore = null;
    }

    // ---------------------------------------------------------------- selection / transform

    private void selectMesh(EditableMesh mesh) {
        if (selectedMesh != null) selectedMesh.setSelected(false);
        selectedMesh = mesh;
        if (selectedMesh != null) selectedMesh.setSelected(true);
        if (objectList != null && objectList.getSelectionModel().getSelectedItem() != mesh) {
            if (mesh == null) objectList.getSelectionModel().clearSelection();
            else objectList.getSelectionModel().select(mesh);
        }
        syncTransformSliders();
        updateStatus();
    }

    private void syncTransformSliders() {
        syncingSliders = true;
        if (selectedMesh != null) {
            tx.setValue(selectedMesh.translate.getX());
            ty.setValue(selectedMesh.translate.getY());
            tz.setValue(selectedMesh.translate.getZ());
            rx.setValue(selectedMesh.rotateX.getAngle());
            ry.setValue(selectedMesh.rotateY.getAngle());
            rz.setValue(selectedMesh.rotateZ.getAngle());
            sx.setValue(selectedMesh.scale.getX());
            sy.setValue(selectedMesh.scale.getY());
            sz.setValue(selectedMesh.scale.getZ());
            selectionLabel.setText("Selected: " + selectedMesh.label);
        } else {
            tx.setValue(0); ty.setValue(0); tz.setValue(0);
            rx.setValue(0); ry.setValue(0); rz.setValue(0);
            sx.setValue(1); sy.setValue(1); sz.setValue(1);
            selectionLabel.setText("No object selected");
        }
        syncingSliders = false;
    }

    private void beginTransformEdit() {
        if (selectedMesh == null) return;
        pendingTransformMesh = selectedMesh;
        pendingTransformBefore = selectedMesh.snapshotTransform();
    }

    private void endTransformEdit() {
        if (pendingTransformMesh == null || pendingTransformBefore == null) return;
        EditableMesh mesh = pendingTransformMesh;
        double[] before = pendingTransformBefore;
        double[] after = mesh.snapshotTransform();
        undoManager.push(new UndoManager.Action() {
            @Override public void undo() { mesh.restoreTransform(before); if (selectedMesh == mesh) syncTransformSliders(); }
            @Override public void redo() { mesh.restoreTransform(after); if (selectedMesh == mesh) syncTransformSliders(); }
        });
        pendingTransformMesh = null;
        pendingTransformBefore = null;
    }

    // ---------------------------------------------------------------- UI

    private HBox buildModeBar() {
        ToggleGroup group = new ToggleGroup();
        ToggleButton sculptBtn = modeButton("Sculpt", group, Mode.SCULPT);
        ToggleButton drawBtn = modeButton("Draw && Extrude", group, Mode.DRAW);
        ToggleButton paintBtn = modeButton("Paint", group, Mode.PAINT);
        ToggleButton fillBtn = modeButton("Fill", group, Mode.FILL);
        ToggleButton transformBtn = modeButton("Transform", group, Mode.TRANSFORM);
        sculptBtn.setSelected(true);

        Button addSphere = new Button("Add Sphere");
        addSphere.setOnAction(e -> spawnSphere());

        Button undoBtn = new Button("Undo (Ctrl+Z)");
        undoBtn.setOnAction(e -> { undoManager.undo(); syncTransformSliders(); });
        Button redoBtn = new Button("Redo (Ctrl+Shift+Z)");
        redoBtn.setOnAction(e -> { undoManager.redo(); syncTransformSliders(); });

        HBox bar = new HBox(8, sculptBtn, drawBtn, paintBtn, fillBtn, transformBtn,
                new Separator(), addSphere, new Separator(), undoBtn, redoBtn);
        bar.setPadding(new Insets(10));
        bar.setAlignment(Pos.CENTER_LEFT);
        return bar;
    }

    private ToggleButton modeButton(String text, ToggleGroup group, Mode m) {
        ToggleButton b = new ToggleButton(text);
        b.setToggleGroup(group);
        b.setOnAction(e -> { mode = m; updateStatus(); });
        return b;
    }

    private VBox buildSettingsPanel() {
        VBox panel = new VBox(14);
        panel.setPadding(new Insets(10));
        panel.setPrefWidth(280);

        // --- Sculpt settings ---
        ToggleGroup sculptModeGroup = new ToggleGroup();
        RadioButton pull = radio("Pull (build up)", sculptModeGroup, true);
        RadioButton push = radio("Push (carve in)", sculptModeGroup, false);
        RadioButton smooth = radio("Smooth", sculptModeGroup, false);
        pull.setOnAction(e -> sculptTool.mode = SculptTool.Mode.PULL);
        push.setOnAction(e -> sculptTool.mode = SculptTool.Mode.PUSH);
        smooth.setOnAction(e -> sculptTool.mode = SculptTool.Mode.SMOOTH);

        Slider radiusSlider = labeledSlider(0.1, 4.0, sculptTool.radius, v -> sculptTool.radius = v);
        Slider strengthSlider = labeledSlider(0.01, 1.0, sculptTool.strength, v -> sculptTool.strength = v);
        Slider preserveSlider = labeledSlider(0.0, 0.9, sculptTool.shapePreservation, v -> sculptTool.shapePreservation = v);

        VBox sculptBox = section("Sculpt", pull, push, smooth,
                new Label("Brush radius"), radiusSlider,
                new Label("Strength"), strengthSlider,
                new Label("Shape preservation (higher = calmer, less spiky)"), preserveSlider);

        // --- Draw & Extrude settings ---
        Slider heightSlider = new Slider(0.2, 6.0, 1.5);
        heightSlider.setShowTickLabels(true);
        Button finish = new Button("Finish Shape (Extrude)");
        finish.setOnAction(e -> {
            EditableMesh built = drawTool.finishAndExtrude(heightSlider.getValue(), TEXTURE_SIZE);
            if (built != null) {
                built.label = "Shape " + (++shapeCounter);
                registerNewMesh(built);
            }
            updateStatus();
        });
        Button cancelDraw = new Button("Cancel Shape");
        cancelDraw.setOnAction(e -> { drawTool.cancel(); updateStatus(); });
        VBox drawBox = section("Draw & Extrude",
                new Label("Click the ground to place points, then:"),
                new Label("Extrusion height"), heightSlider,
                new HBox(8, finish, cancelDraw));

        // --- Paint settings ---
        ColorPicker colorPicker = new ColorPicker(paintTool.color);
        colorPicker.setOnAction(e -> paintTool.color = colorPicker.getValue());
        ToggleGroup brushGroup = new ToggleGroup();
        RadioButton soft = radio("Soft round", brushGroup, true);
        RadioButton hard = radio("Hard round", brushGroup, false);
        RadioButton square = radio("Square", brushGroup, false);
        soft.setOnAction(e -> paintTool.brush = PaintTool.Brush.SOFT_ROUND);
        hard.setOnAction(e -> paintTool.brush = PaintTool.Brush.HARD_ROUND);
        square.setOnAction(e -> paintTool.brush = PaintTool.Brush.SQUARE);

        Slider brushSize = labeledSlider(2, 80, paintTool.sizePixels, v -> paintTool.sizePixels = v);
        Slider opacity = labeledSlider(0.05, 1.0, paintTool.opacity, v -> paintTool.opacity = v);

        VBox paintBox = section("Paint / Fill", colorPicker, soft, hard, square,
                new Label("Brush size"), brushSize,
                new Label("Opacity"), opacity,
                new Label("(Fill mode uses this color on the whole object)"));

        // --- Transform settings ---
        selectionLabel = new Label("No object selected");
        objectList = new ListView<>(allMeshes);
        objectList.setPrefHeight(110);
        objectList.getSelectionModel().selectedItemProperty().addListener((obs, old, newSel) -> {
            if (newSel != null && newSel != selectedMesh) selectMesh(newSel);
        });

        tx = axisSlider(-10, 10, 0, v -> { if (selectedMesh != null) selectedMesh.translate.setX(v); });
        ty = axisSlider(-10, 10, 0, v -> { if (selectedMesh != null) selectedMesh.translate.setY(v); });
        tz = axisSlider(-10, 10, 0, v -> { if (selectedMesh != null) selectedMesh.translate.setZ(v); });
        rx = axisSlider(-180, 180, 0, v -> { if (selectedMesh != null) selectedMesh.rotateX.setAngle(v); });
        ry = axisSlider(-180, 180, 0, v -> { if (selectedMesh != null) selectedMesh.rotateY.setAngle(v); });
        rz = axisSlider(-180, 180, 0, v -> { if (selectedMesh != null) selectedMesh.rotateZ.setAngle(v); });
        sx = axisSlider(0.1, 4.0, 1, v -> { if (selectedMesh != null) selectedMesh.scale.setX(v); });
        sy = axisSlider(0.1, 4.0, 1, v -> { if (selectedMesh != null) selectedMesh.scale.setY(v); });
        sz = axisSlider(0.1, 4.0, 1, v -> { if (selectedMesh != null) selectedMesh.scale.setZ(v); });

        Button resetTransform = new Button("Reset Transform");
        resetTransform.setOnAction(e -> {
            if (selectedMesh != null) {
                beginTransformEdit();
                selectedMesh.resetTransform();
                syncTransformSliders();
                endTransformEdit();
            }
        });

        VBox transformBox = section("Transform / Select",
                new Label("Objects in scene (click to select):"), objectList,
                selectionLabel,
                new Label("Translate X / Y / Z"), tx, ty, tz,
                new Label("Rotate X / Y / Z (deg)"), rx, ry, rz,
                new Label("Scale X / Y / Z"), sx, sy, sz,
                resetTransform);

        panel.getChildren().addAll(sculptBox, new Separator(), drawBox, new Separator(), paintBox,
                new Separator(), transformBox);

        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        VBox wrapper = new VBox(scroll);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        return wrapper;
    }

    private VBox section(String title, javafx.scene.Node... children) {
        Label header = new Label(title);
        header.setStyle("-fx-font-weight: bold;");
        VBox box = new VBox(6, header);
        box.getChildren().addAll(children);
        return box;
    }

    private RadioButton radio(String text, ToggleGroup group, boolean selected) {
        RadioButton rb = new RadioButton(text);
        rb.setToggleGroup(group);
        rb.setSelected(selected);
        return rb;
    }

    private Slider labeledSlider(double min, double max, double initial, java.util.function.DoubleConsumer onChange) {
        Slider s = new Slider(min, max, initial);
        s.valueProperty().addListener((obs, oldV, newV) -> onChange.accept(newV.doubleValue()));
        return s;
    }

    /** Same as labeledSlider, but also (a) ignores changes made while we're just syncing the UI
     *  to a newly-selected object, and (b) brackets real drags with undo-snapshot capture. */
    private Slider axisSlider(double min, double max, double initial, java.util.function.DoubleConsumer onChange) {
        Slider s = new Slider(min, max, initial);
        s.valueProperty().addListener((obs, oldV, newV) -> {
            if (!syncingSliders) onChange.accept(newV.doubleValue());
        });
        s.setOnMousePressed(e -> beginTransformEdit());
        s.setOnMouseReleased(e -> endTransformEdit());
        return s;
    }

    private void updateStatus() {
        String toolHint = switch (mode) {
            case SCULPT -> "Sculpt: left-drag on the object.";
            case DRAW -> "Draw: left-click the ground to add points (" + (drawTool.hasPoints() ? "in progress" : "none yet")
                    + "), then Finish Shape.";
            case PAINT -> "Paint: left-drag on the object.";
            case FILL -> "Fill: left-click an object to recolor it entirely.";
            case TRANSFORM -> "Transform: click an object (or pick it from the list) to select it.";
        };
        statusLabel.setText(toolHint + "   |   Camera: right-drag orbit, shift+right-drag pan, scroll zoom (up=in).   |   Ctrl+Z undo, Ctrl+Shift+Z redo.");
    }

    public static void main(String[] args) {
        launch(args);
    }
}
