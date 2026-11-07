package com.amalkrishna.books;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

// The Audiobookshelf server: its address and how we're signed in, JSON calls and covers.
// Sign in on the TV (LoginActivity: username and password), or send an API key from a computer:
//   am start -n com.amalkrishna.books/.MainActivity --es server http://host:13378 --es key <API key>
// API calls run one at a time on a background thread, covers on their own threads; both answer on
// the main thread. The token goes in the Authorization header, or as ?token= for audio streams.
final class Abs {
    private static final String PREFS = "server";
    private static final ExecutorService API = Executors.newSingleThreadExecutor();
    // Images load newest request first: when scrolling fast, the covers now on screen come before
    // the ones already scrolled past (whose requests are dropped once their tile is reused).
    private static final ExecutorService IMAGES = new ThreadPoolExecutor(3, 3, 0, TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<Runnable>() {
                private static final long serialVersionUID = 1L;
                @Override public boolean offer(Runnable r) { return offerFirst(r); }
            });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Covers are decoded as RGB_565 (no alpha) at the size they're shown; the cache gets an eighth
    // of the app's memory allowance.
    private static LruCache<String, Bitmap> covers;
    private static final Map<String, Integer> TINTS = new HashMap<>();

    interface Done { void got(Object json, String error); }
    interface Call { Object run() throws IOException, JSONException; }

    private Abs() {}

    // Saves a server and API key passed with --es server/--es key. Returns true if they changed.
    static boolean save(Context c, Intent i) {
        String server = i == null ? null : i.getStringExtra("server");
        String key = i == null ? null : i.getStringExtra("key");
        if (server == null && key == null) return false;
        SharedPreferences.Editor e = prefs(c).edit();
        if (server != null) e.putString("server", normalize(server));
        if (key != null) e.putString("key", key.trim()).remove("access").remove("refresh");
        e.apply();
        return true;
    }

    // Signed in: with an API key (it doesn't expire), or with a password login's refresh token.
    static final String CLIENT_VERSION = "1.1.2"; // what playback sessions and downloads report
    static boolean configured(Context c) {
        SharedPreferences p = prefs(c);
        return !server(c).isEmpty() && (!p.getString("key", "").isEmpty() || !p.getString("refresh", "").isEmpty());
    }

    static String server(Context c) { return prefs(c).getString("server", ""); }
    static String user(Context c) { return prefs(c).getString("user", ""); }

    // The token for the next request: the API key, or the current access token.
    static String token(Context c) {
        SharedPreferences p = prefs(c);
        String key = p.getString("key", "");
        return !key.isEmpty() ? key : p.getString("access", "");
    }

    // "192.168.1.20:13378/" becomes "http://192.168.1.20:13378".
    static String normalize(String server) {
        String s = server.trim().replaceAll("/+$", "");
        if (!s.isEmpty() && !s.matches("(?i)^https?://.*")) s = "http://" + s;
        return s;
    }

    static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, 0);
    }

    // A stable id for this TV, so Audiobookshelf shows it as one device in its sessions.
    static String deviceId(Context c) {
        SharedPreferences p = prefs(c);
        String id = p.getString("device", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            p.edit().putString("device", id).apply();
        }
        return id;
    }

    // Signs in with a username and password. Blocking. Audiobookshelf answers with an access token
    // that lasts an hour and a refresh token that refresh() trades for new ones.
    static void login(Context c, String server, String username, String password) throws IOException, JSONException {
        JSONObject body = new JSONObject().put("username", username).put("password", password);
        JSONObject r;
        try {
            r = (JSONObject) request(server, "POST", "/login", body, null, "x-return-tokens", "true");
        } catch (Unauthorized e) {
            throw new IOException("Wrong username or password");
        }
        JSONObject u = r.getJSONObject("user");
        prefs(c).edit().putString("server", server).remove("key").remove("library")
                .putString("access", u.getString("accessToken"))
                .putString("refresh", u.getString("refreshToken"))
                .putString("user", u.optString("username", username)).apply();
    }

    // Forgets the login (and API key) on the TV, and ends the session on the server if it can.
    static void signOut(Context c) {
        SharedPreferences p = prefs(c);
        final String server = server(c), refresh = p.getString("refresh", "");
        p.edit().remove("key").remove("access").remove("refresh").remove("user").remove("library").apply();
        if (!refresh.isEmpty()) run(() -> request(server, "POST", "/logout", null, null, "x-refresh-token", refresh), null);
    }

    // The server's public /status, or an IOException if it isn't Audiobookshelf. Blocking.
    static JSONObject status(String server, int timeoutMs) throws IOException {
        try {
            Object s = request(server, "GET", "/status", null, null, timeoutMs);
            if (s instanceof JSONObject && "audiobookshelf".equals(((JSONObject) s).optString("app")))
                return (JSONObject) s;
        } catch (JSONException ignored) {
            // Not JSON: not Audiobookshelf.
        }
        throw new IOException("No Audiobookshelf server answers at " + server);
    }

    // Swaps a password login's access token for a new one a few minutes before it runs out.
    // Blocking; called before every request. API keys don't expire.
    static synchronized void ensureFresh(Context c) throws IOException {
        SharedPreferences p = prefs(c);
        if (!p.getString("key", "").isEmpty() || p.getString("refresh", "").isEmpty()) return;
        if (expiresAt(p.getString("access", "")) - System.currentTimeMillis() / 1000 > 300) return;
        refresh(c);
    }

    private static synchronized void refresh(Context c) throws IOException {
        SharedPreferences p = prefs(c);
        String refresh = p.getString("refresh", "");
        if (refresh.isEmpty()) throw new IOException("Signed out");
        try {
            JSONObject r = (JSONObject) request(server(c), "POST", "/auth/refresh", null, null, "x-refresh-token", refresh);
            JSONObject u = r.getJSONObject("user");
            p.edit().putString("access", u.getString("accessToken"))
                    .putString("refresh", u.optString("refreshToken", refresh)).apply();
        } catch (Unauthorized e) {
            // The refresh token ran out (30 days unused) or was revoked.
            p.edit().remove("access").remove("refresh").apply();
            throw new IOException("Signed out: please sign in again");
        } catch (JSONException | ClassCastException e) {
            throw new IOException("Couldn't renew the login: " + e.getMessage());
        }
    }

    // The "exp" (seconds) inside a JWT, or 0 if it can't be read.
    private static long expiresAt(String jwt) {
        try {
            String payload = jwt.split("\\.")[1];
            int flags = android.util.Base64.URL_SAFE | android.util.Base64.NO_PADDING | android.util.Base64.NO_WRAP;
            return new JSONObject(new String(android.util.Base64.decode(payload, flags), StandardCharsets.UTF_8))
                    .optLong("exp", 0);
        } catch (Exception e) {
            return 0;
        }
    }

    // An audio track's URL for MediaPlayer, which can't send headers to every server it may hit.
    // Call ensureFresh first: the token is baked into the URL.
    static String streamUrl(Context c, String contentUrl) {
        return server(c) + contentUrl + (contentUrl.contains("?") ? "&" : "?") + "token=" + token(c);
    }

    static void get(Context c, String path, Done done) {
        run(() -> call(c, "GET", path, null), done);
    }

    static void post(Context c, String path, JSONObject body, Done done) {
        run(() -> call(c, "POST", path, body), done);
    }

    static void run(final Call call, final Done done) {
        API.execute(() -> {
            Object result = null;
            String error = null;
            try {
                result = call.run();
            } catch (Exception e) {
                error = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            final Object r = result;
            final String err = error;
            if (done != null) MAIN.post(() -> done.got(r, err));
        });
    }

    // An authenticated API call. Blocking: only from a background thread. Returns a JSONObject,
    // a JSONArray, or null if empty. A refused access token is renewed once and the call retried.
    // The body is a JSONObject, a JSONArray, or null for GETs and bodyless calls.
    static Object call(Context c, String method, String path, Object body) throws IOException, JSONException {
        ensureFresh(c);
        try {
            return request(server(c), method, path, body, token(c));
        } catch (Unauthorized e) {
            if (prefs(c).getString("refresh", "").isEmpty()) throw new IOException("The server refused the API key (401)");
            refresh(c);
            return request(server(c), method, path, body, token(c));
        }
    }

    static final class Unauthorized extends IOException {
        private static final long serialVersionUID = 1L;
        Unauthorized() { super("Not signed in (401)"); }
    }

    private static Object request(String server, String method, String path, Object body, String token,
                                  String... headers) throws IOException, JSONException {
        return request(server, method, path, body, token, 8000, headers);
    }

    // A call on the instant path, where an offline server must answer fast or be skipped:
    // a book opening waits at most a second and a half for one.
    static Object quick(Context c, String method, String path) throws IOException, JSONException {
        ensureFresh(c);
        return request(server(c), method, path, null, token(c), 1500);
    }

    // One HTTP request, with extra headers as name, value pairs. Blocking.
    private static Object request(String server, String method, String path, Object body, String token,
                                  int timeoutMs, String... headers) throws IOException, JSONException {
        if (server.isEmpty()) throw new IOException("No server is set");
        HttpURLConnection http = (HttpURLConnection) new URL(server + path).openConnection();
        try {
            http.setConnectTimeout(timeoutMs);
            http.setReadTimeout(Math.max(timeoutMs, 20000));
            http.setRequestMethod(method);
            if (token != null && !token.isEmpty()) http.setRequestProperty("Authorization", "Bearer " + token);
            for (int i = 0; i + 1 < headers.length; i += 2) http.setRequestProperty(headers[i], headers[i + 1]);
            if (body != null || "POST".equals(method)) {
                byte[] out = body != null ? body.toString().getBytes(StandardCharsets.UTF_8) : new byte[0];
                http.setDoOutput(true);
                http.setRequestProperty("Content-Type", "application/json");
                try (OutputStream os = http.getOutputStream()) { os.write(out); }
            }
            int code = http.getResponseCode();
            if (code == 401) throw new Unauthorized();
            if (code == 429) throw new IOException("Too many tries: wait a minute and try again");
            if (code >= 400) throw new IOException("The server answered " + code + " for " + path);
            String text;
            try (InputStream in = http.getInputStream()) { text = new String(read(in), StandardCharsets.UTF_8); }
            return text.trim().isEmpty() ? null : new JSONTokener(text).nextValue();
        } finally {
            http.disconnect();
        }
    }

    private static HttpURLConnection open(Context c, String path) throws IOException {
        ensureFresh(c);
        String server = server(c);
        if (server.isEmpty()) throw new IOException("No server is set");
        HttpURLConnection http = (HttpURLConnection) new URL(server + path).openConnection();
        http.setConnectTimeout(8000);
        http.setReadTimeout(20000);
        http.setRequestProperty("Authorization", "Bearer " + token(c));
        return http;
    }

    private static byte[] read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        return out.toByteArray();
    }

    interface CoverDone { void got(Bitmap cover, int tint); }

    private static synchronized LruCache<String, Bitmap> covers(Context c) {
        if (covers == null) {
            android.app.ActivityManager am = (android.app.ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
            int mb = am != null ? am.getMemoryClass() : 64;
            covers = new LruCache<String, Bitmap>(mb * 1024 * 1024 / 8) {
                @Override protected int sizeOf(String key, Bitmap b) { return b.getByteCount(); }
            };
        }
        return covers;
    }

    // A book's cover at about `size` px.
    static void cover(Context c, String itemId, int size, ImageView into, CoverDone done) {
        image(c, "/api/items/" + itemId + "/cover?width=" + size + "&format=jpeg", itemId + "@" + size, size, into, done);
    }

    // An author's photo at about `size` px (none if the author has no photo).
    static void authorImage(Context c, String authorId, int size, ImageView into, CoverDone done) {
        image(c, "/api/authors/" + authorId + "/image?width=" + size + "&format=jpeg", "author:" + authorId + "@" + size,
                size, into, done);
    }

    // Loads an image into an ImageView, then reports it with its glow colour. If the view has been
    // reused for something else by the time it's this request's turn (or it's done), it's skipped.
    private static void image(final Context c, final String path, final String key, final int size,
                              final ImageView into, final CoverDone done) {
        if (into != null) into.setTag(key);
        final LruCache<String, Bitmap> cache = covers(c);
        Bitmap hit = cache.get(key);
        if (hit != null) {
            if (into != null) into.setImageBitmap(hit);
            if (done != null) done.got(hit, tintOf(key, hit));
            return;
        }
        IMAGES.execute(() -> {
            if (into != null && !key.equals(into.getTag())) return; // scrolled away before its turn
            Bitmap b = null;
            try {
                HttpURLConnection http = open(c, path);
                try {
                    if (http.getResponseCode() == 200) {
                        byte[] data;
                        try (InputStream in = http.getInputStream()) { data = read(in); }
                        BitmapFactory.Options o = new BitmapFactory.Options();
                        o.inJustDecodeBounds = true;
                        BitmapFactory.decodeByteArray(data, 0, data.length, o);
                        o.inSampleSize = 1;
                        while (Math.min(o.outWidth, o.outHeight) / (o.inSampleSize * 2) >= size) o.inSampleSize *= 2;
                        o.inJustDecodeBounds = false;
                        o.inPreferredConfig = Bitmap.Config.RGB_565;
                        b = BitmapFactory.decodeByteArray(data, 0, data.length, o);
                    }
                } finally {
                    http.disconnect();
                }
            } catch (IOException ignored) {
                // No image: the tile keeps its placeholder.
            }
            if (b == null) return;
            cache.put(key, b);
            final Bitmap image = b;
            final int tint = tintOf(key, image);
            MAIN.post(() -> {
                if (into != null && !key.equals(into.getTag())) return;
                if (into != null) into.setImageBitmap(image);
                if (done != null) done.got(image, tint);
            });
        });
    }

    // A filter value for /items or /series: base64 of the id, URL-encoded (Audiobookshelf decodes
    // it with decodeURIComponent, then base64).
    static String filter(String group, String id) {
        String b64 = android.util.Base64.encodeToString(id.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        try {
            return group + "." + java.net.URLEncoder.encode(b64, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return group + "." + b64;
        }
    }

    // The signed-in user's progress by library item id, kept by the library screen for the others.
    static JSONObject progress = new JSONObject();

    // The cover's main colour, for the inner glow: the average hue of its saturated pixels
    // (hues averaged as angles, weighted by saturation). Greyish covers get Books' amber.
    private static int tintOf(String key, Bitmap cover) {
        synchronized (TINTS) {
            Integer known = TINTS.get(key);
            if (known != null) return known;
        }
        Bitmap small = Bitmap.createScaledBitmap(cover, 24, 24, true);
        float[] hsv = new float[3];
        double x = 0, y = 0, weight = 0;
        for (int py = 0; py < 24; py++) for (int px = 0; px < 24; px++) {
            Color.colorToHSV(small.getPixel(px, py), hsv);
            double w = hsv[1] * hsv[2];
            double a = Math.toRadians(hsv[0]);
            x += Math.cos(a) * w;
            y += Math.sin(a) * w;
            weight += w;
        }
        if (small != cover) small.recycle();
        int tint = Ui.AMBER;
        if (weight >= 30) {
            float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
            tint = Color.HSVToColor(new float[] {hue, 0.7f, 1f});
        }
        synchronized (TINTS) { TINTS.put(key, tint); }
        return tint;
    }
}
