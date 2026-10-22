package com.amalkrishna.books;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

// Finds Audiobookshelf servers on the TV's network. Audiobookshelf doesn't announce itself, so
// every address in the TV's /24 is tried on its usual ports, and whatever answers /status as
// Audiobookshelf is reported. Closed ports refuse at once and absent hosts time out quickly,
// so a full sweep takes a few seconds.
final class Discover {
    private static final int[] PORTS = {13378, 80};
    private static final int CONNECT_MS = 350, STATUS_MS = 1500, THREADS = 48;

    interface Found {
        void found(String server, String version);
        void done();
    }

    private final ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled;

    static Discover start(Found callback) {
        Discover d = new Discover();
        d.sweep(callback);
        return d;
    }

    void cancel() {
        cancelled = true;
        pool.shutdownNow();
    }

    private void sweep(final Found callback) {
        List<String> hosts = new ArrayList<>(candidates());
        final AtomicInteger left = new AtomicInteger(hosts.size() * PORTS.length);
        if (left.get() == 0) {
            main.post(callback::done);
            pool.shutdown();
            return;
        }
        for (final int port : PORTS) {
            for (final String host : hosts) {
                pool.execute(() -> {
                    try {
                        if (!cancelled && open(host, port)) {
                            String server = "http://" + host + (port == 80 ? "" : ":" + port);
                            JSONObject s = Abs.status(server, STATUS_MS);
                            final String version = s.optString("serverVersion", "");
                            if (!cancelled) main.post(() -> callback.found(server, version));
                        }
                    } catch (Exception ignored) {
                        // Nothing there, or not Audiobookshelf.
                    } finally {
                        if (left.decrementAndGet() == 0 && !cancelled) main.post(callback::done);
                    }
                });
            }
        }
        pool.shutdown();
    }

    private static boolean open(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), CONNECT_MS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // Every address in the /24 of each of the TV's IPv4 networks (a wider network is narrowed to
    // the TV's own /24, which keeps the sweep to 254 addresses per network).
    private static Set<String> candidates() {
        Set<String> out = new LinkedHashSet<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress a = ia.getAddress();
                    if (!(a instanceof Inet4Address) || !a.isSiteLocalAddress()) continue;
                    byte[] b = a.getAddress();
                    String prefix = (b[0] & 0xFF) + "." + (b[1] & 0xFF) + "." + (b[2] & 0xFF) + ".";
                    for (int i = 1; i < 255; i++) out.add(prefix + i);
                }
            }
        } catch (Exception ignored) {
            // No network: nothing to sweep.
        }
        return out;
    }
}
