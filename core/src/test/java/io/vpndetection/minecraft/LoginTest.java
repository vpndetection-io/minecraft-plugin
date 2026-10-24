package io.vpndetection.minecraft;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginTest {
    private static final String DEVICE = "{\"device_code\":\"dc-1\",\"user_code\":\"WDJB-MJHT\","
            + "\"verification_uri\":\"https://vpndetection.io/device\","
            + "\"verification_uri_complete\":\"https://vpndetection.io/device?user_code=WDJB-MJHT\","
            + "\"expires_in\":900,\"interval\":1}";
    private static final String TOKENS = "{\"access_token\":\"at-1\",\"token_type\":\"Bearer\",\"expires_in\":3600,"
            + "\"refresh_token\":\"rt-1\",\"scope\":\"apikeys.read apikeys.reveal\","
            + "\"mslm:apikey_id\":\"10c2b437-3aa2-4a63-bd17-8e7c8c7f0def\",\"mslm:apikey\":\"vd_test_0123456789abcdef\"}";

    @TempDir
    Path dir;
    private StubApi api;
    private Engine engine;
    private final List<String> said = new CopyOnWriteArrayList<>();
    private final List<String> links = new CopyOnWriteArrayList<>();
    private final Sender console = new Sender() {
        @Override
        public boolean hasPermission(String permission) {
            return true;
        }

        @Override
        public void send(String text) {
            said.add(Text.plain(text));
        }

        @Override
        public void sendLink(String text, String url) {
            links.add(url);
        }
    };

    @BeforeEach
    void start() throws Exception {
        api = new StubApi();
        System.setProperty("vpndetection.api-url", api.url());
        api.answer("/oauth/device_authorization", DEVICE);
        api.answer("/oauth/revoke", "{}");
        engine = new Engine(new FakePlatform(dir));
        engine.enable();
    }

    @AfterEach
    void stop() {
        engine.disable();
        System.clearProperty("vpndetection.api-url");
        api.close();
    }

    @Test
    void anApprovedSignInSavesTheKeyAndRevokesTheGrant() throws Exception {
        AtomicInteger polls = new AtomicInteger();
        api.route("/oauth/token", request -> polls.incrementAndGet() == 1
                ? new StubApi.Answer(400, "{\"error\":\"authorization_pending\"}", 0)
                : StubApi.Answer.ok(TOKENS));
        engine.commands().execute(console, new String[] {"login"});
        waitFor("Signed in");

        assertTrue(said.get(0).contains("confirm the code WDJB-MJHT"), said.toString());
        assertEquals(List.of("https://vpndetection.io/device?user_code=WDJB-MJHT"), links);
        assertEquals("Signed in. This server now checks players with the key vd_t...cdef, saved in config.yml.",
                said.get(said.size() - 1));
        assertTrue(Files.readString(dir.resolve("config.yml")).contains("api-key: \"vd_test_0123456789abcdef\""));
        assertEquals("vd_test_0123456789abcdef", engine.state().apiKey());

        StubApi.Request device = api.requests.stream().filter(r -> r.path().equals("/oauth/device_authorization"))
                .findFirst().orElseThrow();
        assertTrue(device.body().contains("client_id=vpndetection-minecraft"), device.body());
        assertTrue(device.body().contains("scope=apikeys.read"), device.body());
        StubApi.Request revoke = api.requests.stream().filter(r -> r.path().equals("/oauth/revoke"))
                .findFirst().orElseThrow();
        assertTrue(revoke.body().contains("token=rt-1"), revoke.body());
    }

    @Test
    void aDeclinedSignInChangesNothing() throws Exception {
        api.route("/oauth/token", request -> new StubApi.Answer(400, "{\"error\":\"access_denied\"}", 0));
        String before = Files.readString(dir.resolve("config.yml"));
        engine.commands().execute(console, new String[] {"login"});
        waitFor("Signing in was declined");
        assertEquals(before, Files.readString(dir.resolve("config.yml")));
    }

    @Test
    void anApprovalThatHandsOverNoKeySaysWhatToDo() throws Exception {
        api.answer("/oauth/token", TOKENS.replace(",\"mslm:apikey\":\"vd_test_0123456789abcdef\"", ""));
        engine.commands().execute(console, new String[] {"login"});
        waitFor("Signed in, but no API key came back");
    }

    private void waitFor(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (said.stream().anyMatch(s -> s.startsWith(prefix))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("never said " + prefix + ": " + said);
    }
}
