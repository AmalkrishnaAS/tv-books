package com.amalkrishna.books;

import android.os.Handler;
import android.os.Looper;

// Runs only the latest of a burst of calls, once they've stopped for `ms`: a held D-pad or fast
// typing then costs one update instead of one per key.
final class Debounce {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private final long ms;
    private Runnable pending;

    Debounce(long ms) {
        this.ms = ms;
    }

    void run(Runnable r) {
        cancel();
        pending = r;
        MAIN.postDelayed(r, ms);
    }

    void cancel() {
        if (pending != null) MAIN.removeCallbacks(pending);
        pending = null;
    }
}
