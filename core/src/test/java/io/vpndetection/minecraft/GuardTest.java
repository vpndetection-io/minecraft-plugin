package io.vpndetection.minecraft;

import io.vpndetection.VPNDetection;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuardTest {
    private static final String VPN_IP = "45.83.91.1";
    private static final UUID PLAYER = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

    @TempDir
    Path dir;
    private StubApi api;
    private VPNDetection client;
    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws Exception {
        api = new StubApi();
        client = VPNDetection.builder().baseUrl(api.url()).httpClient(new UserAgentHttpClient("test/1")).build();
    }

    @AfterEach
    void stop() {
        client.close();
        api.close();
    }

    private Guard guard(String yaml, String bypass) throws Exception {
        List<String> problems = new ArrayList<>();
        Settings settings = Settings.parse(ConfigFile.parse(yaml), ConfigFile.defaults(), problems);
        assertEquals(List.of(), problems);
        Path file = dir.resolve("bypass.txt");
        Files.writeString(file, bypass);
        return new Guard(settings, Bypass.load(file, problems), client, warnings::add);
    }

    private Verdict check(Guard guard, String ip) throws Exception {
        return guard.check("Notch", PLAYER, InetAddress.getByName(ip));
    }

    @Test
    void theShippedDefaultsBlockAVpnWithNoKey() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        Verdict verdict = check(guard("", ""), VPN_IP);
        assertEquals(Action.BLOCK, verdict.action());
        assertEquals("VPN", verdict.reason());
    }

    @Test
    void aCheckThePlanDoesNotAnswerIsReportedOnceAndNeverMatches() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, false));
        Verdict verdict = check(guard("checks: {tor: block}", ""), VPN_IP);
        assertEquals(Action.ALLOW, verdict.action());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("checks.tor is block") && w.contains("API key")),
                warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("checks.proxy") && w.contains("paid plan")),
                warnings.toString());
    }

    @Test
    void aFreeKeyFindsAHostingNetworkByTheTypeOfItsAs() throws Exception {
        String ip = "146.70.116.130";
        api.answer("/" + ip, Answers.free(ip, false, false, Answers.LOCATION_AT, Answers.ASN_M247));
        Verdict verdict = check(guard("checks: {vpn: block, hosting: alert}", ""), ip);
        assertEquals(Action.ALERT, verdict.action());
        assertEquals("hosting network (M247 Europe SRL)", verdict.reason());
    }

    @Test
    void aPaidPlanNamesEachProviderAndBlockingBeatsAlerting() throws Exception {
        api.answer("/" + VPN_IP, Answers.paid(VPN_IP));
        Verdict verdict = check(guard("checks: {vpn: alert, hosting: block, proxy: block}", ""), VPN_IP);
        assertEquals(Action.BLOCK, verdict.action());
        assertEquals(List.of("hosting (ovh)", "residential proxy (brightdata)"), verdict.reasons());
        assertFalse(verdict.country());
    }

    @Test
    void countriesRefuseByBlockListAndByAllowList() throws Exception {
        String ip = "177.1.2.3";
        api.answer("/" + ip, Answers.free(ip, false, false, Answers.LOCATION_BR, Answers.ASN_ISP));
        Verdict blocked = check(guard("countries: {block: [br]}", ""), ip);
        assertEquals(Action.BLOCK, blocked.action());
        assertEquals("country (BR)", blocked.reason());
        assertTrue(blocked.country());

        Verdict notAllowed = check(guard("countries: {allow: [US, CA]}", ""), ip);
        assertEquals(Action.BLOCK, notAllowed.action());
        assertTrue(notAllowed.country());

        assertEquals(Action.ALLOW, check(guard("countries: {allow: [BR]}", ""), ip).action());
    }

    @Test
    void anAddressNoCountryCanBePlacedIsLetIn() throws Exception {
        String ip = "177.1.2.3";
        api.answer("/" + ip, Answers.free(ip, false, false, Answers.LOCATION_UNKNOWN, Answers.ASN_ISP));
        assertEquals(Action.ALLOW, check(guard("countries: {allow: [US]}", ""), ip).action());
    }

    @Test
    void countriesWithNoKeyAreReported() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, false));
        assertEquals(Action.ALLOW, check(guard("countries: {block: [AT]}", ""), VPN_IP).action());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("countries need an API key")), warnings.toString());
    }

    @Test
    void rulesMatchOnAnyFieldTheSdkMatchesOn() throws Exception {
        api.answer("/" + VPN_IP, Answers.paid(VPN_IP));
        String yaml = """
                checks: {vpn: allow, tor: allow, hosting: allow, proxy: allow, relay: allow}
                rules:
                  - action: alert
                    reason: busy pool
                    match:
                      resproxy:
                        hits: {gte: 7}
                  - action: block
                    reason: M247
                    match:
                      asn: {asn: 9009}
                  - action: block
                    match:
                      location: {country: [DE, AT]}
                  - action: block
                    reason: never
                    match:
                      vpn: {provider: mullvad}
                """;
        Verdict verdict = check(guard(yaml, ""), VPN_IP);
        assertEquals(Action.BLOCK, verdict.action());
        assertEquals(List.of("M247", "rule #3"), verdict.reasons());
        assertEquals(List.of(), warnings);
    }

    @Test
    void aRuleNamingAMemberThePlanDoesNotServeIsReported() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        String yaml = """
                checks: {vpn: allow}
                rules:
                  - action: block
                    reason: proxies
                    match: {is_resproxy: true}
                """;
        assertEquals(Action.ALLOW, check(guard(yaml, ""), VPN_IP).action());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("'proxies' names is_resproxy")), warnings.toString());
    }

    @Test
    void aBypassedLoginIsNeverLookedUp() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, true));
        String[] lists = {"notch", PLAYER.toString(), PLAYER.toString().replace("-", ""), VPN_IP, "45.83.91.0/24"};
        for (String entry : lists) {
            Verdict verdict = check(guard("", entry + "  # a comment\n"), VPN_IP);
            assertTrue(verdict.isBypassed(), entry);
            assertEquals(Action.ALLOW, verdict.action(), entry);
        }
        assertEquals(0, api.count("/" + VPN_IP));
        assertEquals(Action.BLOCK, check(guard("", "45.83.92.0/24\nJeb_\n"), VPN_IP).action());
    }

    @Test
    void aFailedLookupLetsThePlayerInUnlessFailClosed() throws Exception {
        api.route("/" + VPN_IP, request -> new StubApi.Answer(500, "{\"error\":\"lookup failed\"}", 0));
        Verdict open = check(guard("", ""), VPN_IP);
        assertEquals(Action.ALLOW, open.action());
        assertTrue(open.error().isPresent());

        Verdict closed = check(guard("fail-closed: true", ""), VPN_IP);
        assertEquals(Action.BLOCK, closed.action());
        assertTrue(closed.error().isPresent());
    }

    @Test
    void aPrivateAddressIsLetInWithoutALookupAndReportedOnce() throws Exception {
        Guard guard = guard("", "");
        assertEquals(Action.ALLOW, check(guard, "10.0.0.7").action());
        assertEquals(Action.ALLOW, check(guard, "::ffff:192.168.1.20").action());
        assertTrue(api.requests.isEmpty());
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.get(0).contains("10.0.0.7, a private address"), warnings.toString());
    }

    @Test
    void playersJoiningTogetherFromOneAddressShareOneLookup() throws Exception {
        api.route("/" + VPN_IP, request -> new StubApi.Answer(200, Answers.keyless(VPN_IP, true), 300));
        Guard guard = guard("", "");
        int players = 8;
        ExecutorService pool = Executors.newFixedThreadPool(players);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Verdict>> verdicts = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            UUID uuid = UUID.randomUUID();
            verdicts.add(CompletableFuture.supplyAsync(() -> {
                try {
                    start.await();
                    return guard.check("bot", uuid, InetAddress.getByName(VPN_IP));
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, pool));
        }
        start.countDown();
        for (CompletableFuture<Verdict> verdict : verdicts) {
            assertEquals(Action.BLOCK, verdict.get().action());
        }
        pool.shutdown();
        assertEquals(1, api.count("/" + VPN_IP));
        // The next login from it is answered from the cache.
        assertEquals(Action.BLOCK, check(guard, VPN_IP).action());
        assertEquals(1, api.count("/" + VPN_IP));
    }

    @Test
    void aPrefetchIsTheLookupTheLoginThenUses() throws Exception {
        api.route("/" + VPN_IP, request -> new StubApi.Answer(200, Answers.keyless(VPN_IP, true), 300));
        Guard guard = guard("", "");
        guard.prefetch(InetAddress.getByName(VPN_IP));
        assertEquals(Action.BLOCK, check(guard, VPN_IP).action());
        assertEquals(1, api.count("/" + VPN_IP));
    }

    @Test
    void everyLookupNamesThePluginInItsUserAgent() throws Exception {
        api.answer("/" + VPN_IP, Answers.keyless(VPN_IP, false));
        check(guard("", ""), VPN_IP);
        assertEquals("test/1", api.requests.get(0).header("User-Agent"));
    }
}
