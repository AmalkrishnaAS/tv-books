package com.amalkrishna.books;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

// Thin white outline icons, so the app ships no image files. Each shape adds its strokes to a
// path on a 24 x 24 grid; the drawable scales that path to its bounds.
final class LineIcon extends Drawable {
    interface Shape { void into(Path p); }

    static final Shape PLAY = p -> {
        p.moveTo(7.5f, 5f);
        p.lineTo(18.5f, 12f);
        p.lineTo(7.5f, 19f);
        p.close();
    };
    static final Shape PAUSE = p -> {
        line(p, 8.8f, 5.8f, 8.8f, 18.2f);
        line(p, 15.2f, 5.8f, 15.2f, 18.2f);
    };
    // Skips: a ring open at the top, its arrowhead at the gap, clockwise forward, anticlockwise back.
    static final Shape FORWARD = p -> {
        arc(p, 12f, 12.8f, 7.2f, -60f, 300f);
        p.moveTo(5f, 6.3f);
        p.lineTo(8.4f, 6.6f);
        p.lineTo(7f, 9.7f);
    };
    static final Shape BACK = p -> {
        arc(p, 12f, 12.8f, 7.2f, -60f, 300f);
        p.moveTo(19f, 6.3f);
        p.lineTo(15.6f, 6.6f);
        p.lineTo(17f, 9.7f);
    };
    static final Shape STOP = p -> p.addRoundRect(new RectF(6.5f, 6.5f, 17.5f, 17.5f), 2f, 2f, Path.Direction.CW);
    static final Shape CHAPTERS = p -> {
        for (float y : new float[] {7f, 12f, 17f}) {
            line(p, 9.2f, y, 20f, y);
            p.addCircle(5f, y, 0.6f, Path.Direction.CW);
        }
    };
    static final Shape SPEED = p -> {
        arc(p, 12f, 15f, 8.4f, 180f, 180f);
        line(p, 12f, 15f, 16.8f, 10f);
    };
    static final Shape LIBRARY = p -> {
        line(p, 5f, 6f, 5f, 19f);
        line(p, 9.5f, 6f, 9.5f, 19f);
        line(p, 14f, 6.6f, 19f, 18.2f);
    };
    static final Shape SERVER = p -> {
        p.addRect(4f, 5f, 20f, 11f, Path.Direction.CW);
        p.addRect(4f, 13f, 20f, 19f, Path.Direction.CW);
        p.addCircle(7.2f, 8f, 0.6f, Path.Direction.CW);
        p.addCircle(7.2f, 16f, 0.6f, Path.Direction.CW);
    };
    static final Shape SEARCH = p -> {
        p.addCircle(10.5f, 10.5f, 6f, Path.Direction.CW);
        line(p, 15f, 15f, 20f, 20f);
    };
    static final Shape GRID = p -> {
        p.addRoundRect(new RectF(4.5f, 4.5f, 10.5f, 10.5f), 1.2f, 1.2f, Path.Direction.CW);
        p.addRoundRect(new RectF(13.5f, 4.5f, 19.5f, 10.5f), 1.2f, 1.2f, Path.Direction.CW);
        p.addRoundRect(new RectF(4.5f, 13.5f, 10.5f, 19.5f), 1.2f, 1.2f, Path.Direction.CW);
        p.addRoundRect(new RectF(13.5f, 13.5f, 19.5f, 19.5f), 1.2f, 1.2f, Path.Direction.CW);
    };
    static final Shape SORT = p -> {
        line(p, 5f, 7f, 19f, 7f);
        line(p, 5f, 12f, 15f, 12f);
        line(p, 5f, 17f, 11f, 17f);
    };
    static final Shape USER = p -> {
        p.addCircle(12f, 8.2f, 4f, Path.Direction.CW);
        arc(p, 12f, 21.4f, 8.2f, 200f, 140f);
    };

    private static void line(Path p, float x1, float y1, float x2, float y2) {
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
    }

    private static void arc(Path p, float cx, float cy, float r, float start, float sweep) {
        p.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), start, sweep);
    }

    private final Path path = new Path();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LineIcon(Shape shape) {
        this(shape, 1.6f, false);
    }

    // A heavier or filled version, for a primary button.
    LineIcon(Shape shape, float strokeWidth, boolean filled) {
        shape.into(path);
        paint.setStyle(filled ? Paint.Style.FILL_AND_STROKE : Paint.Style.STROKE);
        paint.setStrokeWidth(strokeWidth);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xEBFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        Rect b = getBounds();
        c.save();
        c.translate(b.left, b.top);
        c.scale(b.width() / 24f, b.height() / 24f);
        c.drawPath(path, paint);
        c.restore();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 44; }
    @Override public int getIntrinsicHeight() { return 44; }
}
