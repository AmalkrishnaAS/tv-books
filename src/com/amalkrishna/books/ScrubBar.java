package com.amalkrishna.books;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;

// Progress through the book, scrubbable with the remote. Focus it, then left and right move a
// preview of where to jump, in steps that grow while the key is held (10 s, 30 s, 2 min, 5 min);
// OK, or a short pause, jumps there, and Back cancels. Chapter starts show as small notches.
final class ScrubBar extends View {
    interface Listener {
        // Where the preview is, or -1 when not scrubbing.
        void preview(double at);
        void seek(double at);
    }

    private static final int COMMIT_MS = 900;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG),
            ahead = new Paint(Paint.ANTI_ALIAS_FLAG), notch = new Paint(), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final Debounce commit = new Debounce(COMMIT_MS);
    private double duration, position, scrub = -1;
    private double[] marks = new double[0];
    private float focusAmount; // 0 to 1, animated
    private ValueAnimator focusAnim;
    private Listener listener;

    ScrubBar(Context c) {
        super(c);
        track.setColor(0x33FFFFFF);
        fill.setColor(0xFFFFFFFF);
        ahead.setColor(Ui.AMBER);
        notch.setColor(0x99060814);
        thumb.setColor(0xFFFFFFFF);
        Ui.focusable(this);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // Updates the playing position. Only redraws when the fill would move by at least a pixel.
    void set(double duration, double position, double[] chapterStarts) {
        boolean changed = duration != this.duration || chapterStarts.length != marks.length
                || Math.abs(x(position) - x(this.position)) >= 1f;
        this.duration = duration;
        this.position = position;
        marks = chapterStarts;
        if (changed) invalidate();
    }

    boolean scrubbing() {
        return scrub >= 0;
    }

    private float x(double t) {
        int w = getWidth() - getPaddingLeft() - getPaddingRight();
        return getPaddingLeft() + (duration > 0 ? (float) (Math.max(0, Math.min(1, t / duration)) * w) : 0f);
    }

    float previewX() {
        return x(scrub >= 0 ? scrub : position);
    }

    @Override
    protected void onFocusChanged(boolean has, int direction, Rect previous) {
        super.onFocusChanged(has, direction, previous);
        if (!has && scrubbing()) commitNow(); // moved away mid-scrub: go to where it was left
        if (focusAnim != null) focusAnim.cancel();
        focusAnim = ValueAnimator.ofFloat(focusAmount, has ? 1f : 0f);
        focusAnim.setDuration(Ui.FOCUS_MS);
        focusAnim.setInterpolator(Ui.EASE);
        focusAnim.addUpdateListener(a -> {
            focusAmount = (float) a.getAnimatedValue();
            invalidate();
        });
        focusAnim.start();
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        if (duration <= 0) return super.onKeyDown(code, ev);
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT: {
                int n = ev.getRepeatCount();
                double step = n < 6 ? 10 : n < 16 ? 30 : n < 30 ? 120 : 300;
                double from = scrubbing() ? scrub : position;
                scrub = Math.max(0, Math.min(duration - 1, from + (code == KeyEvent.KEYCODE_DPAD_LEFT ? -step : step)));
                if (listener != null) listener.preview(scrub);
                commit.run(this::commitNow);
                invalidate();
                return true;
            }
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (scrubbing()) commitNow();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (!scrubbing()) return super.onKeyDown(code, ev);
                commit.cancel();
                scrub = -1;
                if (listener != null) listener.preview(-1);
                invalidate();
                return true;
            default:
                return super.onKeyDown(code, ev);
        }
    }

    private void commitNow() {
        commit.cancel();
        if (!scrubbing()) return;
        double t = scrub;
        scrub = -1;
        position = t;
        if (listener != null) {
            listener.seek(t);
            listener.preview(-1);
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float left = getPaddingLeft(), right = getWidth() - getPaddingRight(), mid = getHeight() / 2f;
        float half = Ui.px(3) + Ui.px(3) * focusAmount; // the track thickens when focused
        r.set(left, mid - half, right, mid + half);
        c.drawRoundRect(r, half, half, track);
        float at = x(position);
        r.set(left, mid - half, at, mid + half);
        c.drawRoundRect(r, half, half, fill);
        if (scrubbing()) {
            float to = x(scrub);
            r.set(Math.min(at, to), mid - half, Math.max(at, to), mid + half);
            c.drawRect(r, ahead);
        }
        float notchW = Math.max(1, Ui.px(2));
        for (double m : marks) {
            if (m <= 0) continue;
            float mx = x(m);
            c.drawRect(mx - notchW / 2, mid - half, mx + notchW / 2, mid + half, notch);
        }
        if (focusAmount > 0) {
            c.drawCircle(previewX(), mid, Ui.px(12) * focusAmount, scrubbing() ? ahead : thumb);
        }
    }
}
