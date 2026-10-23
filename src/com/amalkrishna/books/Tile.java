package com.amalkrishna.books;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

// A cover tile: the cover (or the title while it loads), a frame with a soft sheen and edge, a
// glow in the cover's colour that fades in on focus, one light sweep, and an optional progress bar.
// Focus only animates view properties (scale, lift, the glow's alpha, the sweep's position), which
// the render thread applies on its own: nothing is redrawn or re-recorded per frame. The glow is a
// white mask shared by every tile of the same size, tinted with a colour filter.
final class Tile extends FrameLayout {
    private static final Map<String, Bitmap> MASKS = new HashMap<>();

    final ImageView cover;
    private final TextView placeholder;
    private final ImageView glow;
    private final View shine, bar, fill;
    private final int w, h;
    private final float grow;
    private int tint;

    Tile(Context c, int w, int h, boolean round, float grow) {
        super(c);
        this.w = w;
        this.h = h;
        this.grow = grow;
        final float radius = round ? Math.min(w, h) / 2f : Ui.px(Ui.RADIUS);
        GradientDrawable base = new GradientDrawable();
        base.setColor(0x14FFFFFF);
        setBackground(base);

        placeholder = Ui.text(c, Ui.px(24), 0x99FFFFFF, Ui.regular);
        placeholder.setGravity(Gravity.CENTER);
        placeholder.setMaxLines(3);
        placeholder.setEllipsize(TextUtils.TruncateAt.END);
        placeholder.setPadding(Ui.px(18), 0, Ui.px(18), 0);
        addView(placeholder, new LayoutParams(-1, -1));
        cover = new ImageView(c);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        addView(cover, new LayoutParams(-1, -1));

        bar = new View(c);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.px(3));
        track.setColor(0x66000000);
        bar.setBackground(track);
        LayoutParams bp = new LayoutParams(-1, Ui.px(6), Gravity.BOTTOM);
        bp.setMargins(Ui.px(18), 0, Ui.px(18), Ui.px(18));
        addView(bar, bp);
        fill = new View(c);
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(Ui.px(3));
        f.setColor(Color.WHITE);
        fill.setBackground(f);
        fill.setPivotX(0);
        addView(fill, new LayoutParams(bp));
        bar.setVisibility(GONE);
        fill.setVisibility(GONE);

        glow = new ImageView(c);
        glow.setImageBitmap(mask(w, h, radius));
        glow.setAlpha(0f);
        addView(glow, new LayoutParams(-1, -1));
        setTint(Ui.AMBER);

        // A soft diagonal band, swept across by moving the view.
        shine = new View(c);
        shine.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0x00FFFFFF, 0x2EFFFFFF, 0x00FFFFFF}));
        shine.setVisibility(INVISIBLE);
        addView(shine, new LayoutParams(Math.round(w * 0.6f), -1));

        setForeground(new Frame(radius));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
        Ui.focusable(this);
    }

    // Shows a book: its title until the cover loads, and progress when it's under way.
    void bind(String title, double progress) {
        placeholder.setText(title);
        cover.setImageDrawable(null);
        boolean show = progress > 0 && progress < 1;
        bar.setVisibility(show ? VISIBLE : GONE);
        fill.setVisibility(show ? VISIBLE : GONE);
        if (show) fill.setScaleX((float) progress);
        setTint(Ui.AMBER);
    }

    void setTint(int color) {
        if (color == tint) return;
        tint = color;
        glow.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
    }

    // Focus on or off, animated. `instant` jumps straight there (a recycled view being re-bound).
    void focus(boolean has, boolean instant) {
        animate().cancel();
        glow.animate().cancel();
        float s = has ? grow : 1f, ty = has ? -Ui.px(6) : 0, a = has ? 1f : 0f;
        if (instant) {
            setScaleX(s);
            setScaleY(s);
            setTranslationY(ty);
            glow.setAlpha(a);
            return;
        }
        animate().scaleX(s).scaleY(s).translationY(ty).setInterpolator(Ui.EASE).setDuration(Ui.FOCUS_MS).start();
        glow.animate().alpha(a).setInterpolator(Ui.EASE).setDuration(Ui.FOCUS_MS).start();
        if (has) {
            shine.animate().cancel();
            shine.setTranslationX(-w * 0.6f);
            shine.setVisibility(VISIBLE);
            shine.animate().translationX(w).setInterpolator(Ui.EASE).setDuration(Ui.SHINE_MS)
                    .withEndAction(() -> shine.setVisibility(INVISIBLE)).start();
        }
    }

    // The inner glow as a white band just inside the edge, fading inwards. Blurred once per tile
    // size and shared; each tile tints it.
    private static Bitmap mask(int w, int h, float radius) {
        String key = w + "x" + h + "r" + radius;
        Bitmap b = MASKS.get(key);
        if (b != null) return b;
        b = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8);
        Canvas c = new Canvas(b);
        Path inside = new Path();
        inside.addRoundRect(new RectF(0, 0, w, h), radius, radius, Path.Direction.CW);
        c.clipPath(inside);
        float blur = h * 0.11f;
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(blur * 1.2f);
        p.setColor(0x8CFFFFFF); // low-key: about 55 % at the very edge
        p.setMaskFilter(new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL));
        c.drawRoundRect(new RectF(0, 0, w, h), radius, radius, p);
        MASKS.put(key, b);
        return b;
    }

    // The frame: a faint sheen across the top and a thin edge that catches the light. Drawn once.
    private static final class Frame extends Drawable {
        private final Paint sheen = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final float radius, stroke = Math.max(1, Ui.px(2));

        Frame(float radius) {
            this.radius = radius;
            edge.setStyle(Paint.Style.STROKE);
            edge.setStrokeWidth(stroke);
        }

        @Override
        protected void onBoundsChange(Rect b) {
            box.set(b.left + stroke / 2, b.top + stroke / 2, b.right - stroke / 2, b.bottom - stroke / 2);
            sheen.setShader(new LinearGradient(0, b.top, 0, b.top + b.height() * 0.5f, 0x1AFFFFFF, 0x00FFFFFF,
                    Shader.TileMode.CLAMP));
            edge.setShader(new LinearGradient(b.left, b.top, b.right, b.bottom,
                    new int[] {0x8CFFFFFF, 0x1AFFFFFF, 0x0DFFFFFF, 0x33FFFFFF}, new float[] {0f, 0.35f, 0.7f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas c) {
            c.drawRoundRect(box, radius, radius, sheen);
            c.drawRoundRect(box, radius, radius, edge);
        }

        @Override public void setAlpha(int alpha) { edge.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter cf) { edge.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
