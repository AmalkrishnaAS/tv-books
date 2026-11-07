package com.amalkrishna.books;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// The library: the focused book large at the top over a wash of its cover's colours, then rows
// from Audiobookshelf's home shelves (Continue Listening first). Covers sit in glass tiles, and
// focus lifts them, sweeps a light across and glows in the cover's colour.
public class MainActivity extends Activity implements PlayerService.Listener {
    private static final int TILE = 264, GAP = 40, ROWS_TOP = 412, RELOAD_AFTER_MS = 30000;
    // The header's two columns: the hero ends a gutter before the widest right-hand item, the
    // now-playing card with its two buttons (MiniPlayer.WIDTH).
    private static final int HEADER_TOP = 72, HERO_TOP = 172, HERO_W = 1920 - 2 * Ui.MARGIN - MiniPlayer.WIDTH - 64;

    private final Handler main = new Handler(Looper.getMainLooper());
    private FrameLayout root;
    private Backdrop backdrop;
    private TextView heroTitle, heroMeta, message;
    private LinearLayout rows, topPills;
    private ScrollView rowsScroll;

    private Ui.Pill libraryPill, account;
    private MiniPlayer mini;
    private String lastData, focusedKey, libraryId, backdropItem;
    private long loadedAt;
    private LinearLayout snappedRow;
    // A held D-pad moves focus faster than text should re-lay out: the title waits for focus to settle.
    private final Debounce heroLater = new Debounce(120), backdropLater = new Debounce(300);
    private final List<String[]> libraries = new ArrayList<>(); // {id, name}
    private boolean loading, askedLogin;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Abs.save(this, getIntent());
        root = new FrameLayout(this);
        root.setClipChildren(false);
        backdrop = new Backdrop(this, root, new int[] {0x8004050E, 0xCC04050E, 0xF004050E},
                GradientDrawable.Orientation.TOP_BOTTOM, 0.45f);

        // Top row, left: the ways into the rest of the library.
        LinearLayout nav = new LinearLayout(this);
        nav.setClipChildren(false);
        addNav(nav, LineIcon.SEARCH, "Search", BrowseActivity.SEARCH);
        addNav(nav, LineIcon.GRID, "All books", BrowseActivity.ALL);
        addNav(nav, LineIcon.LIBRARY, "Series", BrowseActivity.SERIES);
        addNav(nav, LineIcon.USER, "Authors", BrowseActivity.AUTHORS);
        root.addView(nav, Ui.at(Ui.px(Ui.MARGIN), Ui.px(HEADER_TOP), -2, Ui.px(Ui.PILL_H)));

        // Header, left under the nav: the focused book's title with its author and time left tight
        // underneath. Right: the utilities on one line (library, account, clock), and under them the
        // book that's playing, if any. Two columns, so nothing in the header can overlap.
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        heroTitle = Ui.text(this, Ui.px(64), Color.WHITE, Ui.medium);
        heroTitle.setMaxLines(2);
        heroTitle.setEllipsize(TextUtils.TruncateAt.END);
        heroTitle.setLineSpacing(0, 0.95f);
        hero.addView(heroTitle);
        heroMeta = Ui.line(this, Ui.px(28), 0xA6FFFFFF, Ui.regular);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(-1, -2);
        mlp.topMargin = Ui.px(14);
        hero.addView(heroMeta, mlp);
        root.addView(hero, Ui.at(Ui.px(Ui.MARGIN), Ui.px(HERO_TOP), Ui.px(HERO_W), -2));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setGravity(Gravity.END);
        side.setClipChildren(false);
        topPills = new LinearLayout(this);
        topPills.setGravity(Gravity.CENTER_VERTICAL);
        topPills.setClipChildren(false);
        libraryPill = new Ui.Pill(this, LineIcon.LIBRARY, "Library");
        libraryPill.view.setOnClickListener(v -> pickLibrary());
        libraryPill.view.setVisibility(View.GONE);
        topPills.addView(libraryPill.view, new LinearLayout.LayoutParams(-2, Ui.px(Ui.PILL_H)));
        account = new Ui.Pill(this, LineIcon.USER, "Account");
        account.view.setOnClickListener(v -> accountMenu());
        account.view.setVisibility(View.GONE);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(-2, Ui.px(Ui.PILL_H));
        ap.leftMargin = Ui.px(16);
        topPills.addView(account.view, ap);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("H:mm");
        clock.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, Ui.px(40));
        clock.setTextColor(0xD9FFFFFF);
        clock.setTypeface(Ui.light);
        clock.setFontFeatureSettings("tnum");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-2, -2);
        clp.leftMargin = Ui.px(36);
        topPills.addView(clock, clp);
        side.addView(topPills, new LinearLayout.LayoutParams(-2, -2));
        mini = new MiniPlayer(this);
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-2, -2);
        np.topMargin = Ui.px(28);
        side.addView(mini.view, np);
        FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        sp.topMargin = Ui.px(HEADER_TOP);
        sp.rightMargin = Ui.px(Ui.MARGIN);
        root.addView(side, sp);

        // The shelves: one horizontal row each, in a vertical scroll.
        rowsScroll = new ScrollView(this);
        rowsScroll.setVerticalScrollBarEnabled(false);
        rowsScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setClipChildren(false);
        rows.setPadding(0, Ui.px(8), 0, Ui.px(96));
        rowsScroll.addView(rows, new FrameLayout.LayoutParams(-1, -2));
        // The root lets focused tiles and pills grow past their edges, so it doesn't clip. The rows
        // get a frame of their own that does: otherwise scrolled rows draw over the header.
        FrameLayout rowsFrame = new FrameLayout(this);
        rowsFrame.addView(rowsScroll, new FrameLayout.LayoutParams(-1, -1));
        root.addView(rowsFrame, Ui.at(0, Ui.px(ROWS_TOP), -1, -1));

        // Setup help and errors, in the middle of the screen.
        message = Ui.text(this, Ui.px(30), 0xD9FFFFFF, Ui.regular);
        message.setLineSpacing(Ui.px(8), 1f);
        message.setVisibility(View.GONE);
        message.setFocusable(true);
        if (android.os.Build.VERSION.SDK_INT >= 26) message.setDefaultFocusHighlightEnabled(false);
        message.setOnClickListener(v -> {
            if (Abs.configured(this)) load();
            else signIn();
        });
        root.addView(message, Ui.at(Ui.px(Ui.MARGIN), Ui.px(ROWS_TOP + 20), Ui.px(1200), -2));

        setContentView(root);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (Abs.save(this, intent)) lastData = null;
    }

    // Moves the mini player's progress along while this screen is showing.
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            mini.update();
            main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        PlayerService.listener = this;
        main.post(tick);
        load();
    }

    @Override
    protected void onPause() {
        main.removeCallbacks(tick);
        if (PlayerService.listener == this) PlayerService.listener = null;
        super.onPause();
    }

    @Override
    public void changed() {
        mini.update();
    }

    // Leaving Books (the Home button, or Back out of the library) stops the book, saving the place.
    @Override
    protected void onUserLeaveHint() {
        Ui.stopPlayback();
        super.onUserLeaveHint();
    }

    @Override
    public void onBackPressed() {
        Ui.stopPlayback();
        super.onBackPressed();
    }

    // Fetches the libraries, the listener's progress and the chosen library's shelves in one go,
    // and rebuilds the rows only if something changed.
    private void load() {
        if (!Abs.configured(this)) {
            // Straight to sign-in the first time; after Back, a prompt instead of a loop.
            account.view.setVisibility(View.GONE);
            if (!askedLogin) {
                askedLogin = true;
                signIn();
            }
            showMessage("Sign in to Audiobookshelf", "Press OK to sign in.");
            return;
        }
        if (loading) return;
        // Coming back from the player or a browse screen: no need to fetch it all again so soon.
        if (lastData != null && System.currentTimeMillis() - loadedAt < RELOAD_AFTER_MS) return;
        loading = true;
        final String wanted = Abs.prefs(this).getString("library", null);
        Abs.run(() -> {
            JSONObject libs = (JSONObject) Abs.call(this, "GET", "/api/libraries", null);
            JSONArray all = libs.getJSONArray("libraries");
            JSONArray books = new JSONArray();
            String chosen = null;
            for (int i = 0; i < all.length(); i++) {
                JSONObject l = all.getJSONObject(i);
                if (!"book".equals(l.optString("mediaType"))) continue;
                books.put(new JSONObject().put("id", l.getString("id")).put("name", l.optString("name")));
                if (chosen == null || l.getString("id").equals(wanted)) chosen = l.getString("id");
            }
            JSONObject out = new JSONObject().put("libraries", books);
            if (chosen == null) return out;
            out.put("library", chosen);
            JSONObject me = (JSONObject) Abs.call(this, "GET", "/api/me", null);
            JSONObject progress = new JSONObject();
            JSONArray mp = me.optJSONArray("mediaProgress");
            for (int i = 0; mp != null && i < mp.length(); i++) {
                JSONObject p = mp.getJSONObject(i);
                if (p.isNull("episodeId") || p.optString("episodeId").isEmpty())
                    progress.put(p.getString("libraryItemId"), p);
            }
            out.put("progress", progress);
            out.put("user", me.optString("username", ""));
            out.put("shelves", Abs.call(this, "GET", "/api/libraries/" + chosen + "/personalized?limit=20", null));
            return out;
        }, (json, err) -> {
            loading = false;
            if (!Abs.configured(this)) { // the login ran out while loading
                load();
                return;
            }
            if (err != null) {
                if (lastData == null) showMessage("Can't reach Audiobookshelf",
                        err + "\nServer: " + Abs.server(this) + "\nPress OK to try again.");
                return;
            }
            loadedAt = System.currentTimeMillis();
            String data = json.toString();
            if (data.equals(lastData)) return;
            lastData = data;
            build((JSONObject) json);
        });
    }

    private void showMessage(String title, String body) {
        rows.removeAllViews();
        lastData = null;
        heroTitle.setText(title);
        heroMeta.setText("");
        message.setText(body);
        message.setVisibility(View.VISIBLE);
        message.requestFocus();
    }

    private void build(JSONObject data) {
        message.setVisibility(View.GONE);
        libraries.clear();
        JSONArray libs = data.optJSONArray("libraries");
        for (int i = 0; libs != null && i < libs.length(); i++) {
            JSONObject l = libs.optJSONObject(i);
            libraries.add(new String[] {l.optString("id"), l.optString("name")});
        }
        libraryId = data.optString("library", null);
        String libraryName = "";
        for (String[] l : libraries) if (l[0].equals(libraryId)) libraryName = l[1];
        libraryPill.view.setVisibility(libraries.size() > 1 ? View.VISIBLE : View.GONE);
        libraryPill.set(LineIcon.LIBRARY, libraryName.isEmpty() ? "Library" : libraryName);
        String user = data.optString("user", "");
        account.view.setVisibility(View.VISIBLE);
        account.set(LineIcon.USER, user.isEmpty() ? "Account" : user);
        if (libraryId == null) {
            showMessage("No audiobook library", "Audiobookshelf has no book libraries for this user.");
            return;
        }

        JSONObject progress = data.optJSONObject("progress");
        if (progress == null) progress = new JSONObject();
        Abs.progress = progress;
        rows.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        rows.removeAllViews();
        snappedRow = null;
        View restore = null, first = null;
        JSONArray shelves = data.optJSONArray("shelves");
        for (int s = 0; shelves != null && s < shelves.length(); s++) {
            JSONObject shelf = shelves.optJSONObject(s);
            if (shelf == null || !"book".equals(shelf.optString("type"))) continue;
            JSONArray items = shelf.optJSONArray("entities");
            LinearLayout row = null;
            for (int i = 0; items != null && i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                JSONObject media = item == null ? null : item.optJSONObject("media");
                if (media == null || media.optDouble("duration", 0) <= 0) continue; // ebook only: nothing to play
                if (row == null) row = addRow(shelf.optString("label", ""));
                String key = shelf.optString("id") + "/" + item.optString("id");
                View tile = addTile(row, key, item, media, progress.optJSONObject(item.optString("id")));
                if (first == null) first = tile;
                if (key.equals(focusedKey)) restore = tile;
            }
        }
        rows.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        if (first == null) {
            showMessage("Nothing to play yet", "This library has no audiobooks.");
            return;
        }
        (restore != null ? restore : first).requestFocus();
    }

    private LinearLayout addRow(String label) {
        TextView head = Ui.line(this, Ui.px(30), 0xCCFFFFFF, Ui.medium);
        head.setText(label);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(-2, -2);
        hp.leftMargin = Ui.px(Ui.MARGIN);
        hp.topMargin = rows.getChildCount() > 0 ? Ui.px(28) : 0;
        rows.addView(head, hp);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setClipChildren(false);
        scroll.setClipToPadding(false);
        LinearLayout row = new LinearLayout(this);
        row.setClipChildren(false);
        row.setClipToPadding(false);
        row.setPadding(Ui.px(Ui.MARGIN), Ui.px(26), Ui.px(Ui.MARGIN), Ui.px(26));
        scroll.addView(row, new FrameLayout.LayoutParams(-2, -2));
        rows.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
        return row;
    }

    // A cover tile (see Tile) with a thin progress bar for books in progress.
    private View addTile(final LinearLayout row, final String key, final JSONObject item, JSONObject media,
                         JSONObject progress) {
        final String itemId = item.optString("id");
        JSONObject meta = media.optJSONObject("metadata");
        final String title = meta != null ? meta.optString("title", "") : "";
        final String author = meta != null ? meta.optString("authorName", "") : "";
        double duration = media.optDouble("duration", 0);
        boolean finished = progress != null && progress.optBoolean("isFinished");
        double done = progress != null ? progress.optDouble("progress", 0) : 0;
        final String metaLine = author + (author.isEmpty() ? "" : "  ·  ")
                + (finished ? "Finished" : done > 0 ? Ui.duration(duration * (1 - done)) + " left" : Ui.duration(duration));

        int size = Ui.px(TILE);
        final Tile card = new Tile(this, size, size, false, 1.1f);
        card.bind(title, finished ? 1 : done);
        card.setTag(key);
        final android.graphics.Bitmap[] loaded = new android.graphics.Bitmap[1];
        Abs.cover(this, itemId, size, card.cover, (bmp, tint) -> {
            loaded[0] = bmp;
            card.setTint(tint);
            if (card.isFocused()) showBackdrop(itemId, bmp);
        });
        card.setOnFocusChangeListener((v, has) -> {
            card.focus(has, false);
            if (!has) return;
            focusedKey = key;
            heroLater.run(() -> showHero(title, metaLine));
            if (loaded[0] != null) showBackdrop(itemId, loaded[0]);
            snapToRow(row);
        });
        card.setOnClickListener(v -> {
            Intent i = new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerService.EXTRA_ITEM, itemId)
                    .putExtra(PlayerActivity.EXTRA_TITLE, title)
                    .putExtra(PlayerActivity.EXTRA_AUTHOR, author);
            startActivity(Ui.inApp(i), ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.getWidth(), v.getHeight()).toBundle());
        });
        // Holding OK on a book offers its progress and its offline copy.
        Ui.okHold(card, () -> BookMenu.show(this, itemId, title, () -> {
            lastData = null;
            load();
        }));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        if (row.getChildCount() > 0) lp.leftMargin = Ui.px(GAP);
        row.addView(card, lp);
        return card;
    }

    // Scrolls so the focused row's label sits at the top of the list, rather than the minimum that
    // shows the tile (which leaves the label sliced at the edge).
    private void snapToRow(LinearLayout row) {
        if (row == snappedRow) return; // moving along a row: nothing to scroll
        snappedRow = row;
        int index = rows.indexOfChild((View) row.getParent());
        if (index < 1) return;
        final View label = rows.getChildAt(index - 1);
        rowsScroll.post(() -> rowsScroll.smoothScrollTo(0, Math.max(0, label.getTop() - Ui.px(8))));
    }

    // Crossfades the big title and its line to the newly focused book.
    private void showHero(final String title, final String meta) {
        if (title.contentEquals(heroTitle.getText()) && heroTitle.getAlpha() > 0f) {
            heroMeta.setText(meta);
            return;
        }
        for (final View v : new View[] {heroTitle, heroMeta}) {
            v.animate().cancel();
            v.animate().alpha(0f).setDuration(v.getAlpha() > 0f ? Ui.NAME_OUT_MS : 0).withEndAction(() -> {
                if (v == heroTitle) heroTitle.setText(title);
                else heroMeta.setText(meta);
                v.setTranslationY(Ui.px(10));
                v.animate().alpha(1f).translationY(0).setInterpolator(Ui.EASE).setDuration(Ui.NAME_IN_MS).start();
            }).start();
        }
    }

    // A wash of the focused cover's colours behind everything, changed once focus settles so
    // moving along a row doesn't re-render it for every tile.
    private void showBackdrop(String itemId, final android.graphics.Bitmap cover) {
        if (itemId.equals(backdropItem)) return;
        backdropItem = itemId;
        backdropLater.run(() -> backdrop.show(cover));
    }

    private void signIn() {
        startActivity(Ui.inApp(new Intent(this, LoginActivity.class)));
    }

    private void addNav(LinearLayout nav, LineIcon.Shape icon, String label, final String mode) {
        Ui.Pill p = new Ui.Pill(this, icon, label);
        p.view.setOnClickListener(v -> {
            if (libraryId == null) return;
            startActivity(Ui.inApp(new Intent(this, BrowseActivity.class)
                    .putExtra(BrowseActivity.EXTRA_MODE, mode)
                    .putExtra(BrowseActivity.EXTRA_LIBRARY, libraryId)));
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.px(Ui.PILL_H));
        if (nav.getChildCount() > 0) lp.leftMargin = Ui.px(16);
        nav.addView(p.view, lp);
    }

    private void accountMenu() {
        String who = Abs.user(this);
        List<String> items = new ArrayList<>();
        items.add("Sign out");
        Ui.sheet(this, (who.isEmpty() ? "Signed in" : who) + " on " + Abs.server(this).replaceFirst("^https?://", ""),
                items, -1, which -> {
                    if (PlayerService.instance != null) PlayerService.instance.stop();
                    Abs.signOut(this);
                    lastData = null;
                    focusedKey = null;
                    askedLogin = false;
                    load();
                });
    }

    private void pickLibrary() {
        List<String> names = new ArrayList<>();
        int current = -1;
        for (int i = 0; i < libraries.size(); i++) {
            names.add(libraries.get(i)[1]);
            if (libraries.get(i)[0].equals(libraryId)) current = i;
        }
        Ui.sheet(this, "Library", names, current, which -> {
            Abs.prefs(this).edit().putString("library", libraries.get(which)[0]).apply();
            focusedKey = null;
            lastData = null;
            load();
        });
    }
}
