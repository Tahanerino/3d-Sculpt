package com.tahanerino.sculpt3d;

import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;

/**
 * Paints directly onto a mesh's diffuse texture at the UV coordinate under the cursor.
 * "Fill all" just floods the whole texture -- since each mesh has one texture, that's
 * equivalent to recoloring the entire object, which is exactly the DK-Bananza-style
 * "paint bucket for the whole sculpt" behavior asked for.
 */
public class PaintTool {

    public enum Brush { SOFT_ROUND, HARD_ROUND, SQUARE }

    public double sizePixels = 24;
    public double opacity = 0.85;
    public Brush brush = Brush.SOFT_ROUND;
    public Color color = Color.web("#d1495b");

    public void paintAt(EditableMesh mesh, double u, double v) {
        WritableImage img = mesh.getTexture();
        int size = mesh.getTextureSize();
        int cx = (int) Math.round(u * size);
        int cy = (int) Math.round(v * size);
        int r = (int) Math.ceil(sizePixels);

        PixelReader reader = img.getPixelReader();
        PixelWriter writer = img.getPixelWriter();

        int x0 = Math.max(0, cx - r), x1 = Math.min(size - 1, cx + r);
        int y0 = Math.max(0, cy - r), y1 = Math.min(size - 1, cy + r);

        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                double dx = x - cx, dy = y - cy;
                double alpha;
                switch (brush) {
                    case HARD_ROUND -> alpha = (dx * dx + dy * dy <= sizePixels * sizePixels) ? opacity : 0;
                    case SQUARE -> alpha = (Math.abs(dx) <= sizePixels && Math.abs(dy) <= sizePixels) ? opacity : 0;
                    default -> { // SOFT_ROUND
                        double dist = Math.sqrt(dx * dx + dy * dy);
                        double t = 1.0 - Math.min(1.0, dist / sizePixels);
                        alpha = opacity * (t * t * (3 - 2 * t));
                    }
                }
                if (alpha <= 0) continue;
                Color existing = reader.getColor(x, y);
                Color blended = existing.interpolate(color, alpha);
                writer.setColor(x, y, blended);
            }
        }
    }

    public void fillAll(EditableMesh mesh) {
        mesh.fillTexture(color);
    }
}
