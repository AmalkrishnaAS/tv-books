package com.amalkrishna.books;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Plays one book at a time and keeps Audiobookshelf in step, so the phone and web pick up where
// the TV left off. A book is a list of audio tracks; positions are in seconds across the whole book.
// Keeps playing after Back or the remote's Home button; stops itself after 15 minutes paused.
public class PlayerService extends Service implements MediaPlayer.OnPreparedListener,
        MediaPlayer.OnCompletionListener, MediaPlayer.OnErrorListener {

    static final String EXTRA_ITEM = "item";
    private static final int SYNC_MS = 15000, IDLE_STOP_MS = 15 * 60 * 1000, NOTIFICATION = 1;
    private static final float[] SPEEDS = {0.8f, 1f, 1.1f, 1.2f, 1.25f, 1.5f, 1.75f, 2f};

    interface Listener { void changed(); }

    static PlayerService instance;
    static Listener listener;

    static final class Track {
        final double start, length;
        final String path; // the server path; the token is added when the track is loaded
        Track(double start, double length, String path) { this.start = start; this.length = length; this.path = path; }
    }

    static final class Chapter {
        final double start, end;
        final String title;
        Chapter(double start, double end, String title) { this.start = start; this.end = end; this.title = title; }
    }

    // The book: set once its Audiobookshelf session has started.
    String itemId, sessionId, title = "", author = "", narrator = "", error;
    double duration;
    final List<Chapter> chapters = new ArrayList<>();
    private final List<Track> tracks = new ArrayList<>();

    private MediaPlayer player;
    private MediaSession session;
    private AudioManager audio;
    private AudioFocusRequest focusRequest;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int track = -1, loads, retries;
    private String loadedToken = ""; // the token baked into the current track's URL
    private boolean prepared, wantPlay, foreground;
    private double startAt;       // where to seek once the current track is prepared
    private double lastPosition;  // the last known position, for while a track is loading
    private long playingSince;    // elapsedRealtime when playback last started, 0 when paused
    private double listened;      // seconds listened since the last sync
    private float speed;

    private final Runnable syncTick = new Runnable() {
        @Override public void run() {
            sync(false);
            if (isPlaying() && prepared) retries = 0;
            main.postDelayed(this, SYNC_MS);
        }
    };
    private final Runnable idleStop = this::stop;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        speed = Abs.prefs(this).getFloat("speed", 1f);
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        session = new MediaSession(this, "Books");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { play(); }
            @Override public void onPause() { pause(); }
            @Override public void onStop() { stop(); }
            @Override public void onFastForward() { skip(30); }
            @Override public void onRewind() { skip(-30); }
            @Override public void onSkipToNext() { skip(30); }
            @Override public void onSkipToPrevious() { skip(-30); }
            @Override public void onSeekTo(long ms) { seek(ms / 1000.0); }
        });
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        session.setSessionActivity(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String item = intent == null ? null : intent.getStringExtra(EXTRA_ITEM);
        if (item != null && !item.equals(itemId)) open(item);
        else if (item != null) play();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        release();
        session.release();
        if (instance == this) instance = null;
        super.onDestroy();
    }

    // Starts an Audiobookshelf playback session for a book and plays from its saved position.
    private void open(final String item) {
        closeSession();
        release();
        itemId = item;
        sessionId = null;
        error = null;
        title = author = narrator = "";
        duration = 0;
        chapters.clear();
        tracks.clear();
        track = -1;
        wantPlay = true;
        changed();
        try {
            JSONObject device = new JSONObject()
                    .put("clientName", "Books for Android TV")
                    .put("clientVersion", "1.1.1")
                    .put("deviceId", Abs.deviceId(this))
                    .put("manufacturer", Build.MANUFACTURER)
                    .put("model", Build.MODEL)
                    .put("sdkVersion", Build.VERSION.SDK_INT);
            // Direct play: Android's own player handles the usual audiobook formats.
            JSONObject body = new JSONObject()
                    .put("deviceInfo", device)
                    .put("forceDirectPlay", true)
                    .put("forceTranscode", false)
                    .put("mediaPlayer", "android-mediaplayer")
                    .put("supportedMimeTypes", new JSONArray().put("audio/mpeg").put("audio/mp4").put("audio/x-m4b")
                            .put("audio/aac").put("audio/flac").put("audio/ogg").put("audio/opus"));
            Abs.post(this, "/api/items/" + item + "/play", body, (json, err) -> {
                if (!item.equals(itemId)) return; // another book was opened meanwhile
                if (err != null || !(json instanceof JSONObject)) {
                    fail("Couldn't start this book: " + (err != null ? err : "no session"));
                    return;
                }
                try {
                    start((JSONObject) json);
                } catch (JSONException e) {
                    fail("Couldn't read this book's session: " + e.getMessage());
                }
            });
        } catch (JSONException e) {
            fail(e.getMessage());
        }
    }

    private void start(JSONObject s) throws JSONException {
        sessionId = s.getString("id");
        title = s.optString("displayTitle", "");
        author = s.optString("displayAuthor", "");
        JSONObject meta = s.optJSONObject("mediaMetadata");
        if (meta != null) narrator = names(meta.optJSONArray("narrators"));
        duration = s.optDouble("duration", 0);
        JSONArray ch = s.optJSONArray("chapters");
        for (int i = 0; ch != null && i < ch.length(); i++) {
            JSONObject c = ch.getJSONObject(i);
            chapters.add(new Chapter(c.optDouble("start"), c.optDouble("end"), c.optString("title", "Chapter " + (i + 1))));
        }
        JSONArray at = s.getJSONArray("audioTracks");
        for (int i = 0; i < at.length(); i++) {
            JSONObject t = at.getJSONObject(i);
            tracks.add(new Track(t.optDouble("startOffset"), t.optDouble("duration"), t.getString("contentUrl")));
        }
        if (tracks.isEmpty()) {
            fail("This book has no audio");
            return;
        }
        if (duration <= 0) {
            Track last = tracks.get(tracks.size() - 1);
            duration = last.start + last.length;
        }
        double at0 = s.optDouble("currentTime", 0);
        // Finished books start again from the beginning.
        if (at0 >= duration - 5) at0 = 0;
        lastPosition = at0;
        session.setActive(true);
        updateMetadata(null);
        Abs.cover(this, itemId, 400, null, (cover, tint) -> updateMetadata(cover));
        load(trackAt(at0), at0);
        changed();
    }

    private static String names(JSONArray a) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; a != null && i < a.length(); i++) {
            String n = a.optString(i, "");
            if (n.isEmpty()) continue;
            if (out.length() > 0) out.append(", ");
            out.append(n);
        }
        return out.toString();
    }

    private int trackAt(double t) {
        for (int i = tracks.size() - 1; i >= 0; i--) if (t >= tracks.get(i).start) return i;
        return 0;
    }

    // Prepares a track and, once ready, seeks to `at` (seconds in the book). The stream URL carries
    // the token, so it's built after making sure the token is fresh (a password login's lasts an hour).
    private void load(final int index, final double at) {
        release();
        track = index;
        startAt = at;
        lastPosition = at;
        final int attempt = ++loads;
        Abs.run(() -> {
            Abs.ensureFresh(this);
            return Abs.streamUrl(this, tracks.get(index).path);
        }, (url, err) -> {
            if (attempt != loads) return; // superseded by a later seek or track
            if (err != null) fail("Couldn't play this book: " + err);
            else prepare((String) url);
        });
    }

    private void prepare(String url) {
        loadedToken = Abs.token(this);
        prepared = false;
        player = new MediaPlayer();
        player.setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        player.setWakeMode(this, PowerManager.PARTIAL_WAKE_LOCK);
        player.setOnPreparedListener(this);
        player.setOnCompletionListener(this);
        player.setOnErrorListener(this);
        try {
            player.setDataSource(url);
            player.prepareAsync();
        } catch (Exception e) {
            fail("Couldn't play this book: " + e.getMessage());
        }
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        prepared = true;
        int ms = (int) Math.round((startAt - tracks.get(track).start) * 1000);
        if (ms > 0) mp.seekTo(ms);
        if (wantPlay) play();
        else changed();
    }

    @Override
    public void onCompletion(MediaPlayer mp) {
        if (track + 1 < tracks.size()) {
            load(track + 1, tracks.get(track + 1).start);
        } else {
            // The end of the book: Audiobookshelf marks it finished from the final position.
            lastPosition = duration;
            pauseClock();
            wantPlay = false;
            sync(false);
            closeSession();
            changed();
        }
    }

    // A dropped stream (often an expired token on a long track) is reopened where it stopped, twice
    // at most; after that it's reported.
    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        if (retries < 2 && track >= 0) {
            retries++;
            load(track, position());
        } else {
            fail("Playback stopped (error " + what + "/" + extra + ")");
        }
        return true;
    }

    boolean isPlaying() { return wantPlay && error == null; }
    boolean isLoading() { return wantPlay && !prepared && error == null && sessionId != null; }

    double position() {
        if (prepared && player != null && track >= 0) {
            lastPosition = tracks.get(track).start + player.getCurrentPosition() / 1000.0;
        }
        return lastPosition;
    }

    Chapter chapter() {
        double t = position();
        for (Chapter c : chapters) if (t >= c.start && t < c.end) return c;
        return null;
    }

    float speed() { return speed; }
    static float[] speeds() { return SPEEDS.clone(); }

    void toggle() {
        if (isPlaying()) pause();
        else play();
    }

    void play() {
        if (sessionId == null) return;
        if (sessionId.isEmpty() && itemId != null) {
            // The session was closed at the end of the book: start a new one.
            open(itemId);
            return;
        }
        wantPlay = true;
        main.removeCallbacks(idleStop);
        if (!prepared || player == null) {
            changed();
            return;
        }
        if (!requestFocus()) return;
        player.start();
        applySpeed();
        if (playingSince == 0) playingSince = SystemClock.elapsedRealtime();
        main.removeCallbacks(syncTick);
        main.postDelayed(syncTick, SYNC_MS);
        goForeground();
        changed();
    }

    void pause() {
        wantPlay = false;
        if (prepared && player != null && player.isPlaying()) player.pause();
        position();
        pauseClock();
        main.removeCallbacks(syncTick);
        sync(false);
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_STOP_MS);
        changed();
    }

    void skip(double seconds) {
        seek(position() + seconds);
    }

    void seek(double t) {
        if (tracks.isEmpty()) return;
        t = Math.max(0, Math.min(t, duration - 1));
        int index = trackAt(t);
        lastPosition = t;
        // A seek opens a new request with the URL's token, so after a renewal the track is reloaded.
        if (index == track && prepared && player != null && loadedToken.equals(Abs.token(this))) {
            player.seekTo((int) Math.round((t - tracks.get(track).start) * 1000));
            changed();
        } else {
            load(index, t);
            changed();
        }
    }

    void setSpeed(float s) {
        speed = s;
        Abs.prefs(this).edit().putFloat("speed", s).apply();
        if (isPlaying()) applySpeed();
        changed();
    }

    // Only while playing: on some Android versions, setting the speed of a paused player starts it.
    private void applySpeed() {
        if (Build.VERSION.SDK_INT < 23 || player == null || !player.isPlaying()) return;
        try {
            PlaybackParams p = player.getPlaybackParams();
            player.setPlaybackParams(p.setSpeed(speed));
        } catch (Exception ignored) {
            // Some formats can't change speed; play on at 1x.
        }
    }

    private void abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest != null) audio.abandonAudioFocusRequest(focusRequest);
        } else {
            audio.abandonAudioFocus(onFocusChange);
        }
    }

    // Ends playback: tells the server, then gives back audio focus and drops the notification and
    // the service.
    void stop() {
        if (isPlaying()) pause();
        closeSession();
        release();
        abandonFocus();
        session.setActive(false);
        stopForeground(true);
        foreground = false;
        itemId = null;
        changed();
        stopSelf();
    }

    private void pauseClock() {
        if (playingSince != 0) {
            listened += (SystemClock.elapsedRealtime() - playingSince) / 1000.0;
            playingSince = 0;
        }
    }

    // Reports the position (and time listened since the last report) to Audiobookshelf.
    private void sync(boolean close) {
        if (sessionId == null || sessionId.isEmpty()) return;
        if (playingSince != 0) {
            long now = SystemClock.elapsedRealtime();
            listened += (now - playingSince) / 1000.0;
            playingSince = now;
        }
        final double heard = listened;
        listened = 0;
        try {
            JSONObject body = new JSONObject()
                    .put("currentTime", position())
                    .put("timeListened", heard)
                    .put("duration", duration);
            Abs.post(this, "/api/session/" + sessionId + (close ? "/close" : "/sync"), body, null);
        } catch (JSONException ignored) {
            // Not reachable: the values are plain numbers.
        }
    }

    private void closeSession() {
        if (sessionId != null && !sessionId.isEmpty()) {
            sync(true);
            sessionId = ""; // closed; play() starts a new one
        }
    }

    private void release() {
        prepared = false;
        if (player != null) {
            player.release();
            player = null;
        }
    }

    private void fail(String message) {
        error = message;
        wantPlay = false;
        pauseClock();
        release();
        changed();
    }

    // One listener for the service's life, so the focus taken is the focus given back in stop().
    private final AudioManager.OnAudioFocusChangeListener onFocusChange = change -> {
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause();
    };

    private boolean requestFocus() {
        AudioManager.OnAudioFocusChangeListener onChange = onFocusChange;
        if (Build.VERSION.SDK_INT >= 26) {
            if (focusRequest == null) {
                focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setOnAudioFocusChangeListener(onChange).build();
            }
            return audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        }
        return audio.requestAudioFocus(onChange, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    private void updateMetadata(Bitmap cover) {
        MediaMetadata.Builder m = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, author)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, Math.round(duration * 1000));
        if (cover != null) m.putBitmap(MediaMetadata.METADATA_KEY_ART, cover);
        session.setMetadata(m.build());
    }

    // Foreground services must show a notification; on the TV it mostly stays out of sight.
    private void goForeground() {
        if (foreground) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel("playback", "Playback",
                    NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, "playback");
        } else {
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(R.drawable.note)
                .setContentTitle(title)
                .setContentText(author)
                .setContentIntent(session.getController().getSessionActivity())
                .setStyle(new Notification.MediaStyle().setMediaSession(session.getSessionToken()));
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, b.build(), android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        } else {
            startForeground(NOTIFICATION, b.build());
        }
        foreground = true;
    }

    private void changed() {
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_FAST_FORWARD | PlaybackState.ACTION_REWIND | PlaybackState.ACTION_SEEK_TO
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS | PlaybackState.ACTION_STOP;
        int state = error != null ? PlaybackState.STATE_ERROR : isLoading() ? PlaybackState.STATE_BUFFERING
                : isPlaying() ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED;
        session.setPlaybackState(new PlaybackState.Builder().setActions(actions)
                .setState(state, Math.round(position() * 1000), isPlaying() ? speed : 0f).build());
        if (listener != null) listener.changed();
    }
}
