package io.vpndetection.minecraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AlertsTest {
    private static final long SECOND = TimeUnit.SECONDS.toNanos(1);

    @TempDir
    Path dir;
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();

    @AfterEach
    void stop() {
        timer.shutdownNow();
    }

    @Test
    void aFloodIsToldInFiveLinesAndASum() throws Exception {
        FakePlatform platform = new FakePlatform(dir);
        long[] now = {0};
        Alerts alerts = new Alerts(platform, timer, () -> now[0]);
        for (int i = 0; i < Alerts.BURST; i++) {
            alerts.login("203.0.113." + i, "line " + i);
        }
        // The rest arrive just before the ten seconds are over, so the sum is due at once.
        now[0] = 10 * SECOND - TimeUnit.MILLISECONDS.toNanos(20);
        alerts.login("203.0.113.100", "held");
        alerts.login("203.0.113.101", "held");
        assertEquals(Alerts.BURST, platform.notified.size());

        long deadline = System.nanoTime() + 5 * SECOND;
        while (platform.notified.size() == Alerts.BURST && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(Alerts.BURST + 1, platform.notified.size());
        String sum = platform.notified.get(Alerts.BURST);
        assertTrue(sum.contains("and 2 more logins refused or flagged in the last ten seconds"), sum);

        now[0] = 11 * SECOND;
        alerts.login("203.0.113.0", "the same address again, within ten minutes");
        alerts.login("203.0.113.200", "a new one, in a new window");
        assertEquals(Alerts.BURST + 2, platform.notified.size());
        assertEquals(Engine.ALERTS + " a new one, in a new window", platform.notified.get(Alerts.BURST + 1));
    }
}
