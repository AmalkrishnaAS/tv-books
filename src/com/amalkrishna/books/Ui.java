package com.amalkrishna.books;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.Interpolator;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

// The look shared with Home: wallpaper, type, focus motion, pills and the side panel.
// Designed at 1920x1080 and scaled to the TV's UI size by px().
final class Ui {
    static final int MARGIN = 96, RADIUS = 20, PILL_H = 72;
    // Base durations; Android scales them by the TV's animation speed (0.5x here, so halved on screen).
    static final int FOCUS_MS = 300, NAME_OUT_MS = 110, NAME_IN_MS = 260, SHINE_MS = 900;
    static final Interpolator EASE = new DecelerateInterpolator(2f);
    static final int AMBER = 0xFFF0A040; // Books' accent: the glow when a cover has no clear colour

    static Typeface light, regular, medium;
    private static float scale = 1f;

    private Ui() {}

    static void init(Activity a) {
        scale = a.getResources().getDisplayMetrics().widthPixels / 1920f;
        if (regular != null) return;
        light = Typeface.create("sans-serif-light", Typeface.NORMAL);
        regular = Typeface.create("sans-serif", Typeface.NORMAL);
        medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    }

    static int px(int v) {
        return Math.round(v * scale);
    }

    // Home's deep navy-to-indigo wallpaper with three soft glows. Drawn by the window: no bitmap.
    static Drawable wallpaper() {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[] {0xFF0D1330, 0xFF080B1D, 0xFF04050E});
        GradientDrawable blue = radial(0x42506CFF, 0.12f, -0.15f, px(1350));
        GradientDrawable violet = radial(0x2A9B5CF6, 0.72f, 0.28f, px(900));
        GradientDrawable teal = radial(0x2614B8C4, 1.02f, 1.1f, px(1150));
        for (GradientDrawable g : new GradientDrawable[] {base, blue, violet, teal}) g.setDither(true);
        return new LayerDrawable(new Drawable[] {base, blue, violet, teal});
    }

    private static GradientDrawable radial(int color, float cx, float cy, float r) {
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setColors(new int[] {color, color & 0x00FFFFFF});
        g.setGradientCenter(cx, cy);
        g.setGradientRadius(r);
        return g;
    }

    static TextView text(Context c, int px, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        if (tf != null) t.setTypeface(tf);
        return t;
    }

    static TextView line(Context c, int px, int color, Typeface tf) {
        TextView t = text(c, px, color, tf);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static FrameLayout.LayoutParams at(int x, int y, int w, int h) {
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(w, h);
        lp.leftMargin = x;
        lp.topMargin = y;
        return lp;
    }

    // Views draw their own focus, so Android's grey default highlight (8.0+) is turned off.
    static void focusable(View v) {
        v.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) v.setDefaultFocusHighlightEnabled(false);
    }

    static void round(View v, final float radius) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        v.setClipToOutline(true);
    }

    // Home's focus motion for a glass tile: lift, scale, brighter glass, inner glow and one sweep.
    // No elevation shadow: it would show through the translucent glass.
    static void focusTile(View v, Glass glass, Shine shine, ValueAnimator[] glowAnim, boolean has, float grow) {
        if (has) sweep(shine);
        if (glowAnim[0] != null) glowAnim[0].cancel();
        glowAnim[0] = ValueAnimator.ofFloat(glass.getGlow(), has ? 1f : 0f);
        glowAnim[0].setDuration(FOCUS_MS);
        glowAnim[0].setInterpolator(EASE);
        glowAnim[0].addUpdateListener(a -> glass.setGlow((float) a.getAnimatedValue()));
        glowAnim[0].start();
        v.animate().scaleX(has ? grow : 1f).scaleY(has ? grow : 1f).translationY(has ? -px(6) : 0)
                .setInterpolator(EASE).setDuration(FOCUS_MS).start();
    }

    // One light sweep across a tile when it gains focus.
    static void sweep(final Shine shine) {
        ValueAnimator a = ValueAnimator.ofFloat(0f, 1f);
        a.setDuration(SHINE_MS);
        a.setInterpolator(EASE);
        a.addUpdateListener(an -> shine.setProgress((float) an.getAnimatedValue()));
        a.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator an) { shine.setProgress(-1f); }
        });
        a.start();
    }

    // Home's pill: a line icon and a label on a soft rounded background that brightens on focus.
    static final class Pill {
        final LinearLayout view;
        final ImageView icon;
        final TextView label;

        Pill(Context c, int[] lineIcon, String text) {
            view = new LinearLayout(c);
            view.setOrientation(LinearLayout.HORIZONTAL);
            view.setGravity(Gravity.CENTER_VERTICAL);
            view.setPadding(px(28), 0, px(32), 0);
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(px(PILL_H) / 2f);
            bg.setColor(0x14FFFFFF);
            view.setBackground(bg);
            icon = new ImageView(c);
            icon.setImageDrawable(new LineIcon(lineIcon));
            shown = lineIcon;
            view.addView(icon, new LinearLayout.LayoutParams(px(32), px(32)));
            label = text(c, px(26), 0xCCFFFFFF, regular);
            label.setText(text);
            label.setSingleLine(true);
            label.setFontFeatureSettings("tnum");
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
            tlp.leftMargin = px(16);
            view.addView(label, tlp);
            focusable(view);
            view.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? 0x40FFFFFF : 0x14FFFFFF);
                label.setTextColor(has ? Color.WHITE : 0xCCFFFFFF);
                v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                        .setInterpolator(EASE).setDuration(FOCUS_MS).start();
            });
        }

        void set(int[] lineIcon, String text) {
            if (lineIcon != shown) {
                shown = lineIcon;
                icon.setImageDrawable(new LineIcon(lineIcon));
            }
            if (!text.contentEquals(label.getText())) label.setText(text);
        }

        private int[] shown;
    }

    interface Pick { void at(int which); }

    // Home's side panel: a title, then one row per choice, the current one selected. Back closes it.
    static void panel(Activity a, String title, final List<String> labels, int current, final Pick pick) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Material_Dialog_NoActionBar);
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(48), px(92), px(48), px(48));

        TextView head = line(a, px(40), Color.WHITE, medium);
        head.setText(title);
        head.setPadding(px(24), 0, px(24), 0);
        box.addView(head);

        ListView list = new ListView(a);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        GradientDrawable focus = new GradientDrawable();
        focus.setCornerRadius(px(RADIUS));
        focus.setColor(0x3DFFFFFF);
        list.setSelector(focus);
        list.setAdapter(new BaseAdapter() {
            @Override public int getCount() { return labels.size(); }
            @Override public Object getItem(int pos) { return labels.get(pos); }
            @Override public long getItemId(int pos) { return pos; }
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = convert != null ? (TextView) convert : line(a, px(30), 0xE6FFFFFF, regular);
                t.setText(labels.get(pos));
                t.setTextColor(pos == current ? Color.WHITE : 0xB3FFFFFF);
                t.setTypeface(pos == current ? medium : regular);
                t.setGravity(Gravity.CENTER_VERTICAL);
                t.setMinHeight(px(88));
                t.setPadding(px(24), 0, px(24), 0);
                return t;
            }
        });
        list.setOnItemClickListener((parent, v, pos, id) -> {
            d.dismiss();
            pick.at(pos);
        });
        if (current >= 0) list.setSelection(current);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, 1f);
        lp.topMargin = px(36);
        box.addView(list, lp);

        d.setContentView(box);
        Window w = d.getWindow();
        if (w == null) return;
        w.setBackgroundDrawable(new ColorDrawable(0xF70B0F22));
        w.setLayout(px(640), -1);
        w.setGravity(Gravity.END);
        w.setDimAmount(0.5f);
        d.show();
    }

    // "12h 5m", "45m".
    static String duration(double seconds) {
        long m = Math.round(seconds / 60);
        if (m < 60) return m + "m";
        return (m / 60) + "h " + (m % 60) + "m";
    }

    // "1:02:03", "2:03".
    static String clock(double seconds) {
        long s = Math.max(0, Math.round(seconds));
        long h = s / 3600, m = s / 60 % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s % 60)
                : String.format(Locale.US, "%d:%02d", m, s % 60);
    }
}
