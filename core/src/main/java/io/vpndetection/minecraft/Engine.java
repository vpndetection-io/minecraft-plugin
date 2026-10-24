package io.vpndetection.minecraft;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.vpndetection.ErrorKind;
import io.vpndetection.Result;
import io.vpndetection.VPNDetection;
import io.vpndetection.VPNDetectionException;
import io.vpndetection.model.Entitlement;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Everything the platforms share, from start to stop: the files, the settings, the client, what each
 * login comes to, and what staff and the console are told about it.
 *
 * <p>A platform module builds one with its {@link Platform}, calls {@link #enable} and {@link #disable}
 * with its own lifecycle, hands each login to {@link #login} on a thread that may block, and each
 * command to {@link #commands}.
 */
public final class Engine {
    /** Runs every /vpnd command. */
    public static final String ADMIN = "vpndetection.admin";
    /** Is told in chat about refused and flagged logins. */
    public static final String ALERTS = "vpndetection.alerts";
    private static final String ENV_KEY = "VPNDETECTION_API_KEY";
    // Points the plugin at another deployment of the API, for its own tests: a JVM flag rather than a
    // setting, since nobody running a server has a reason to.
    private static final String API_URL_PROPERTY = "vpndetection.api-url";

    private final Platform platform;
    private final String version;
    private final ExecutorService executor;
    private final ScheduledExecutorService timer;
    private final Alerts alerts;
    private final VPNDetection signIn;
    private final Login login;
    private final Commands commands;
    private final Set<String> warned = ConcurrentHashMap.newKeySet();
    private final Cache<ErrorKind, Boolean> failureTold = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .build();
    private final Cache<ErrorKind, Boolean> spentTold = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .build();
    private volatile State state;

    final AtomicLong checked = new AtomicLong();
    final AtomicLong refused = new AtomicLong();
    final AtomicLong flagged = new AtomicLong();
    final AtomicLong failed = new AtomicLong();
    final AtomicLong bypassed = new AtomicLong();

    /** One reading of the files, with the client and the decision built from it, swapped whole. */
    record State(Settings settings, Bypass bypass, VPNDetection client, String apiKey, String keySource,
            Guard guard) {}

    public Engine(Platform platform) {
        this.platform = platform;
        this.version = readVersion();
        this.executor = Executors.newCachedThreadPool(daemon("vpndetection-plugin"));
        this.timer = Executors.newSingleThreadScheduledExecutor(daemon("vpndetection-timer"));
        this.alerts = new Alerts(platform, timer, System::nanoTime);
        this.signIn = clientBuilder(null, Duration.ZERO).build();
        this.login = new Login(signIn.oauth(), executor);
        this.commands = new Commands(this);
    }

    /** Writes the default files where there are none, reads them, and says what it will do. */
    public void enable() {
        try {
            ConfigFile.writeDefaults(platform.dataDirectory());
        } catch (IOException e) {
            platform.warn("couldn't write the default config.yml and bypass.txt: " + e.getMessage());
        }
        List<String> problems = new ArrayList<>();
        state = load(null, problems);
        report(problems);
        platform.info("VPNDetection " + version + " on " + platform.server() + ": "
                + describe(state.settings()));
        checkKey(state);
    }

    /** Re-reads config.yml and bypass.txt. One that can't be read leaves the current settings in place. */
    public List<String> reload() {
        List<String> problems = new ArrayList<>();
        synchronized (this) {
            State before = state;
            State after = load(before, problems);
            state = after;
            warned.clear();
            if (after.client() != before.client()) {
                before.client().close();
                checkKey(after);
            }
        }
        report(problems);
        return problems;
    }

    public void disable() {
        executor.shutdownNow();
        timer.shutdownNow();
        State current = state;
        if (current != null) {
            current.client().close();
        }
        signIn.close();
    }

    /**
     * What happens to a joining player, decided now. Blocks for the lookup, up to 2.5 seconds, so
     * call it on a login thread. Logs the outcome and tells staff about it.
     */
    public Verdict login(String name, UUID uuid, InetAddress address) {
        State current = state;
        Verdict verdict = current.guard().check(name, uuid, address);
        record(current, verdict, name, address);
        return verdict;
    }

    /** Starts the lookup a later {@link #login} for this address will need, as the connection arrives. */
    public void prefetch(InetAddress address) {
        state.guard().prefetch(address);
    }

    /** The message a refused player is shown, colored. */
    public String kickMessage(Verdict verdict, String name, InetAddress address) {
        Settings settings = state.settings();
        String template = verdict.error().isPresent() ? settings.message("failed")
                : verdict.country() ? settings.message("kick-country") : settings.message("kick");
        return Text.format(template, values(verdict, name, address));
    }

    public Commands commands() {
        return commands;
    }

    String version() {
        return version;
    }

    Platform platform() {
        return platform;
    }

    State state() {
        return state;
    }

    Login signIn() {
        return login;
    }

    ExecutorService executor() {
        return executor;
    }

    /** Saves a key from signing in, and starts using it. False when config.yml could not be written. */
    boolean saveApiKey(String key) {
        try {
            ConfigFile.saveApiKey(platform.dataDirectory().resolve(ConfigFile.CONFIG), key);
        } catch (IOException e) {
            platform.warn("couldn't save the API key in config.yml: " + e.getMessage());
            return false;
        }
        reload();
        return true;
    }

    /** An answer for /vpnd check, looked up on the client's own bounds rather than a login's. */
    Result lookup(InetAddress address) {
        return state.client().lookup(address.getHostAddress());
    }

    Entitlement entitlement() {
        return state.client().myEntitlement();
    }

    private State load(State before, List<String> problems) {
        Path dir = platform.dataDirectory();
        Settings settings;
        try {
            Map<String, Object> config = ConfigFile.read(dir.resolve(ConfigFile.CONFIG));
            settings = Settings.parse(config, ConfigFile.defaults(), problems);
        } catch (IOException | IllegalArgumentException e) {
            if (before != null) {
                problems.add("config.yml can't be read, so the settings stay as they were: " + e.getMessage());
                settings = before.settings();
            } else {
                problems.add("config.yml can't be read, so the defaults apply until it's fixed: "
                        + e.getMessage());
                settings = Settings.parse(Map.of(), ConfigFile.defaults(), problems);
            }
        }
        Bypass bypass;
        try {
            bypass = Bypass.load(dir.resolve(ConfigFile.BYPASS), problems);
        } catch (IOException e) {
            problems.add("bypass.txt can't be read: " + e.getMessage());
            bypass = before != null ? before.bypass() : Bypass.empty(dir.resolve(ConfigFile.BYPASS));
        }
        String key = settings.apiKey;
        String source = "config.yml";
        if (key == null) {
            String env = System.getenv(ENV_KEY);
            key = env == null || env.isBlank() ? null : env.strip();
            source = key == null ? null : "the " + ENV_KEY + " environment variable";
        }
        VPNDetection client;
        if (before != null && sameClient(before, key, settings)) {
            client = before.client();
        } else {
            try {
                client = clientBuilder(key, settings.cacheTtl).build();
            } catch (IllegalArgumentException e) {
                problems.add("the API key can't be used (" + e.getMessage() + "), so lookups go without one");
                client = clientBuilder(null, settings.cacheTtl).build();
                key = null;
                source = null;
            }
        }
        Guard guard = new Guard(settings, bypass, client, this::warnOnce);
        return new State(settings, bypass, client, key, source, guard);
    }

    // A new key, or a new cache lifetime, needs a client of its own; anything else keeps the client,
    // and so the answers it has cached.
    private static boolean sameClient(State before, String key, Settings settings) {
        return Objects.equals(before.apiKey(), key) && before.settings().cacheTtl.equals(settings.cacheTtl);
    }

    private VPNDetection.Builder clientBuilder(String key, Duration cacheTtl) {
        VPNDetection.Builder builder = VPNDetection.builder()
                .httpClient(new UserAgentHttpClient(userAgent()))
                .executor(executor);
        if (key != null) {
            builder.apiKey(key);
        }
        if (cacheTtl.isZero()) {
            builder.cacheEnabled(false);
        } else {
            builder.cacheTtl(cacheTtl);
        }
        String base = System.getProperty(API_URL_PROPERTY);
        if (base != null && !base.isBlank()) {
            builder.baseUrl(base);
        }
        return builder;
    }

    private String userAgent() {
        return "vpndetection-minecraft/" + version + " (" + platform.server() + "; Java "
                + Runtime.version().feature() + ")";
    }

    // Says on start, and when the key changes, whether the key works and how much of its allowance is
    // left, so a wrong key is found before the first player is let in unchecked.
    private void checkKey(State current) {
        if (current.apiKey() == null) {
            platform.info("No API key, so only VPNs are checked, on a small daily allowance. "
                    + "Run /vpnd login to sign in and check for Tor, hosting networks, proxies and countries "
                    + "too.");
            return;
        }
        executor.execute(() -> {
            try {
                Entitlement entitlement = current.client().myEntitlement();
                platform.info("Signed in with " + Login.mask(current.apiKey()) + " from " + current.keySource()
                        + ": "
                        + Commands.usage(entitlement) + ".");
            } catch (VPNDetectionException e) {
                if (e.kind() == ErrorKind.UNAUTHORIZED || e.kind() == ErrorKind.FORBIDDEN) {
                    platform.warn("The API key " + Login.mask(current.apiKey()) + " from " + current.keySource()
                            + " was refused, so every lookup will fail. Check it, or run /vpnd login.");
                } else {
                    platform.warn("Couldn't check the API key: " + e.getMessage());
                }
            }
        });
    }

    private void record(State current, Verdict verdict, String name, InetAddress address) {
        checked.incrementAndGet();
        if (verdict.isBypassed()) {
            bypassed.incrementAndGet();
            return;
        }
        verdict.error().ifPresent(error -> failure(current, error));
        Map<String, String> values = values(verdict, name, address);
        String template = null;
        if (verdict.isBlocked()) {
            refused.incrementAndGet();
            template = current.settings().message("blocked");
        } else if (verdict.action() == Action.ALERT) {
            flagged.incrementAndGet();
            template = current.settings().message("alert");
        }
        if (template != null && !template.isBlank()) {
            alerts.login(address.getHostAddress(), Text.format(template, values));
        }
    }

    private void failure(State current, VPNDetectionException error) {
        failed.incrementAndGet();
        ErrorKind kind = error.kind();
        String outcome = current.settings().failClosed ? "players are refused" : "players are let in unchecked";
        if (kind == ErrorKind.QUOTA_EXCEEDED) {
            if (spentTold.asMap().putIfAbsent(kind, Boolean.TRUE) == null) {
                String message = "Your lookup allowance is spent, so " + outcome
                        + " until it renews. /vpnd status says when.";
                platform.warn(message);
                alerts.notice(Text.colorize("&c" + message));
            }
            return;
        }
        if (failureTold.asMap().putIfAbsent(kind, Boolean.TRUE) == null) {
            String why = kind == ErrorKind.UNAUTHORIZED || kind == ErrorKind.FORBIDDEN
                    ? "the API key was refused" : error.getMessage();
            platform.warn("A lookup failed (" + why + "), so " + outcome + " while it keeps failing. "
                    + "This is said once every ten minutes.");
        }
    }

    private static Map<String, String> values(Verdict verdict, String name, InetAddress address) {
        String country = verdict.result()
                .flatMap(Result::location)
                .map(location -> location.getCountry() == null ? "" : location.getCountry())
                .filter(code -> !code.isEmpty())
                .orElse("unknown");
        return Map.of(
                "player", name == null ? "?" : name,
                "ip", address.getHostAddress(),
                "reason", verdict.reason(),
                "country", country);
    }

    void warnOnce(String message) {
        if (warned.add(message)) {
            platform.warn(message);
        }
    }

    private void report(List<String> problems) {
        for (String problem : problems) {
            platform.warn(problem);
        }
    }

    static String describe(Settings settings) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<Check, Action> entry : settings.checks.entrySet()) {
            parts.add(entry.getKey().key() + " " + entry.getValue().key());
        }
        String out = String.join(", ", parts);
        if (!settings.blockCountries.isEmpty()) {
            out += "; refusing " + String.join(", ", settings.blockCountries);
        }
        if (!settings.allowCountries.isEmpty()) {
            out += "; only from " + String.join(", ", settings.allowCountries);
        }
        if (!settings.rules.isEmpty()) {
            out += "; " + settings.rules.size() + (settings.rules.size() == 1 ? " rule" : " rules");
        }
        return out;
    }

    private static String readVersion() {
        try (InputStream in = Engine.class.getResourceAsStream("vpndetection-minecraft.properties")) {
            Properties properties = new Properties();
            properties.load(in);
            return properties.getProperty("version", "dev");
        } catch (IOException | NullPointerException e) {
            return "dev";
        }
    }

    private static ThreadFactory daemon(String name) {
        AtomicLong seq = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, name + "-" + seq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
