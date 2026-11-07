package com.amalkrishna.books;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

// Holding a book tile (the OK button of the remote) opens this menu: decide its progress and
// whether the TV keeps its own copy. Works for the library rows and the browse screens alike;
// every screen says how it wants its data rebuilt when an action changes what it shows.
final class BookMenu {
    interface Redo { void doIt(); }

    static void show(final Context c, final String itemId, final String title, final Redo redo) {
        if (itemId == null || itemId.isEmpty()) return;
        JSONObject p = Abs.progress == null ? null : Abs.progress.optJSONObject(itemId);
        JSONObject m = Downloads.meta(c, itemId); // an offline copy keeps its own progress when away
        boolean heard = (p != null && (p.optBoolean("isFinished") || p.optDouble("progress", 0) > 0))
                || (m != null && (m.optBoolean("finished") || m.optDouble("pos", 0) > 0));
        final List<String> items = new ArrayList<>();
        if (heard) items.add("Forget progress");
        if (Downloads.downloading(itemId)) items.add("Downloading…");
        else items.add(m == null ? "Download to this TV" : "Remove from this TV");
        Ui.sheet((Activity) c, title, items, -1, which -> act(c, itemId, title, items.get(which), redo));
    }

    private static void act(final Context c, final String itemId, final String title, final String what, final Redo redo) {
        boolean playing = PlayerService.instance != null && itemId.equals(PlayerService.instance.itemId);
        if ("Forget progress".equals(what)) {
            if (playing) PlayerService.instance.stop();
            Downloads.saveProgress(c, itemId, 0, false); // an offline copy forgets along with the server
            // The progress entry has its own id: it's usually the book's id, but servers
            // differ, so take it from the progress record itself.
            JSONObject p = Abs.progress == null ? null : Abs.progress.optJSONObject(itemId);
            final String id = p != null && !p.optString("id").isEmpty() ? p.optString("id") : itemId;
            Abs.run(() -> { Abs.call(c, "DELETE", "/api/me/progress/" + id, null); return null; },
                (json, err) -> {
                    toast(c, err == null ? "Progress gone" : err);
                    redo.doIt();
                });
        } else if ("Download to this TV".equals(what)) {
            toast(c, "Downloading “" + title + "”…");
            Downloads.download(c, itemId, s -> toast(c, "Downloading “" + title + "”  " + s),
                (ok, err) -> { toast(c, err == null ? "“" + title + "” was downloaded successfully" : "Couldn't download “" + title + "” — " + err); redo.doIt(); });
        } else if ("Remove from this TV".equals(what)) {
            if (playing) PlayerService.instance.stop();
            Downloads.remove(c, itemId);
            toast(c, "“" + title + "” was deleted from this TV");
            redo.doIt();
        }
        // "Downloading…" simply closes the sheet: it's a status, not a choice.
    }

    private static void toast(Context c, String msg) {
        Toast.makeText(c, msg, android.widget.Toast.LENGTH_LONG).show();
    }
}
