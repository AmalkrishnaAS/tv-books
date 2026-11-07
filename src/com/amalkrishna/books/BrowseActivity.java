package com.amalkrishna.books;

import android.app.Activity;
import android.app.ActivityOptions;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// Browsing the whole library: every book (sortable), series, authors, one series or author's books,
// and search. One screen, a grid of cover tiles that recycles its views (so a large library costs
// no more than a screenful) and loads further pages as it nears the end.
public class BrowseActivity extends Activity {
    static final String EXTRA_MODE = "mode", EXTRA_LIBRARY = "library", EXTRA_ID = "id", EXTRA_TITLE = "title";
    static final String ALL = "all", SERIES = "series", AUTHORS = "authors", SEARCH = "search",
            SERIES_BOOKS = "seriesBooks", AUTHOR_BOOKS = "authorBooks";

    private static final int TILE = 232, COLUMNS = 6, PAGE = 60, CAPTION = 64;
    private static final String[] SORT_NAMES = {"Title", "Author", "Recently added", "Length"};
    private static final String[] SORT_KEYS = {"media.metadata.title", "media.metadata.authorName", "addedAt", "media.duration"};
    private static final boolean[] SORT_DESC = {false, false, true, true};

    private String mode, library, id;
    private GridView grid;
    private TextView heading, detail, status;
    private Ui.Pill sortPill;
    private EditText query;
    private LinearLayout matches;
    private Backdrop backdrop;
    private final List<JSONObject> items = new ArrayList<>();
    private final Adapter adapter = new Adapter();
    private final Debounce headerLater = new Debounce(120), backdropLater = new Debounce(300),
            searchLater = new Debounce(350);
    private int page, total = -1, selected = -1, searchSeq;
    private boolean loading;
    private View selectedView;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Ui.init(this);
        Intent in = getIntent();
        mode = in.getStringExtra(EXTRA_MODE);
        library = in.getStringExtra(EXTRA_LIBRARY);
        id = in.getStringExtra(EXTRA_ID);
        if (mode == null || library == null) {
            finish();
            return;
        }
        FrameLayout root = new FrameLayout(this);
        backdrop = new Backdrop(this, root, new int[] {0x8004050E, 0xCC04050E, 0xF004050E},
                GradientDrawable.Orientation.TOP_BOTTOM, 0.45f);

        boolean search = SEARCH.equals(mode);
        heading = Ui.line(this, Ui.px(56), Color.WHITE, Ui.medium);
        heading.setText(titleFor(in.getStringExtra(EXTRA_TITLE)));
        root.addView(heading, Ui.at(Ui.px(Ui.MARGIN), Ui.px(72), Ui.px(1100), -2));
        detail = Ui.line(this, Ui.px(28), 0xA6FFFFFF, Ui.regular);
        root.addView(detail, Ui.at(Ui.px(Ui.MARGIN), Ui.px(search ? 268 : 150), Ui.px(1300), -2));

        if (ALL.equals(mode)) {
            sortPill = new Ui.Pill(this, LineIcon.SORT, SORT_NAMES[sortIndex()]);
            sortPill.view.setOnClickListener(v -> pickSort());
            FrameLayout.LayoutParams sp = new FrameLayout.LayoutParams(-2, Ui.px(Ui.PILL_H), Gravity.TOP | Gravity.END);
            sp.topMargin = Ui.px(76);
            sp.rightMargin = Ui.px(Ui.MARGIN);
            root.addView(sortPill.view, sp);
        }
        int gridTop = 210;
        if (search) {
            query = Ui.field(this, "Title, author, series or narrator", EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    EditorInfo.IME_ACTION_SEARCH);
            query.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
                @Override public void afterTextChanged(Editable s) { searchLater.run(BrowseActivity.this::search); }
            });
            root.addView(query, Ui.at(Ui.px(Ui.MARGIN), Ui.px(160), Ui.px(1100), Ui.px(84)));
            // Matching series and authors, as pills.
            HorizontalScrollView strip = new HorizontalScrollView(this);
            strip.setHorizontalScrollBarEnabled(false);
            strip.setClipChildren(false);
            strip.setClipToPadding(false);
            matches = new LinearLayout(this);
            matches.setClipChildren(false);
            matches.setPadding(Ui.px(Ui.MARGIN), Ui.px(8), Ui.px(Ui.MARGIN), Ui.px(8));
            strip.addView(matches);
            root.addView(strip, Ui.at(0, Ui.px(310), -1, Ui.px(Ui.PILL_H + 16)));
            gridTop = 400;
        }

        status = Ui.text(this, Ui.px(28), 0xB3FFFFFF, Ui.regular);

        grid = new GridView(this);
        grid.setNumColumns(COLUMNS);
        grid.setColumnWidth(Ui.px(TILE));
        grid.setStretchMode(GridView.STRETCH_SPACING);
        grid.setVerticalSpacing(Ui.px(28));
        grid.setPadding(Ui.px(Ui.MARGIN), Ui.px(28), Ui.px(Ui.MARGIN), Ui.px(96));
        grid.setClipToPadding(false);
        grid.setClipChildren(false);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT));
        grid.setDrawSelectorOnTop(false);
        grid.setVerticalScrollBarEnabled(false);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setAdapter(adapter);
        grid.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long rowId) {
                select(view, position);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { select(null, -1); }
        });
        grid.setOnFocusChangeListener((v, has) -> {
            if (selectedView != null) tileOf(selectedView).focus(has, false);
        });
        grid.setOnItemClickListener((parent, view, position, rowId) -> open(position, view));
        // Holding OK on a book tile offers its progress and its offline copy.
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= items.size()) return false;
            JSONObject o = items.get(position);
            if (o.optJSONObject("media") == null) return false; // authors and series aren't books
            JSONObject meta = o.optJSONObject("media").optJSONObject("metadata");
            BookMenu.show(this, o.optString("id"),
                    meta == null ? "" : meta.optString("title", ""), this::restart);
            return true;
        });
        grid.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(AbsListView v, int scrollState) {}
            @Override public void onScroll(AbsListView v, int first, int visible, int count) {
                if (count > 0 && first + visible >= count - 12) loadMore();
            }
        });
        root.addView(grid, Ui.at(0, Ui.px(gridTop), -1, -1));
        root.addView(status, Ui.at(Ui.px(Ui.MARGIN), Ui.px(gridTop + 40), Ui.px(1400), -2));

        setContentView(root);
        if (search) {
            query.requestFocus();
            status.setText("Type at least two letters.");
        } else {
            loadMore();
        }
    }

    private String titleFor(String given) {
        switch (mode) {
            case ALL: return "All books";
            case SERIES: return "Series";
            case AUTHORS: return "Authors";
            case SEARCH: return "Search";
            default: return given != null ? given : "";
        }
    }

    @Override
    protected void onUserLeaveHint() {
        Ui.stopPlayback();
        super.onUserLeaveHint();
    }

    @Override
    protected void onDestroy() {
        headerLater.cancel();
        backdropLater.cancel();
        searchLater.cancel();
        super.onDestroy();
    }

    // Loads the next page for this view, unless one is loading or everything's here.
    private void loadMore() {
        if (loading || SEARCH.equals(mode) || (total >= 0 && page * PAGE >= total)) return;
        loading = true;
        if (items.isEmpty()) status.setText("Loading…");
        final String path = pagePath(page);
        final int at = page;
        Abs.get(this, path, (json, err) -> {
            loading = false;
            if (isFinishing()) return;
            if (err != null || !(json instanceof JSONObject)) {
                if (items.isEmpty()) status.setText("Couldn't load this: " + (err != null ? err : "no data"));
                return;
            }
            JSONObject r = (JSONObject) json;
            total = r.optInt("total", 0);
            page = at + 1;
            JSONArray results = r.optJSONArray("results");
            int before = items.size();
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject o = results.optJSONObject(i);
                if (o != null && playable(o)) items.add(o);
            }
            adapter.notifyDataSetChanged();
            if (before == 0) firstShown();
            // Pages that were all ebooks add nothing; keep going if there's more.
            if (items.size() == before && page * PAGE < total) loadMore();
        });
    }

    private String pagePath(int p) {
        String base = "/api/libraries/" + library;
        String paging = "limit=" + PAGE + "&page=" + p + "&minified=1";
        switch (mode) {
            case ALL: {
                int s = sortIndex();
                return base + "/items?" + paging + "&sort=" + SORT_KEYS[s] + "&desc=" + (SORT_DESC[s] ? 1 : 0);
            }
            case SERIES: return base + "/series?" + paging + "&sort=name";
            case AUTHORS: return base + "/authors?" + paging + "&sort=name";
            case SERIES_BOOKS: return base + "/items?" + paging + "&sort=sequence&filter=" + Abs.filter("series", id);
            default: return base + "/items?" + paging + "&sort=media.metadata.title&filter=" + Abs.filter("authors", id);
        }
    }

    // Books need audio; series need a book with audio; authors are always shown.
    private boolean playable(JSONObject o) {
        if (AUTHORS.equals(mode)) return o.optInt("numBooks", 1) > 0;
        if (SERIES.equals(mode)) return firstPlayable(o.optJSONArray("books")) != null;
        JSONObject media = o.optJSONObject("media");
        return media != null && media.optDouble("duration", 0) > 0;
    }

    private static JSONObject firstPlayable(JSONArray books) {
        for (int i = 0; books != null && i < books.length(); i++) {
            JSONObject b = books.optJSONObject(i);
            JSONObject m = b == null ? null : b.optJSONObject("media");
            if (m != null && m.optDouble("duration", 0) > 0) return b;
        }
        return null;
    }

    // Focus starts on the first book, or in a series on the first one not yet finished.
    private void firstShown() {
        status.setText(items.isEmpty() ? "Nothing here yet." : "");
        if (items.isEmpty()) return;
        int start = 0;
        if (SERIES_BOOKS.equals(mode)) {
            for (int i = 0; i < items.size(); i++) {
                JSONObject p = Abs.progress.optJSONObject(items.get(i).optString("id"));
                if (p == null || !p.optBoolean("isFinished")) { start = i; break; }
            }
        }
        grid.requestFocus();
        grid.setSelection(start);
    }

    private void select(View view, int position) {
        if (selectedView != null && selectedView != view) tileOf(selectedView).focus(false, false);
        selectedView = view;
        selected = position;
        if (view == null || position < 0 || position >= items.size()) return;
        if (grid.hasFocus()) tileOf(view).focus(true, false);
        final JSONObject o = items.get(position);
        headerLater.run(() -> showDetail(o));
        backdropLater.run(() -> {
            final String coverId = coverId(o);
            if (coverId == null) return;
            Abs.cover(this, coverId, Ui.px(TILE), null, (bmp, tint) -> backdrop.show(bmp));
        });
    }

    private void showDetail(JSONObject o) {
        if (AUTHORS.equals(mode)) {
            int n = o.optInt("numBooks", 0);
            detail.setText(o.optString("name") + "  ·  " + n + (n == 1 ? " book" : " books"));
        } else if (SERIES.equals(mode)) {
            JSONArray b = o.optJSONArray("books");
            int n = b == null ? 0 : b.length();
            detail.setText(o.optString("name") + "  ·  " + n + (n == 1 ? " book" : " books"));
        } else {
            detail.setText(bookLine(o));
        }
    }

    // "Title  ·  Author  ·  3h 12m left"
    private static String bookLine(JSONObject item) {
        JSONObject media = item.optJSONObject("media");
        JSONObject meta = media == null ? null : media.optJSONObject("metadata");
        String title = meta == null ? "" : meta.optString("title", "");
        String author = meta == null ? "" : meta.optString("authorName", "");
        double duration = media == null ? 0 : media.optDouble("duration", 0);
        JSONObject p = Abs.progress.optJSONObject(item.optString("id"));
        boolean finished = p != null && p.optBoolean("isFinished");
        double done = p != null ? p.optDouble("progress", 0) : 0;
        String time = finished ? "Finished" : done > 0 ? Ui.duration(duration * (1 - done)) + " left" : Ui.duration(duration);
        return TextUtils.join("  ·  ", nonEmpty(title, author, time));
    }

    private static List<String> nonEmpty(String... parts) {
        List<String> out = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isEmpty()) out.add(p);
        return out;
    }

    private String coverId(JSONObject o) {
        if (AUTHORS.equals(mode)) return null;
        if (SERIES.equals(mode)) {
            JSONObject b = firstPlayable(o.optJSONArray("books"));
            return b == null ? null : b.optString("id");
        }
        return o.optString("id");
    }

    private void open(int position, View from) {
        if (position < 0 || position >= items.size()) return;
        JSONObject o = items.get(position);
        if (SERIES.equals(mode)) {
            openList(SERIES_BOOKS, o.optString("id"), o.optString("name"));
        } else if (AUTHORS.equals(mode)) {
            openList(AUTHOR_BOOKS, o.optString("id"), o.optString("name"));
        } else {
            JSONObject meta = o.optJSONObject("media") == null ? null : o.optJSONObject("media").optJSONObject("metadata");
            Intent i = new Intent(this, PlayerActivity.class)
                    .putExtra(PlayerService.EXTRA_ITEM, o.optString("id"))
                    .putExtra(PlayerActivity.EXTRA_TITLE, meta == null ? "" : meta.optString("title"))
                    .putExtra(PlayerActivity.EXTRA_AUTHOR, meta == null ? "" : meta.optString("authorName"));
            Bundle zoom = from == null ? null
                    : ActivityOptions.makeScaleUpAnimation(from, 0, 0, from.getWidth(), from.getHeight()).toBundle();
            startActivity(Ui.inApp(i), zoom);
        }
    }

    private void openList(String listMode, String listId, String title) {
        startActivity(Ui.inApp(new Intent(this, BrowseActivity.class)
                .putExtra(EXTRA_MODE, listMode)
                .putExtra(EXTRA_LIBRARY, library)
                .putExtra(EXTRA_ID, listId)
                .putExtra(EXTRA_TITLE, title)));
    }

    private int sortIndex() {
        int s = Abs.prefs(this).getInt("sort", 0);
        return s >= 0 && s < SORT_KEYS.length ? s : 0;
    }

    private void pickSort() {
        List<String> names = new ArrayList<>();
        for (String n : SORT_NAMES) names.add(n);
        Ui.sheet(this, "Sort by", names, sortIndex(), which -> {
            if (which == sortIndex()) return;
            Abs.prefs(this).edit().putInt("sort", which).apply();
            sortPill.set(LineIcon.SORT, SORT_NAMES[which]);
            restart();
        });
    }

    private void restart() {
        items.clear();
        page = 0;
        total = -1;
        selectedView = null;
        adapter.notifyDataSetChanged();
        loadMore();
    }

    // Search: after typing pauses (debounced), with responses to older queries ignored.
    private void search() {
        final String q = query.getText().toString().trim();
        final int seq = ++searchSeq;
        if (q.length() < 2) {
            items.clear();
            matches.removeAllViews();
            adapter.notifyDataSetChanged();
            status.setText("Type at least two letters.");
            return;
        }
        status.setText(items.isEmpty() ? "Searching…" : "");
        String encoded;
        try {
            encoded = URLEncoder.encode(q, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return;
        }
        Abs.get(this, "/api/libraries/" + library + "/search?limit=40&q=" + encoded, (json, err) -> {
            if (seq != searchSeq || isFinishing()) return; // a newer search is on its way
            items.clear();
            matches.removeAllViews();
            if (err != null || !(json instanceof JSONObject)) {
                adapter.notifyDataSetChanged();
                status.setText("Search failed: " + (err != null ? err : "no data"));
                return;
            }
            JSONObject r = (JSONObject) json;
            JSONArray books = r.optJSONArray("book");
            for (int i = 0; books != null && i < books.length(); i++) {
                JSONObject b = books.optJSONObject(i);
                JSONObject item = b == null ? null : b.optJSONObject("libraryItem");
                if (item != null && playable(item)) items.add(item);
            }
            JSONArray series = r.optJSONArray("series");
            for (int i = 0; series != null && i < series.length(); i++) {
                JSONObject s = series.optJSONObject(i);
                JSONObject info = s == null ? null : s.optJSONObject("series");
                if (info != null) addMatch(LineIcon.LIBRARY, info.optString("name"), SERIES_BOOKS, info.optString("id"));
            }
            JSONArray authors = r.optJSONArray("authors");
            for (int i = 0; authors != null && i < authors.length(); i++) {
                JSONObject a = authors.optJSONObject(i);
                if (a != null) addMatch(LineIcon.USER, a.optString("name"), AUTHOR_BOOKS, a.optString("id"));
            }
            adapter.notifyDataSetChanged();
            status.setText(items.isEmpty() && matches.getChildCount() == 0 ? "No matches for “" + q + "”." : "");
        });
    }

    private void addMatch(LineIcon.Shape icon, final String name, final String listMode, final String listId) {
        Ui.Pill p = new Ui.Pill(this, icon, name);
        p.view.setOnClickListener(v -> openList(listMode, listId, name));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, Ui.px(Ui.PILL_H));
        if (matches.getChildCount() > 0) lp.leftMargin = Ui.px(16);
        matches.addView(p.view, lp);
    }

    private static Tile tileOf(View cell) {
        return (Tile) ((ViewGroup) cell).getChildAt(0);
    }

    // The grid's cells: a tile and a one-line caption, recycled as the grid scrolls.
    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convert, ViewGroup parent) {
            LinearLayout cell = (LinearLayout) convert;
            boolean round = AUTHORS.equals(mode);
            if (cell == null) {
                cell = new LinearLayout(BrowseActivity.this);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setClipChildren(false);
                Tile tile = new Tile(BrowseActivity.this, Ui.px(TILE), Ui.px(TILE), round, 1.08f);
                tile.setFocusable(false); // the grid handles focus and selection
                cell.addView(tile, new LinearLayout.LayoutParams(Ui.px(TILE), Ui.px(TILE)));
                TextView caption = Ui.line(BrowseActivity.this, Ui.px(22), 0x99FFFFFF, Ui.regular);
                caption.setGravity(round ? Gravity.CENTER_HORIZONTAL : Gravity.START);
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
                cp.topMargin = Ui.px(14);
                cell.addView(caption, cp);
                cell.setLayoutParams(new AbsListView.LayoutParams(Ui.px(TILE), Ui.px(TILE + CAPTION)));
            }
            final Tile tile = tileOf(cell);
            TextView caption = (TextView) cell.getChildAt(1);
            JSONObject o = items.get(position);
            if (AUTHORS.equals(mode)) {
                String name = o.optString("name");
                tile.bind(initials(name), 0);
                caption.setText(name);
                if (!o.optString("imagePath").isEmpty() && !o.isNull("imagePath"))
                    Abs.authorImage(BrowseActivity.this, o.optString("id"), Ui.px(TILE), tile.cover, (b, t) -> tile.setTint(t));
                else tile.cover.setTag(null);
            } else if (SERIES.equals(mode)) {
                JSONObject first = firstPlayable(o.optJSONArray("books"));
                caption.setText(o.optString("name"));
                tile.bind(o.optString("name"), 0);
                if (first != null) Abs.cover(BrowseActivity.this, first.optString("id"), Ui.px(TILE), tile.cover,
                        (b, t) -> tile.setTint(t));
            } else {
                JSONObject media = o.optJSONObject("media");
                JSONObject meta = media == null ? null : media.optJSONObject("metadata");
                String title = meta == null ? "" : meta.optString("title", "");
                JSONObject p = Abs.progress.optJSONObject(o.optString("id"));
                double done = p == null ? 0 : p.optBoolean("isFinished") ? 1 : p.optDouble("progress", 0);
                tile.bind(title, done);
                String seq = meta == null ? "" : sequence(meta.optString("seriesName", ""));
                caption.setText(SERIES_BOOKS.equals(mode) && !seq.isEmpty() ? "Book " + seq + "  ·  " + title : title);
                Abs.cover(BrowseActivity.this, o.optString("id"), Ui.px(TILE), tile.cover, (b, t) -> tile.setTint(t));
            }
            // A recycled cell takes on the focus state of the position it now shows.
            tile.focus(position == selected && grid.hasFocus(), true);
            if (position == selected) selectedView = cell;
            return cell;
        }
    }

    // "The Expanse #3" -> "3"
    private static String sequence(String seriesName) {
        int hash = seriesName.lastIndexOf('#');
        return hash >= 0 ? seriesName.substring(hash + 1).trim() : "";
    }

    private static String initials(String name) {
        StringBuilder out = new StringBuilder();
        for (String part : name.trim().split("\\s+")) {
            if (!part.isEmpty() && out.length() < 2) out.append(Character.toUpperCase(part.charAt(0)));
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }
}
