package com.amalkrishna.books;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

// A frosted-glass tile: translucent fill, a soft top sheen and a thin edge that catches the light.
// When focused, a low-key glow in the app's colour fades in along the inside of the edges.
// In frame mode (over a book cover) there is no fill, only sheen, edge and glow.
// Android 9 can't blur while drawing on the GPU, so the inner glow is rendered once (in software)
// into a bitmap per tile and only faded in and out: focus changes allocate nothing.
final class Glass extends Drawable {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF box = new RectF();
    private final float radius, stroke;
    private final boolean frame;
    private int tint; // the app's colour, for the inner glow
    private Bitmap innerGlow;
    private float glow;     // 0 = off, 1 = full (focused)

    Glass(int tint, float radius, float stroke) {
        this(tint, radius, stroke, false);
    }

    Glass(int tint, float radius, float stroke, boolean frame) {
        this.frame = frame;
        this.tint = tint;
        this.radius = radius;
        this.stroke = stroke;
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(stroke);
        setGlow(0f);
    }

    // Fades the inner glow and brightens the glass: 0 unfocused, 1 focused.
    void setGlow(float level) {
        glow = level;
        fill.setColor(blend(0x14, 0x24, level) << 24 | 0xFFFFFF);
        edge.setAlpha(blend(150, 235, level));
        sheen.setAlpha(blend(170, 255, level));
        glowPaint.setAlpha(Math.round(255 * level));
        invalidateSelf();
    }

    float getGlow() { return glow; }

    // A new glow colour, e.g. once a cover has loaded and its colour is known.
    void setGlowColor(int color) {
        if (color == tint) return;
        tint = color;
        Rect b = getBounds();
        if (!b.isEmpty()) innerGlow = renderInnerGlow(b.width(), b.height());
        invalidateSelf();
    }

    private static int blend(int a, int b, float t) {
        return Math.round(a + (b - a) * t);
    }

    @Override
    protected void onBoundsChange(Rect b) {
        float w = b.width(), h = b.height();
        box.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2);
        sheen.setShader(new LinearGradient(0, b.top, 0, b.top + h * 0.5f, 0x22FFFFFF, 0x00FFFFFF,
                Shader.TileMode.CLAMP));
        edge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                new int[] {0x8CFFFFFF, 0x1AFFFFFF, 0x0DFFFFFF, 0x33FFFFFF}, new float[] {0f, 0.35f, 0.7f, 1f},
                Shader.TileMode.CLAMP));
        innerGlow = renderInnerGlow((int) w, (int) h);
        setGlow(glow);
    }

    // A soft band of the tint just inside the rounded edge, fading towards the middle.
    private Bitmap renderInnerGlow(int w, int h) {
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Path inside = new Path();
        inside.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        c.clipPath(inside);
        float blur = h * 0.11f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(blur * 1.2f);
        p.setColor((tint & 0x00FFFFFF) | 0x8C000000); // low-key: about 55 % at the very edge
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        return bmp;
    }

    @Override
    public void draw(Canvas c) {
        if (!frame) c.drawRoundRect(box, radius, radius, fill);
        c.drawRoundRect(box, radius, radius, sheen);
        if (glow > 0f && innerGlow != null) c.drawBitmap(innerGlow, getBounds().left, getBounds().top, glowPaint);
        c.drawRoundRect(box, radius, radius, edge);
    }

    @Override
    public void getOutline(android.graphics.Outline outline) {
        outline.setRoundRect(getBounds(), radius);
    }

    @Override public void setAlpha(int alpha) { fill.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter cf) { fill.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
