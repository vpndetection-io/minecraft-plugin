package io.vpndetection.minecraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineTest {
    private static final String VPN_IP = "45.83.91.1";
    private static final String ENTITLEMENT = "{\"org_id\":\"f32191d0-ef02-450e-a505-eb5814c35cab\","
            + "\"apikey\":{\"id\":\"10c2b437-3aa2-4a63-bd17-8e7c8c7f0def\",\"expires\":null,\"allowed_cidrs\":[]},"
            + "\"plan\":{\"key\":\"free\",\"tier\":\"free\"},\"usage\":{\"requests\":545,\"quota\":50000,"
            + "\"hard_limit\":50000,\"window_start\":\"2026-10-01T00:00:00Z\",\"window_end\":\"2026-11-01T00:00:00Z\"}}";

    @TempDir
    Path dir;
    private StubApi api;
    private FakePlatform platform;
    private Engine engine;

    @BeforeEach
    void start() throws Exception {
        api = new StubApi();
        System.setProperty("vpndetection.api-url", api.url());
        platform = new FakePlatform(dir);
    }

    @AfterEach
    void stop() {
        if (engine != null) {
            engine.disable();
        }
        System.clearProperty("vpndetection.api-url");
        api.close();
    }

    private Engine enable() {
        engine = new Engine(platform);
        engine.enable();
        return engine;
    }

    @Test
    void theFirstStartWritesTheDefaultsAndSaysWhatItChecks() throws Exception {
        enable();
        assertTrue(Files.readString(dir.resolve("config.yml")).contains("api-key: \"\""));
        assertTrue(Files.readString(dir.resolve("bypass.txt")).startsWith("# Players and addresses"));
        assertTrue(platform.infos.get(0).startsWith("VPNDetection " + engine.version() + " on Fake 1.0: vpn block, tor block"),
                platform.infos.toString());
        assertTrue(platform.infos.stream().anyMatch(i -> i.startsWith("No API key")), platform.infos.toString());
        assertEquals(List.of(), platform.warnings);
    }

    @Test
    void aRefusedLoginIsToldToStaffAndLoggedWithTheKickMessageItsPlayerSees() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        enable();
        InetAddress address = InetAddress.getByName(VPN_IP);
        Verdict verdict = engine.login("Steve", UUID.randomUUID(), address);
        assertTrue(verdict.isBlocked());
        assertEquals("§cYou can't join from this connection: VPN.\n§7Turn off your VPN or proxy and join again.",
                engine.kickMessage(verdict, "Steve", address));
        assertEquals(List.of(Engine.ALERTS + " §8[§3VPNDetection§8] §7Refused §fSteve §7from §f45.83.91.1§7: §cVPN"),
                platform.notified);
        assertTrue(platform.infos.contains("Refused Steve from 45.83.91.1: VPN"), platform.infos.toString());
        assertEquals(1, engine.refused.get());
        String userAgent = api.requests.get(0).header("User-Agent");
        assertTrue(userAgent.startsWith("vpndetection-minecraft/" + engine.version() + " (Fake 1.0; Java "), userAgent);
    }

    @Test
    void anEmptyMessageTurnsItsAlertOff() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), "messages: {blocked: \"\"}\n");
        enable();
        assertTrue(engine.login("Steve", UUID.randomUUID(), InetAddress.getByName(VPN_IP)).isBlocked());
        assertEquals(List.of(), platform.notified);
    }

    @Test
    void aSpentAllowanceLetsPlayersInAndTellsStaffOnce() throws Exception {
        api.route("/" + VPN_IP, request -> new StubApi.Answer(429, "{\"error\":\"quota exceeded\"}", 0));
        enable();
        for (int i = 0; i < 3; i++) {
            assertFalse(engine.login("Steve", UUID.randomUUID(), InetAddress.getByName(VPN_IP)).isBlocked());
        }
        assertEquals(1, platform.notified.stream().filter(n -> n.contains("allowance is spent")).count(),
                platform.notified.toString());
        assertEquals(3, engine.failed.get());
    }

    @Test
    void aReloadReadsTheFilesAgainAndKeepsTheCacheWhenTheKeyIsTheSame() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        enable();
        InetAddress address = InetAddress.getByName(VPN_IP);
        assertTrue(engine.login("Steve", UUID.randomUUID(), address).isBlocked());

        Files.writeString(dir.resolve("config.yml"), "checks: {vpn: alert}\n");
        assertEquals(List.of(), engine.reload());
        assertEquals(Action.ALERT, engine.login("Steve", UUID.randomUUID(), address).action());
        assertEquals(1, api.count("/" + VPN_IP));

        Files.writeString(dir.resolve("bypass.txt"), "steve\n");
        engine.reload();
        assertTrue(engine.login("Steve", UUID.randomUUID(), address).isBypassed());
    }

    @Test
    void aConfigThatIsNotYamlKeepsTheSettingsInPlaceAndSaysWhy() throws Exception {
        enable();
        Files.writeString(dir.resolve("config.yml"), "checks:\n  vpn: allow\n tor: [\n");
        List<String> problems = engine.reload();
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("config.yml can't be read, so the settings stay as they were: line "),
                problems.get(0));
        assertEquals(Action.BLOCK, engine.state().settings().checks.get(Check.VPN));
    }

    @Test
    void aKeyIsCheckedOnStartAndItsPlanNamed() throws Exception {
        api.answer("/api/v1/entitlement", ENTITLEMENT);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), "api-key: \"vd_test_0123456789abcdef\"\n");
        enable();
        String said = waitFor(platform.infos, "Signed in");
        assertEquals("Signed in with vd_t...cdef from config.yml: Free plan, 545 of 50,000 lookups used, "
                + "renewing 2026-11-01.", said);
        assertEquals("Bearer vd_test_0123456789abcdef", api.requests.get(0).header("Authorization"));
    }

    @Test
    void aRefusedKeyIsSaidSoBeforeAnyPlayerJoins() throws Exception {
        api.route("/api/v1/entitlement", request -> new StubApi.Answer(401, "{\"error\":\"invalid api key\"}", 0));
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("config.yml"), "api-key: \"vd_test_0123456789abcdef\"\n");
        enable();
        String said = waitFor(platform.warnings, "The API key");
        assertTrue(said.contains("was refused"), said);
    }

    // Through SDK 6.9.1 a key holding é went out with a question mark in its place, so every lookup
    // failed as a wrong key; from 6.10.0 the builder refuses it, and the reload says why.
    @Test
    void aKeyOutsideAsciiIsSaidSoAndLookupsGoWithoutOne() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        enable();
        Files.writeString(dir.resolve("config.yml"), "api-key: \"vd_test_0123456789abcdé\"\n");
        assertEquals(List.of("the API key can't be used (the API key holds a character outside ASCII), "
                + "so lookups go without one"), engine.reload());
        assertTrue(engine.login("Steve", UUID.randomUUID(), InetAddress.getByName(VPN_IP)).isBlocked());
        assertNull(api.requests.get(api.requests.size() - 1).header("Authorization"));
    }

    @Test
    void aSavedKeyKeepsEveryOtherLineAndIsUsedAtOnce() throws Exception {
        api.answer("/api/v1/entitlement", ENTITLEMENT);
        api.answer("/" + VPN_IP, Answers.free(VPN_IP, false, true, Answers.LOCATION_AT, Answers.ASN_M247));
        enable();
        String before = Files.readString(dir.resolve("config.yml"));
        assertTrue(engine.saveApiKey("vd_test_0123456789abcdef"));
        String after = Files.readString(dir.resolve("config.yml"));
        assertEquals(before.replace("api-key: \"\"", "api-key: \"vd_test_0123456789abcdef\""), after);
        Verdict verdict = engine.login("Steve", UUID.randomUUID(), InetAddress.getByName(VPN_IP));
        assertEquals("Tor, hosting network (M247 Europe SRL)", verdict.reason());
        assertTrue(api.requests.stream().anyMatch(r -> ("/" + VPN_IP).equals(r.path())
                && "Bearer vd_test_0123456789abcdef".equals(r.header("Authorization"))));
    }

    private static String waitFor(List<String> lines, String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (String line : lines) {
                if (line.startsWith(prefix)) {
                    return line;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("nothing said starting " + prefix + ": " + lines);
    }
}
