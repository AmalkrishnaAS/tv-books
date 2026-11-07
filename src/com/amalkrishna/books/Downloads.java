package com.amalkrishna.books;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

// Books kept in the TV's own storage, so they play when the server doesn't answer. The
// Audiobookshelf playback session is repurposed as the source of truth (the fields the
// player/UI already read), and everything in it needed to play is copied out: the audio
// track files, chapters, duration, title/author and the position of the download. A
// downloaded book plays with no session and no server: PlayerService reads the copied
// meta.json instead, saves the position here, and mirrors it back to the server
// best-effort when it's reachable again.
final class Downloads {
    interface Stat { void stat(String text); }

    private static final ExecutorService RUN = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicReference<String> busy = new AtomicReference<>();

    private Downloads() {}

    // status: files/total as they download, main thread. ended(ok, err): when it all resolves.
    static void download(final Context c, final String itemId, final Stat status, final Abs.Done ended) {
        if (!busy.compareAndSet(null, itemId)) {
            if (ended != null) MAIN.post(() -> ended.got(null, "A book is already downloading"));
            return;
        }
        RUN.execute(() -> {
            String error = null;
            try {
                run(c, itemId, status);
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            } finally {
                boolean done = error == null;
                final String err = error;
                busy.set(null);
                if (!done) rm(dir(c, itemId)); // a part-downloaded book is no book
                if (ended != null) MAIN.post(() -> ended.got(null, done ? null : err));
            }
        });
    }

    static boolean downloading(String itemId) { return itemId != null && itemId.equals(busy.get()); }

    // One whole book: opens a playback session, downloads each track, writes meta.json, closes
    // the session. Blocking: on the background thread only.
    private static void run(Context c, String itemId, final Stat status) throws IOException, JSONException {
        JSONObject body = new JSONObject()
                .put("clientName", "Books for Android TV")
                .put("clientVersion", "1.1.1")
                .put("deviceId", Abs.deviceId(c))
                .put("forceDirectPlay", true)
                .put("forceTranscode", false)
                .put("mediaPlayer", "android-mediaplayer");
        Object r = Abs.call(c, "POST", "/api/items/" + itemId + "/play", body);
        if (!(r instanceof JSONObject)) throw new IOException("No session");
        JSONObject s = (JSONObject) r;
        JSONArray at = s.getJSONArray("audioTracks");
        JSONArray ch = s.optJSONArray("chapters");

        // Copy each track verbatim: the URLs are the same files the player streams.
        JSONArray files = new JSONArray();
        File d = dir(c, itemId);
        for (int i = 0; i < at.length(); i++) {
            JSONObject t = at.getJSONObject(i);
            final int n = i + 1;
            MAIN.post(() -> { if (status != null) status.stat(n + "/" + at.length()); });
            String file = "t" + n + ".audio";
            save(c, t.getString("contentUrl"), new File(d, file));
            JSONObject f = new JSONObject()
                    .put("f", file)
                    .put("start", t.optDouble("startOffset", 0))
                    .put("length", t.optDouble("duration", 0));
            files.put(f);
        }

        JSONObject meta = new JSONObject()
                .put("id", itemId)
                .put("title", s.optString("displayTitle", ""))
                .put("author", s.optString("displayAuthor", ""))
                .put("duration", s.optDouble("duration", 0))
                .put("pos", s.optDouble("currentTime", 0))
                .put("finished", false)
                .put("tracks", files)
                .put("chapters", ch == null ? new JSONArray() : ch);
        write(c, "meta.js", meta);

        try { Abs.call(c, "POST", "/api/session/" + s.getString("id") + "/close", null); } catch (IOException ignored) {
            // The session also closes by itself when it goes unreported; keep the download.
        }
    }

    static void saveProgress(Context c, String itemId, double seconds, boolean finished) {
        try {
            JSONObject m = meta(c, itemId);
            if (m == null) return;
            m.put("pos", seconds).put("finished", finished);
            write(c, "meta.js", m);
        } catch (IOException | JSONException ignored) {
            // Losing the position sometimes is fine; the book itself is intact.
        }
    }

    // meta.js is the downloaded book's index: read for the player, "forget progress", removing.
    static JSONObject meta(Context c, String itemId) {
        try (InputStream in = new FileInputStream(new File(dir(c, itemId), "meta.js"))) {
            return (JSONObject) new org.json.JSONTokener(new String(read(in), StandardCharsets.UTF_8)).nextValue();
        } catch (Exception e) {
            return null;
        }
    }

    static void remove(Context c, String itemId) {
        rm(dir(c, itemId));
    }

    // A book's folder: next to the app's own media files (Android/data/<apps>/files/Music), so
    // multi-GB books land on the TV's media partition, not the small data one. Still private to
    // this app — no other app can read it, and it's deleted with the app, like all offline media.
    static File dir(Context c, String itemId) {
        File base = c.getExternalFilesDir(android.os.Environment.DIRECTORY_MUSIC);
        if (base == null) base = new File(c.getFilesDir(), "music");
        return new File(base, itemId);
    }

    private static void write(Context c, String name, JSONObject body) throws IOException, JSONException {
        File f = new File(dir(c, body.getString("id")), name);
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    // One HTTP GET whose response is a file's bytes, not JSON (the covers use open() in Abs).
    private static void save(Context c, String path, File out) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(Abs.server(c) + path).openConnection();
        try {
            http.setConnectTimeout(8000);
            http.setReadTimeout(Math.max(20000, 5000 * 60));
            String t = Abs.token(c);
            if (!t.isEmpty()) http.setRequestProperty("Authorization", "Bearer " + t);
            int code = http.getResponseCode();
            if (code == 401) throw new IOException("Not signed in");
            if (code >= 400) throw new IOException("The server answered " + code);
            try (InputStream in = http.getInputStream(); OutputStream f = new FileOutputStream(out)) {
                byte[] buf = new byte[16384];
                for (int n; (n = in.read(buf)) > 0; ) f.write(buf, 0, n);
            }
        } finally {
            http.disconnect();
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    private static void rm(File f) {
        if (f == null) return;
        File[] all = f.listFiles();
        if (all != null) for (File k : all) rm(k);
        f.delete();
    }
}
