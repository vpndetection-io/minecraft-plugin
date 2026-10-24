package io.vpndetection.minecraft;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * What staff are told about logins, and the console's line for each.
 *
 * <p>Throttled, because the moment this matters most is a bot flood: one line per address per ten
 * minutes, so a player retrying is told once, and at most {@value #BURST} lines per ten seconds in
 * all, with whatever that holds back summed up in one line once the ten seconds are over.
 */
final class Alerts {
    static final int BURST = 5;
    private static final Duration WINDOW = Duration.ofSeconds(10);
    private static final String PREFIX = "&8[&3VPNDetection&8] ";
    private static final String TAG = "[VPNDetection] ";

    private final Platform platform;
    private final ScheduledExecutorService timer;
    private final LongSupplier nanos;
    private final Cache<String, Boolean> told = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();
    private long windowStart;
    private int sent;
    private int held;

    Alerts(Platform platform, ScheduledExecutorService timer, LongSupplier nanos) {
        this.platform = platform;
        this.timer = timer;
        this.nanos = nanos;
        this.windowStart = nanos.getAsLong();
    }

    /** Tells staff about a login from {@code address}, unless the throttle holds it back. */
    void login(String address, String text) {
        if (told.asMap().putIfAbsent(address, Boolean.TRUE) != null) {
            return;
        }
        synchronized (this) {
            long now = nanos.getAsLong();
            if (now - windowStart >= WINDOW.toNanos()) {
                windowStart = now;
                sent = 0;
            }
            if (sent >= BURST) {
                if (held++ == 0) {
                    long left = WINDOW.toNanos() - (now - windowStart);
                    timer.schedule(this::summarize, left, TimeUnit.NANOSECONDS);
                }
                return;
            }
            sent++;
        }
        deliver(text);
    }

    /** Tells staff something about the plugin itself, unthrottled, with the plugin's prefix. */
    void notice(String text) {
        deliver(Text.colorize(PREFIX) + text);
    }

    private void summarize() {
        int count;
        synchronized (this) {
            count = held;
            held = 0;
        }
        if (count > 0) {
            notice(Text.colorize("&7... and " + count + " more " + (count == 1 ? "login" : "logins")
                    + " refused or flagged in the last ten seconds."));
        }
    }

    // The console's logger names the plugin on every line already.
    private void deliver(String text) {
        platform.notify(Engine.ALERTS, text);
        String line = Text.plain(text);
        platform.info(line.startsWith(TAG) ? line.substring(TAG.length()) : line);
    }
}
