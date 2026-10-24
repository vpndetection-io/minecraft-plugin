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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandsTest {
    private static final String VPN_IP = "45.83.91.1";
    private static final UUID STEVE = UUID.fromString("8667ba71-b85a-4004-af54-457a9734eed7");

    @TempDir
    Path dir;
    private StubApi api;
    private FakePlatform platform;
    private Engine engine;
    private final List<String> said = new CopyOnWriteArrayList<>();
    private boolean admin = true;
    private final Sender sender = new Sender() {
        @Override
        public boolean hasPermission(String permission) {
            return admin && permission.equals(Engine.ADMIN);
        }

        @Override
        public void send(String text) {
            said.add(Text.plain(text));
        }

        @Override
        public void sendLink(String text, String url) {
            said.add(url);
        }
    };

    @BeforeEach
    void start() throws Exception {
        api = new StubApi();
        System.setProperty("vpndetection.api-url", api.url());
        platform = new FakePlatform(dir);
        platform.online.put("steve", new Platform.OnlinePlayer("Steve", STEVE, InetAddress.getByName(VPN_IP)));
        engine = new Engine(platform);
        engine.enable();
    }

    @AfterEach
    void stop() {
        engine.disable();
        System.clearProperty("vpndetection.api-url");
        api.close();
    }

    private void run(String... args) {
        engine.commands().execute(sender, args);
    }

    @Test
    void everyCommandNeedsTheAdminPermission() {
        admin = false;
        run("reload");
        assertEquals(List.of("You need the vpndetection.admin permission for that."), said);
        assertEquals(List.of(), engine.commands().complete(sender, new String[] {"re"}));
    }

    @Test
    void checkSaysWhatTheAnswerHoldsAndWhatALoginGets() throws Exception {
        api.answer("/" + VPN_IP, Answers.paid(VPN_IP));
        run("check", "steve");
        waitForLines(4);
        assertEquals("VPNDetection on 45.83.91.1 (Steve)", said.get(0));
        assertEquals("VPN yes (nordvpn), Tor no, hosting yes (ovh), proxy residential (brightdata), relay no",
                said.get(1));
        assertEquals("Country Austria (AT), network AS9009 M247 Europe SRL (hosting)", said.get(2));
        assertEquals("A login gets: refused, for VPN (nordvpn), hosting (ovh), residential proxy (brightdata)",
                said.get(3));
    }

    @Test
    void checkOnAnAddressThePlanAnswersLittleOfSaysSo() throws Exception {
        api.answer("/1.2.3.4", Answers.keyless("1.2.3.4", false));
        run("check", "1.2.3.4");
        waitForLines(4);
        assertEquals("VPN no, Tor not in your plan, hosting not in your plan, proxy not in your plan, "
                + "relay not in your plan", said.get(1));
        assertEquals("Country not in your plan, network not in your plan", said.get(2));
        assertEquals("A login gets: let in", said.get(3));
    }

    @Test
    void checkOnNeitherAPlayerNorAnAddressLooksNothingUp() {
        run("check", "example.com");
        assertEquals(List.of("No online player or address is called example.com."), said);
        assertTrue(api.requests.isEmpty());
    }

    @Test
    void anOnlinePlayerIsBypassedByUuidAndNamedBesideIt() throws Exception {
        run("bypass", "add", "Steve");
        assertEquals("Steve is never checked now.", said.get(0));
        String file = Files.readString(dir.resolve("bypass.txt"));
        assertTrue(file.contains(STEVE + "  # Steve, added "), file);
        assertTrue(engine.login("Steve", STEVE, InetAddress.getByName(VPN_IP)).isBypassed());

        run("bypass", "list");
        assertEquals(List.of("Never checked (1):", STEVE.toString()), said.subList(1, 3));

        run("bypass", "remove", "Steve");
        assertEquals("Steve is checked again.", said.get(3));
        assertTrue(engine.state().bypass().entries().isEmpty());
    }

    @Test
    void anAddressOrARangeIsBypassedAsWritten() throws Exception {
        run("bypass", "add", "45.83.91.0/24");
        run("bypass", "add", "45.83.91.7/24");
        assertEquals(List.of("45.83.91.0/24 is never checked now.", "45.83.91.0/24 was in bypass.txt already."), said);
        run("bypass", "add", "not an address");
        assertEquals("not an address is not a player name, UUID, address or range.", said.get(2));
    }

    @Test
    void tabCompletionOffersSubcommandsAndPlayers() {
        assertEquals(List.of("bypass"), engine.commands().complete(sender, new String[] {"by"}));
        assertEquals(List.of("Steve"), engine.commands().complete(sender, new String[] {"check", "st"}));
        assertEquals(List.of("list"), engine.commands().complete(sender, new String[] {"bypass", "l"}));
    }

    @Test
    void statusWithNoKeySaysHowToSignIn() {
        run("status");
        assertTrue(said.get(0).startsWith("VPNDetection " + engine.version() + " on Fake 1.0"), said.toString());
        assertEquals("Checks: vpn block, tor block, hosting block, proxy block, relay allow", said.get(1));
        assertEquals("Since the server started: 0 logins checked, 0 refused, 0 flagged, 0 bypassed, 0 lookups failed.",
                said.get(2));
        assertEquals("No API key, so only VPNs are checked. Run /vpnd login to sign in.", said.get(3));
    }

    @Test
    void reloadSaysWhatItFound() throws Exception {
        Files.writeString(dir.resolve("config.yml"), "checks: {vpn: allert}\n");
        run("reload");
        assertEquals("Read config.yml and bypass.txt again. Checks: vpn block, tor block, hosting block, "
                + "proxy block, relay allow", said.get(0));
        assertEquals("checks.vpn is 'allert', not block, alert or allow", said.get(1));
    }

    private void waitForLines(int count) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (said.size() < count && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(said.size() >= count, said.toString());
    }
}
