package com.amalkrishna.books;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Now playing, on a wash of the cover's colours. Top: the cover, and beside it, in order of
// importance, the title, then author and narrator, then the chapter. Bottom: a full-width,
// scrubbable progress bar with the times under it, and the transport centred below (skip back, a
// large play/pause, skip forward), flanked by the less frequent Chapters on the left and Speed and
// Stop on the right.
// Back returns to the library with the book still playing; the Home button stops it.
public class PlayerActivity extends Activity implements PlayerService.Listener {
    static final String EXTRA_TITLE = "title", EXTRA_AUTHOR = "author";
    private static final int COVER = 480, TOP = 112, TEXT_X = Ui.MARGIN + COVER + 72, TICK_MS = 500;
    private static final int BAR_Y = 690, TIMES_Y = 746, TRANSPORT_Y = 806, PLAY = 120, SKIP = 84;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String itemId, title = "", author = "";
    private ImageView cover, playIcon;
    private Backdrop backdrop;
    private Glass coverGlass;
    private TextView titleView, byline, chapterView, elapsed, remaining, preview, status;
    private ScrubBar bar;
    private View playButton;
    private Ui.Pill speedPill;
    private boolean shownPlaying;

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
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        // The cover's colours, blurred across the screen and darkened towards the text: one picture.
        backdrop = new Backdrop(this, root, new int[] {0x9904050E, 0xCC04050E, 0xE604050E},
                GradientDrawable.Orientation.LEFT_RIGHT, 0.55f);

        FrameLayout coverFrame = new FrameLayout(this);
        cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0x14FFFFFF);
        coverFrame.addView(cover, new FrameLayout.LayoutParams(-1, -1));
        coverGlass = new Glass(Ui.AMBER, Ui.px(Ui.RADIUS), Math.max(1, Ui.px(2)), true);
        coverGlass.setGlow(0.45f);
        coverFrame.setForeground(coverGlass);
        Ui.round(coverFrame, Ui.px(Ui.RADIUS));
        coverFrame.setElevation(Ui.px(28)); // the cover is opaque, so a soft shadow lifts it cleanly
        root.addView(coverFrame, Ui.at(Ui.px(Ui.MARGIN), Ui.px(TOP), Ui.px(COVER), Ui.px(COVER)));

        // The book, centred against the cover: title first, then who wrote and reads it, then where
        // you are in it.
        int textW = Ui.px(1920 - TEXT_X - Ui.MARGIN);
        LinearLayout info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        titleView = Ui.text(this, Ui.px(68), Color.WHITE, Ui.medium);
        titleView.setMaxLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setLineSpacing(0, 0.95f);
        titleView.setIncludeFontPadding(false);
        titleView.setLetterSpacing(-0.01f);
        byline = Ui.line(this, Ui.px(32), 0xC7FFFFFF, Ui.regular);
        chapterView = Ui.line(this, Ui.px(28), 0x8CFFFFFF, Ui.regular);
        info.addView(titleView);
        info.addView(byline, margins(Ui.px(20)));
        info.addView(chapterView, margins(Ui.px(40)));
        root.addView(info, Ui.at(Ui.px(TEXT_X), Ui.px(TOP), textW, Ui.px(COVER)));

        // Progress, the full width, scrubbable. The preview rides above the thumb while scrubbing.
        int fullW = Ui.px(1920 - 2 * Ui.MARGIN);
        preview = Ui.line(this, Ui.px(26), Color.WHITE, Ui.medium);
        preview.setFontFeatureSettings("tnum");
        preview.setVisibility(View.INVISIBLE);
        root.addView(preview, Ui.at(0, Ui.px(BAR_Y - 46), -2, -2));
        bar = new ScrubBar(this);
        bar.setPadding(Ui.px(12), 0, Ui.px(12), 0);
        bar.setListener(new ScrubBar.Listener() {
            @Override public void preview(double at) { showPreview(at); }
            @Override public void seek(double at) { withPlayer(p -> p.seek(at)); }
        });
        root.addView(bar, Ui.at(Ui.px(Ui.MARGIN) - Ui.px(12), Ui.px(BAR_Y), fullW + Ui.px(24), Ui.px(48)));
        elapsed = Ui.line(this, Ui.px(24), 0xA6FFFFFF, Ui.regular);
        remaining = Ui.line(this, Ui.px(24), 0xA6FFFFFF, Ui.regular);
        elapsed.setFontFeatureSettings("tnum");
        remaining.setFontFeatureSettings("tnum");
        remaining.setGravity(Gravity.END);
        root.addView(elapsed, Ui.at(Ui.px(Ui.MARGIN), Ui.px(TIMES_Y), fullW / 3, -2));
        status = Ui.line(this, Ui.px(24), 0xB3FFFFFF, Ui.regular);
        status.setGravity(Gravity.CENTER_HORIZONTAL);
        root.addView(status, Ui.at(Ui.px(Ui.MARGIN) + fullW / 3, Ui.px(TIMES_Y), fullW / 3, -2));
        root.addView(remaining, Ui.at(Ui.px(Ui.MARGIN) + 2 * fullW / 3, Ui.px(TIMES_Y), fullW - 2 * fullW / 3, -2));

        // The transport, centred on the screen.
        LinearLayout transport = new LinearLayout(this);
        transport.setGravity(Gravity.CENTER_VERTICAL);
        transport.setClipChildren(false);
        transport.addView(skipButton(LineIcon.BACK, -30), round(SKIP, 0));
        playIcon = new ImageView(this);
        playButton = circle(playIcon, LineIcon.PLAY, PLAY, true, v -> withPlayer(PlayerService::toggle));
        transport.addView(playButton, round(PLAY, 44));
        transport.addView(skipButton(LineIcon.FORWARD, 30), round(SKIP, 44));
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-2, Ui.px(PLAY), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        tp.topMargin = Ui.px(TRANSPORT_Y);
        root.addView(transport, tp);

        // The quieter actions, either side of it: chapters on the left, speed and stop on the right.
        Ui.Pill chapters = new Ui.Pill(this, LineIcon.CHAPTERS, "Chapters");
        speedPill = new Ui.Pill(this, LineIcon.SPEED, "1×");
        Ui.Pill stop = new Ui.Pill(this, LineIcon.STOP, "Stop");
        chapters.view.setOnClickListener(v -> showChapters());
        speedPill.view.setOnClickListener(v -> showSpeeds());
        // Stop: saves the position, ends the session and the notification, and goes back.
        stop.view.setOnClickListener(v -> {
            PlayerService p = PlayerService.instance;
            if (p != null) p.stop();
            finish();
        });
        int pillTop = TRANSPORT_Y + (PLAY - 64) / 2;
        root.addView(chapters.view, Ui.at(Ui.px(Ui.MARGIN), Ui.px(pillTop), -2, Ui.px(64)));
        LinearLayout right = new LinearLayout(this);
        right.setClipChildren(false);
        right.addView(speedPill.view, new LinearLayout.LayoutParams(-2, Ui.px(64)));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-2, Ui.px(64));
        sp.leftMargin = Ui.px(16);
        right.addView(stop.view, sp);
        FrameLayout.LayoutParams rp = new FrameLayout.LayoutParams(-2, Ui.px(64), Gravity.TOP | Gravity.END);
        rp.topMargin = Ui.px(pillTop);
        rp.rightMargin = Ui.px(Ui.MARGIN);
        root.addView(right, rp);
        for (Ui.Pill p : new Ui.Pill[] {chapters, speedPill, stop}) p.view.setPadding(Ui.px(24), 0, Ui.px(28), 0);

        setContentView(root);
        playButton.requestFocus();
        handle(getIntent());
    }

    private static LinearLayout.LayoutParams margins(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = top;
        return lp;
    }

    private static LinearLayout.LayoutParams round(int size, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.px(size), Ui.px(size));
        lp.leftMargin = Ui.px(left);
        return lp;
    }

    // A round button. The primary one is solid white with a dark icon: the strongest thing in the
    // row. The others are soft glass. Both grow a little and brighten when focused.
    private View circle(ImageView icon, LineIcon.Shape shape, int size, final boolean primary, View.OnClickListener click) {
        FrameLayout b = new FrameLayout(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(primary ? 0xF2FFFFFF : 0x1AFFFFFF);
        b.setBackground(bg);
        icon.setImageDrawable(primary ? new LineIcon(shape, 2f, true) : new LineIcon(shape));
        if (primary) icon.setColorFilter(0xFF0D1330, PorterDuff.Mode.SRC_IN);
        int iconSize = Ui.px(primary ? 48 : 54); // skip rings are large enough to hold their "30"
        b.addView(icon, new FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER));
        Ui.focusable(b);
        b.setOnFocusChangeListener((v, has) -> {
            if (!primary) bg.setColor(has ? 0x47FFFFFF : 0x1AFFFFFF);
            else bg.setColor(has ? 0xFFFFFFFF : 0xF2FFFFFF);
            v.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .setInterpolator(Ui.EASE).setDuration(Ui.FOCUS_MS).start();
        });
        b.setOnClickListener(click);
        return b;
    }

    // Skip back or forward, with the seconds inside the arrow's ring.
    private View skipButton(LineIcon.Shape shape, final int seconds) {
        FrameLayout b = (FrameLayout) circle(new ImageView(this), shape, SKIP, false,
                v -> withPlayer(p -> p.skip(seconds)));
        TextView n = Ui.text(this, Ui.px(14), 0xEBFFFFFF, Ui.medium);
        n.setText(String.valueOf(Math.abs(seconds)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        lp.topMargin = Ui.px(3); // the ring's centre sits a little below the icon's
        b.addView(n, lp);
        return b;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    // Opening a book starts it (or resumes it, if it's the one already loaded). Without a book,
    // as from the now-playing card, the screen shows whatever is playing.
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
            Abs.cover(this, itemId, Ui.px(COVER), cover, (bmp, tint) -> {
                coverGlass.setGlowColor(tint);
                backdrop.show(bmp);
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
        String narrator = ours ? p.narrator : "";
        put(byline, narrator.isEmpty() ? author : author.isEmpty() ? "Narrated by " + narrator
                : author + "  ·  narrated by " + narrator);
        put(chapterView, ours ? chapterLine(p, p.position()) : "");
        double duration = ours ? p.duration : 0, at = ours ? p.position() : 0;
        if (!bar.scrubbing()) {
            bar.set(duration, at, ours ? starts(p) : new double[0]);
            put(elapsed, duration > 0 ? Ui.clock(at) : "");
        }
        put(remaining, duration > 0 ? Ui.duration(duration - at) + " left" : "");
        boolean playing = ours && p.isPlaying();
        if (playing != shownPlaying || playIcon.getDrawable() == null) {
            shownPlaying = playing;
            playIcon.setImageDrawable(new LineIcon(playing ? LineIcon.PAUSE : LineIcon.PLAY, playing ? 2.6f : 2f, !playing));
        }
        speedPill.set(LineIcon.SPEED, speedLabel(ours ? p.speed() : Abs.prefs(this).getFloat("speed", 1f)));
        String s = "";
        if (!ours) s = "Stopped";
        else if (p.error != null) s = p.error;
        else if (p.sessionId == null || p.isLoading()) s = "Loading…";
        put(status, s);
    }

    // "Chapter 12 of 61  ·  The Wedding" (or just the title when chapters have their own names).
    private static String chapterLine(PlayerService p, double at) {
        int n = p.chapters.size();
        for (int i = 0; i < n; i++) {
            PlayerService.Chapter c = p.chapters.get(i);
            if (at >= c.start && at < c.end) {
                String label = "Chapter " + (i + 1) + " of " + n;
                return c.title.isEmpty() || c.title.equalsIgnoreCase(label) || c.title.matches("(?i)chapter\\s*\\d+")
                        ? label : label + "  ·  " + c.title;
            }
        }
        return "";
    }

    private double[] cachedStarts = new double[0];
    private int cachedFor = -1;

    private double[] starts(PlayerService p) {
        if (cachedFor != p.chapters.size() || cachedStarts.length != p.chapters.size()) {
            cachedStarts = new double[p.chapters.size()];
            for (int i = 0; i < cachedStarts.length; i++) cachedStarts[i] = p.chapters.get(i).start;
            cachedFor = p.chapters.size();
        }
        return cachedStarts;
    }

    // While scrubbing: the time and chapter at the preview, above the thumb.
    private void showPreview(double at) {
        PlayerService p = PlayerService.instance;
        if (at < 0 || p == null) {
            preview.setVisibility(View.INVISIBLE);
            return;
        }
        String chapter = chapterLine(p, at);
        put(preview, Ui.clock(at) + (chapter.isEmpty() ? "" : "  ·  " + chapter));
        put(elapsed, Ui.clock(at));
        preview.setVisibility(View.VISIBLE);
        preview.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        float x = bar.getLeft() + bar.previewX() - preview.getMeasuredWidth() / 2f;
        float min = Ui.px(Ui.MARGIN), max = Ui.px(1920 - Ui.MARGIN) - preview.getMeasuredWidth();
        preview.setTranslationX(Math.max(min, Math.min(max, x)));
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
