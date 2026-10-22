package com.amalkrishna.books;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

// A soft diagonal light band that sweeps once across a focused tile, like tvOS.
// The gradient is built once per size and moved with a matrix, so a sweep allocates nothing.
final class Shine extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix matrix = new Matrix();
    private LinearGradient band;
    private float bandWidth;
    private float progress = -1f; // outside 0..1: nothing drawn

    void setProgress(float p) {
        progress = p;
        invalidateSelf();
    }

    @Override
    protected void onBoundsChange(Rect b) {
        bandWidth = b.width() * 0.5f;
        // Tilted band: bright in the middle, clear at both edges.
        band = new LinearGradient(0, 0, bandWidth, b.height() * 0.4f,
                new int[] {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP);
        paint.setShader(band);
    }

    @Override
    public void draw(Canvas c) {
        if (band == null || progress < 0f || progress > 1f) return;
        Rect b = getBounds();
        matrix.setTranslate(-bandWidth + (b.width() + bandWidth) * progress, 0);
        band.setLocalMatrix(matrix);
        c.drawRect(b, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
