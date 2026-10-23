package com.amalkrishna.books;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// A screen's whole background as one picture: the wallpaper, an optional wash of a cover's colours
// and a darkening gradient, rendered off the main thread at half resolution (smooth gradients
// upscale invisibly) and crossfaded in. The TV redraws the full screen every frame, so this makes
// the background one textured quad instead of half a dozen full-screen gradient layers.
final class Backdrop {
    private static final int W = 960, H = 540, FADE_MS = 500;
    private static final ExecutorService RENDER = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Bitmap wallpaper; // shared by every screen: rendered once

    private final ImageView[] layers = new ImageView[2];
    private final int[] scrim;
    private final GradientDrawable.Orientation direction;
    private final float wash;
    private int front, generation;

    // Adds the two background layers behind everything else in `root`. `scrim` darkens the picture
    // (null for none); `wash` is how strongly a cover's colours show through.
    Backdrop(Activity a, FrameLayout root, int[] scrim, GradientDrawable.Orientation direction, float wash) {
        this.scrim = scrim;
        this.direction = direction;
        this.wash = wash;
        for (int i = 0; i < 2; i++) {
            ImageView v = new ImageView(a);
            v.setScaleType(ImageView.ScaleType.FIT_XY);
            layers[i] = v;
            root.addView(v, i, new FrameLayout.LayoutParams(-1, -1));
        }
        layers[1].setVisibility(View.INVISIBLE);
        // The window's own background would be drawn underneath for nothing.
        a.getWindow().setBackgroundDrawable(null);
        layers[0].setImageBitmap(compose(null));
    }

    // Shows a cover's colours (or none, with null), after rendering in the background.
    void show(final Bitmap cover) {
        final int g = ++generation;
        RENDER.execute(() -> {
            final Bitmap picture = compose(cover);
            MAIN.post(() -> {
                if (g != generation) return; // a newer one is on its way
                crossfade(picture);
            });
        });
    }

    // layers[1] always sits above layers[0]. The top one fades in over the bottom, or the bottom
    // one is set underneath and the top fades out; either way only one stays visible afterwards.
    private void crossfade(Bitmap picture) {
        final ImageView bottom = layers[0], top = layers[1];
        top.animate().cancel();
        if (front == 0) {
            top.setImageBitmap(picture);
            top.setAlpha(0f);
            top.setVisibility(View.VISIBLE);
            top.animate().alpha(1f).setDuration(FADE_MS).setInterpolator(Ui.EASE)
                    .withEndAction(() -> bottom.setVisibility(View.INVISIBLE)).start();
            front = 1;
        } else {
            bottom.setImageBitmap(picture);
            bottom.setVisibility(View.VISIBLE);
            top.animate().alpha(0f).setDuration(FADE_MS).setInterpolator(Ui.EASE)
                    .withEndAction(() -> top.setVisibility(View.INVISIBLE)).start();
            front = 0;
        }
    }

    private Bitmap compose(Bitmap cover) {
        Bitmap out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawBitmap(wallpaper(), 0, 0, null);
        if (cover != null) {
            Bitmap tiny = Bitmap.createScaledBitmap(cover, 16, 16, true);
            Paint p = new Paint(Paint.FILTER_BITMAP_FLAG);
            p.setAlpha(Math.round(255 * wash));
            c.drawBitmap(tiny, null, new Rect(0, 0, W, H), p);
            tiny.recycle();
        }
        if (scrim != null) {
            GradientDrawable g = new GradientDrawable(direction, scrim);
            g.setBounds(0, 0, W, H);
            g.draw(c);
        }
        return out;
    }

    private static synchronized Bitmap wallpaper() {
        if (wallpaper == null) {
            wallpaper = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(wallpaper);
            // Ui.wallpaper() is sized for the screen; draw it scaled down.
            int sw = Ui.px(1920), sh = Ui.px(1080);
            c.scale((float) W / sw, (float) H / sh);
            Drawable d = Ui.wallpaper();
            d.setBounds(0, 0, sw, sh);
            d.draw(c);
        }
        return wallpaper;
    }
}
