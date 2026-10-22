package com.amalkrishna.books;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Now playing: the cover on a soft wash of its colours, title and chapter, progress, and a row of
// pills to skip back, play or pause, skip forward, pick a chapter, change the speed or stop.
// Back returns to the library with the book still playing; the Home button stops it.
public class PlayerActivity extends Activity implements PlayerService.Listener {
    static final String EXTRA_TITLE = "title", EXTRA_AUTHOR = "author";
    private static final int COVER = 560, LEFT = 160, TEXT_X = 820, TOP = 200, TICK_MS = 500;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String itemId, title = "", author = "";
    private ImageView cover, backdrop;
    private Glass coverGlass;
    private TextView authorView, titleView, narratorView, chapterView, elapsed, remaining, status;
    private View fill;
    private Ui.Pill playPill, speedPill;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            refresh();
            main.postDelayed(this, TICK_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        getWindow().setBackgroundDrawable(Ui.wallpaper());
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);

        // The cover's colours, blurred across the screen and darkened towards the text.
        backdrop = new ImageView(this);
        backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        backdrop.setAlpha(0f);
        root.addView(backdrop, new FrameLayout.LayoutParams(-1, -1));
        View scrim = new View(this);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0x9904050E, 0xCC04050E, 0xE604050E}));
        root.addView(scrim, new FrameLayout.LayoutParams(-1, -1));

        FrameLayout coverFrame = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0x14FFFFFF);
        coverFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        coverGlass = new Glass(Ui.AMBER, Ui.px(Ui.RADIUS), Math.max(1, Ui.px(2)), true);
        coverGlass.setGlow(0.6f);
        coverFrame.setForeground(coverGlass);
        Ui.round(coverFrame, Ui.px(Ui.RADIUS));
        root.addView(coverFrame, Ui.at(Ui.px(LEFT), Ui.px(TOP), Ui.px(COVER), Ui.px(COVER)));

        int textW = Ui.px(1920 - TEXT_X - Ui.MARGIN);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        authorView = Ui.line(this, Ui.px(30), 0xA6FFFFFF, Ui.regular);
        titleView = Ui.text(this, Ui.px(64), Color.WHITE, Ui.medium);
        titleView.setMaxLines(2);
        titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleView.setLineSpacing(0, 0.95f);
        narratorView = Ui.line(this, Ui.px(26), 0x80FFFFFF, Ui.regular);
        chapterView = Ui.line(this, Ui.px(30), 0xE6FFFFFF, Ui.regular);
        info.addView(authorView);
        info.addView(titleView, margins(Ui.px(8)));
        info.addView(narratorView, margins(Ui.px(12)));
        info.addView(chapterView, margins(Ui.px(40)));
        root.addView(info, Ui.at(Ui.px(TEXT_X), Ui.px(TOP + 10), textW, -2));

        // Progress through the whole book: a thin track with a white fill, times underneath.
        FrameLayout bar = new FrameLayout(this);
        GradientDrawable track = new GradientDrawable();
        track.setCornerRadius(Ui.px(4));
        track.setColor(0x33FFFFFF);
        bar.setBackground(track);
        fill = new View(this);
        GradientDrawable fillShape = new GradientDrawable();
        fillShape.setCornerRadius(Ui.px(4));
        fillShape.setColor(Color.WHITE);
        fill.setBackground(fillShape);
        fill.setPivotX(0);
        fill.setScaleX(0f);
        bar.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        root.addView(bar, Ui.at(Ui.px(TEXT_X), Ui.px(620), textW, Ui.px(8)));
        elapsed = Ui.line(this, Ui.px(24), 0xA6FFFFFF, Ui.regular);
        remaining = Ui.line(this, Ui.px(24), 0xA6FFFFFF, Ui.regular);
        elapsed.setFontFeatureSettings("tnum");
        remaining.setFontFeatureSettings("tnum");
        remaining.setGravity(android.view.Gravity.END);
        root.addView(elapsed, Ui.at(Ui.px(TEXT_X), Ui.px(644), textW / 2, -2));
        root.addView(remaining, Ui.at(Ui.px(TEXT_X) + textW / 2, Ui.px(644), textW - textW / 2, -2));

        LinearLayout controls = new LinearLayout(this);
        controls.setClipChildren(false);
        Ui.Pill back = new Ui.Pill(this, LineIcon.BACK, "30");
        playPill = new Ui.Pill(this, LineIcon.PLAY, "Play");
        Ui.Pill forward = new Ui.Pill(this, LineIcon.FORWARD, "30");
        Ui.Pill chapters = new Ui.Pill(this, LineIcon.CHAPTERS, "Chapters");
        speedPill = new Ui.Pill(this, LineIcon.SPEED, "1×");
        Ui.Pill stop = new Ui.Pill(this, LineIcon.STOP, "Stop");
        back.view.setOnClickListener(v -> withPlayer(p -> p.skip(-30)));
        playPill.view.setOnClickListener(v -> withPlayer(PlayerService::toggle));
        forward.view.setOnClickListener(v -> withPlayer(p -> p.skip(30)));
        chapters.view.setOnClickListener(v -> showChapters());
        speedPill.view.setOnClickListener(v -> showSpeeds());
        // Stop: saves the position, ends the session and the notification, and goes back.
        stop.view.setOnClickListener(v -> {
            PlayerService p = PlayerService.instance;
            if (p != null) p.stop();
            finish();
        });
        for (Ui.Pill p : new Ui.Pill[] {back, playPill, forward, chapters, speedPill, stop}) {
            p.view.setPadding(Ui.px(22), 0, Ui.px(26), 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.px(Ui.PILL_H));
            if (controls.getChildCount() > 0) lp.leftMargin = Ui.px(16);
            controls.addView(p.view, lp);
        }
        root.addView(controls, Ui.at(Ui.px(TEXT_X), Ui.px(740), -2, -2));

        status = Ui.text(this, Ui.px(26), 0xB3FFFFFF, Ui.regular);
        root.addView(status, Ui.at(Ui.px(TEXT_X), Ui.px(850), textW, -2));

        setContentView(root);
        playPill.view.requestFocus();
        handle(getIntent());
    }

    private static LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        return lp;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    // Opening a book starts it (or resumes it, if it's the one already loaded). Without a book,
    // as from the Now Playing pill, the screen shows whatever is playing.
    private void handle(Intent intent) {
        String item = intent.getStringExtra(PlayerService.EXTRA_ITEM);
        PlayerService p = PlayerService.instance;
        if (item == null && p != null) item = p.itemId;
        if (item == null) {
            finish();
            return;
        }
        if (!item.equals(itemId)) {
            itemId = item;
            title = valueOr(intent.getStringExtra(EXTRA_TITLE), "");
            author = valueOr(intent.getStringExtra(EXTRA_AUTHOR), "");
            cover.setImageDrawable(null);
            backdrop.setAlpha(0f);
            Abs.cover(this, itemId, COVER, cover, (bmp, tint) -> {
                coverGlass.setGlowColor(tint);
                showBackdrop(bmp);
            });
        }
        if (intent.getStringExtra(PlayerService.EXTRA_ITEM) != null) {
            startService(new Intent(this, PlayerService.class).putExtra(PlayerService.EXTRA_ITEM, itemId));
        }
        refresh();
    }

    private static String valueOr(String s, String fallback) {
        return s != null ? s : fallback;
    }

    // A 16 px copy of the cover, stretched with filtering: a soft blur that costs almost nothing.
    private void showBackdrop(Bitmap bmp) {
        Bitmap tiny = Bitmap.createScaledBitmap(bmp, 16, 16, true);
        BitmapDrawable d = new BitmapDrawable(getResources(), tiny);
        d.setFilterBitmap(true);
        backdrop.setImageDrawable(d);
        backdrop.animate().alpha(0.55f).setDuration(600).setInterpolator(Ui.EASE).start();
    }

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.listener = this;
        main.post(tick);
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        if (PlayerService.listener == this) PlayerService.listener = null;
        super.onPause();
    }

    @Override
    public void changed() {
        refresh();
    }

    @Override
    protected void onUserLeaveHint() {
        Ui.stopPlayback();
        super.onUserLeaveHint();
    }

    private void refresh() {
        PlayerService p = PlayerService.instance;
        boolean ours = p != null && itemId != null && itemId.equals(p.itemId);
        if (ours && !p.title.isEmpty()) {
            title = p.title;
            author = p.author;
        }
        put(titleView, title);
        put(authorView, author);
        put(narratorView, ours && !p.narrator.isEmpty() ? "Narrated by " + p.narrator : "");
        PlayerService.Chapter ch = ours ? p.chapter() : null;
        put(chapterView, ch != null ? ch.title : "");
        double duration = ours ? p.duration : 0, at = ours ? p.position() : 0;
        fill.setScaleX(duration > 0 ? (float) Math.min(1, at / duration) : 0f);
        put(elapsed, duration > 0 ? Ui.clock(at) : "");
        put(remaining, duration > 0 ? Ui.duration(duration - at) + " left" : "");
        boolean playing = ours && p.isPlaying();
        playPill.set(playing ? LineIcon.PAUSE : LineIcon.PLAY, playing ? "Pause" : "Play");
        speedPill.set(LineIcon.SPEED, speedLabel(ours ? p.speed() : Abs.prefs(this).getFloat("speed", 1f)));
        String s = "";
        if (!ours) s = "Stopped";
        else if (p.error != null) s = p.error;
        else if (p.sessionId == null || p.isLoading()) s = "Loading…";
        put(status, s);
    }

    // setText relayouts even for the same text; this refreshes twice a second.
    private static void put(TextView t, String text) {
        if (!text.contentEquals(t.getText())) t.setText(text);
    }

    private static String speedLabel(float s) {
        String n = s == Math.round(s) ? String.valueOf(Math.round(s)) : String.format(Locale.US, "%s", s);
        return n + "×";
    }

    interface Action { void on(PlayerService p); }

    private void withPlayer(Action a) {
        PlayerService p = PlayerService.instance;
        if (p != null && itemId != null && itemId.equals(p.itemId)) a.on(p);
        else if (itemId != null) startService(new Intent(this, PlayerService.class).putExtra(PlayerService.EXTRA_ITEM, itemId));
        refresh();
    }

    private void showChapters() {
        final PlayerService p = PlayerService.instance;
        if (p == null || p.chapters.isEmpty()) return;
        List<String> labels = new ArrayList<>();
        PlayerService.Chapter now = p.chapter();
        int current = -1;
        for (int i = 0; i < p.chapters.size(); i++) {
            PlayerService.Chapter c = p.chapters.get(i);
            labels.add(c.title + "   " + Ui.clock(c.start));
            if (c == now) current = i;
        }
        Ui.sheet(this, "Chapters", labels, current, which -> {
            p.seek(p.chapters.get(which).start);
            refresh();
        });
    }

    private void showSpeeds() {
        final float[] speeds = PlayerService.speeds();
        float now = PlayerService.instance != null ? PlayerService.instance.speed() : 1f;
        List<String> labels = new ArrayList<>();
        int current = -1;
        for (int i = 0; i < speeds.length; i++) {
            labels.add(speedLabel(speeds[i]));
            if (Math.abs(speeds[i] - now) < 0.01f) current = i;
        }
        Ui.sheet(this, "Speed", labels, current, which -> withPlayer(p -> p.setSpeed(speeds[which])));
    }

    // The remote's media keys, while this screen is open. (Elsewhere the media session gets them.)
    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                withPlayer(PlayerService::toggle);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                withPlayer(PlayerService::play);
                return true;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
                withPlayer(PlayerService::pause);
                return true;
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                withPlayer(p -> p.skip(30));
                return true;
            case KeyEvent.KEYCODE_MEDIA_REWIND:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                withPlayer(p -> p.skip(-30));
                return true;
            default:
                return super.onKeyDown(code, ev);
        }
    }
}
