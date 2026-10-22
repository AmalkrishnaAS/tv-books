package com.amalkrishna.books;

// From tv-detox (github.com/Ansub/tv-detox), MIT License, Copyright (c) 2026 Ansub Khan. See NOTICE.

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

// Tiny white outline icons on a 24x24 grid, so the app needs no image resources.
// Each icon is a list of ops: 0=moveTo x y, 1=lineTo x y, 2=close, 3=circle cx cy r, 4=arc cx cy r start sweep.
final class LineIcon extends Drawable {
    static final int[] PLAY = {0, 60, 45, 1, 60, 195, 1, 190, 120, 2};
    // Added for Books: player controls. Skip arrows circle clockwise (forward) or anticlockwise
    // (back) with the head at the top; the "30" is the label beside them.
    static final int[] PAUSE = {0, 88, 58, 1, 88, 182, 0, 152, 58, 1, 152, 182};
    static final int[] FORWARD = {4, 120, 128, 72, -60, 300, 0, 50, 63, 1, 84, 66, 1, 70, 97};
    static final int[] BACK = {4, 120, 128, 72, -60, 300, 0, 190, 63, 1, 156, 66, 1, 170, 97};
    static final int[] CHAPTERS = {0, 92, 70, 1, 200, 70, 0, 92, 120, 1, 200, 120, 0, 92, 170, 1, 200, 170,
            3, 50, 70, 6, 3, 50, 120, 6, 3, 50, 170, 6};
    static final int[] SPEED = {4, 120, 150, 84, 180, 180, 0, 120, 150, 1, 168, 100};
    static final int[] LIBRARY = {0, 50, 60, 1, 50, 190, 0, 95, 60, 1, 95, 190, 0, 140, 66, 1, 190, 182};
    static final int[] SERVER = {0, 40, 50, 1, 200, 50, 1, 200, 110, 1, 40, 110, 2, 0, 40, 130, 1, 200, 130,
            1, 200, 190, 1, 40, 190, 2, 3, 72, 80, 6, 3, 72, 160, 6};
    static final int[] USER = {3, 120, 82, 40, 4, 120, 214, 82, 200, 140};

    private final int[] ops;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    LineIcon(int[] ops) {
        this.ops = ops;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(0xEBFFFFFF);
    }

    @Override
    public void draw(Canvas c) {
        if (ops == null) return;
        float s = getBounds().width() / 240f; // ops are in tenths of the 24-unit grid
        paint.setStrokeWidth(16 * s);
        path.reset();
        for (int i = 0; i < ops.length; ) {
            switch (ops[i]) {
                case 0: path.moveTo(ops[i + 1] * s, ops[i + 2] * s); i += 3; break;
                case 1: path.lineTo(ops[i + 1] * s, ops[i + 2] * s); i += 3; break;
                case 2: path.close(); i += 1; break;
                case 4: // arc: cx cy r startDegrees sweepDegrees
                    float cx = ops[i + 1] * s, cy = ops[i + 2] * s, r = ops[i + 3] * s;
                    path.addArc(new RectF(cx - r, cy - r, cx + r, cy + r), ops[i + 4], ops[i + 5]);
                    i += 6;
                    break;
                default: path.addCircle(ops[i + 1] * s, ops[i + 2] * s, ops[i + 3] * s, Path.Direction.CW); i += 4;
            }
        }
        c.save();
        c.translate(getBounds().left, getBounds().top);
        c.drawPath(path, paint);
        c.restore();
    }

    @Override public void setAlpha(int a) { paint.setAlpha(a); }
    @Override public void setColorFilter(ColorFilter cf) { paint.setColorFilter(cf); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return 44; }
    @Override public int getIntrinsicHeight() { return 44; }
}
