package com.tahanerino.sculpt3d;

import javafx.scene.Group;
import javafx.scene.PerspectiveCamera;
import javafx.scene.transform.Rotate;
import javafx.scene.transform.Translate;

/**
 * Simple orbit/pan/zoom rig, deliberately kept off the left mouse button since that's reserved
 * for tools. Controls (wired up in App):
 *   right-drag    -> orbit
 *   shift+right-drag (or middle-drag) -> pan
 *   scroll        -> zoom (dolly)
 */
public class CameraRig {

    public final PerspectiveCamera camera = new PerspectiveCamera(true);
    public final Group node = new Group();

    private final Translate pan = new Translate(0, 0, 0);
    private final Rotate azimuth = new Rotate(-30, Rotate.Y_AXIS);
    private final Rotate elevation = new Rotate(-20, Rotate.X_AXIS);
    private final Translate distance = new Translate(0, 0, -18);

    public CameraRig() {
        camera.setNearClip(0.05);
        camera.setFarClip(2000);
        camera.setFieldOfView(35);
        node.getTransforms().addAll(pan, azimuth, elevation, distance);
        node.getChildren().add(camera);
    }

    public void orbit(double dxPixels, double dyPixels) {
        azimuth.setAngle(azimuth.getAngle() - dxPixels * 0.25);
        double newElevation = elevation.getAngle() - dyPixels * 0.25;
        elevation.setAngle(clamp(newElevation, -89, 89));
    }

    public void pan(double dxPixels, double dyPixels) {
        double az = Math.toRadians(azimuth.getAngle());
        double speed = 0.01 * Math.max(1.0, -distance.getZ() / 10.0);
        double right_x = Math.cos(az);
        double right_z = -Math.sin(az);
        pan.setX(pan.getX() - dxPixels * speed * right_x);
        pan.setZ(pan.getZ() - dxPixels * speed * right_z);
        pan.setY(pan.getY() - dyPixels * speed);
    }

    /** notches > 0 zooms IN (camera moves closer), notches < 0 zooms OUT. Percentage-based step,
     *  so it feels consistent whether you're up close or far away. */
    public void zoom(double notches) {
        double z = distance.getZ() + notches * -distance.getZ() * 0.12;
        distance.setZ(clamp(z, -200, -1.0));
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
}
