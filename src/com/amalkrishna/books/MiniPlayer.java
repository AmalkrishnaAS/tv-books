package com.amalkrishna.books;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

// The collapsed player, in the library's header while a book is loaded: a card with the cover,
// title, chapter (or time left) and progress that opens the full player, and round buttons beside
// it to play or pause, and stop. The card sits on a solid surface, not glass, so it stays legible
// over any cover's colours.
final class MiniPlayer {
    private static final int CARD_W = 520, HEIGHT = 112, COVER = 80, BUTTON = 72, GAP = 16;
    static final int WIDTH = CARD_W + 2 * (GAP + BUTTON);

    final LinearLayout view;
    private final Activity activity;
    private final ImageView cover, playIcon;
    private final TextView title, detail;
    private final View fill;
    private final Glass glass;
    private String itemId;
    private boolean playing;

    MiniPlayer(final Activity a) {
        activity = a;
        view = new LinearLayout(a);
        view.setOrientation(LinearLayout.HORIZONTAL);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setClipChildren(false);

        final FrameLayout card = new FrameLayout(a);
        glass = new Glass(Ui.AMBER, Ui.px(Ui.RADIUS), Math.max(1, Ui.px(2)));
        GradientDrawable surface = new GradientDrawable();
        surface.setCornerRadius(Ui.px(Ui.RADIUS));
        surface.setColor(0xF0141A33);
        card.setBackground(new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[] {surface, glass}));
        final Shine shine = new Shine();
        card.setForeground(shine);
        Ui.round(card, Ui.px(Ui.RADIUS));
        Ui.focusable(card);
        LinearLayout row = new LinearLayout(a);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.px(16), 0, Ui.px(24), 0);
        cover = new ImageView(a);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0x14FFFFFF);
        Ui.round(cover, Ui.px(12));
        row.addView(cover, new LinearLayout.LayoutParams(Ui.px(COVER), Ui.px(COVER)));
        LinearLayout text = new LinearLayout(a);
        text.setOrientation(LinearLayout.VERTICAL);
        title = Ui.line(a, Ui.px(26), Color.WHITE, Ui.medium);
        detail = Ui.line(a, Ui.px(22), 0xB3FFFFFF, Ui.regular);
        text.addView(title);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = Ui.px(2);
        text.addView(detail, dp);
        FrameLayout bar = new FrameLayout(a);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.px(2));
        track.setColor(0x33FFFFFF);
        bar.setBackground(track);
        fill = new View(a);
        GradientDrawable f = new GradientDrawable();
        f.setCornerRadius(Ui.px(2));
        f.setColor(Color.WHITE);
        fill.setBackground(f);
        fill.setPivotX(0);
        fill.setScaleX(0f);
        bar.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.px(4));
        bp.topMargin = Ui.px(12);
        text.addView(bar, bp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1f);
        tp.leftMargin = Ui.px(20);
        row.addView(text, tp);
        card.addView(row, new FrameLayout.LayoutParams(-1, -1));
        final ValueAnimator[] glowAnim = new ValueAnimator[1];
        card.setOnFocusChangeListener((v, has) -> Ui.focusTile(v, glass, shine, glowAnim, has, 1.04f));
        card.setOnClickListener(v -> a.startActivity(Ui.inApp(new Intent(a, PlayerActivity.class))));
        view.addView(card, new LinearLayout.LayoutParams(Ui.px(CARD_W), Ui.px(HEIGHT)));

        playIcon = new ImageView(a);
        view.addView(roundButton(a, playIcon, LineIcon.PLAY, v -> {
            PlayerService p = PlayerService.instance;
            if (p != null) p.toggle();
            update();
        }), buttonParams());
        view.addView(roundButton(a, new ImageView(a), LineIcon.STOP, v -> {
            PlayerService p = PlayerService.instance;
            if (p != null) p.stop();
            update();
        }), buttonParams());
        view.setVisibility(View.GONE);
    }

    private static LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(BUTTON), Ui.px(BUTTON));
        lp.leftMargin = Ui.px(GAP);
        return lp;
    }

    // A round button like the pills: soft glass that brightens and grows a little on focus.
    private static View roundButton(Activity a, ImageView icon, LineIcon.Shape shape, View.OnClickListener click) {
        FrameLayout button = new FrameLayout(a);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0x14FFFFFF);
        button.setBackground(bg);
        icon.setImageDrawable(new LineIcon(shape));
        button.addView(icon, new FrameLayout.LayoutParams(Ui.px(32), Ui.px(32), Gravity.CENTER));
        Ui.focusable(button);
        button.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0x40FFFFFF : 0x14FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(Ui.EASE).setDuration(Ui.FOCUS_MS).start();
        });
        button.setOnClickListener(click);
        return button;
    }

    // Follows the player: shown with the current book's details, or hidden when stopped.
    void update() {
        PlayerService p = PlayerService.instance;
        boolean on = p != null && p.itemId != null;
        view.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) {
            itemId = null;
            return;
        }
        if (!p.itemId.equals(itemId)) {
            itemId = p.itemId;
            cover.setImageDrawable(null);
            Abs.cover(activity, itemId, Ui.px(COVER) * 2, cover, (bmp, tint) -> glass.setGlowColor(tint));
        }
        put(title, p.title.isEmpty() ? "Loading…" : p.title);
        PlayerService.Chapter ch = p.chapter();
        double at = p.position();
        String left = p.duration > 0 ? Ui.duration(p.duration - at) + " left" : "";
        put(detail, p.error != null ? p.error : ch != null ? ch.title + (left.isEmpty() ? "" : "  ·  " + left) : left);
        fill.setScaleX(p.duration > 0 ? (float) Math.min(1, at / p.duration) : 0f);
        if (p.isPlaying() != playing) {
            playing = p.isPlaying();
            playIcon.setImageDrawable(new LineIcon(playing ? LineIcon.PAUSE : LineIcon.PLAY));
        }
    }

    private static void put(TextView t, String text) {
        if (!text.contentEquals(t.getText())) t.setText(text);
    }
}
