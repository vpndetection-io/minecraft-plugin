package io.vpndetection.minecraft;

import io.vpndetection.LookupOptions;
import io.vpndetection.Result;
import io.vpndetection.VPNDetection;
import io.vpndetection.VPNDetectionException;
import io.vpndetection.middleware.Options;
import io.vpndetection.model.Location;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Decides what happens to a login, for one reading of config.yml and bypass.txt.
 *
 * <p>Built afresh on every reload; the client it is handed outlives it, so the cache does too. The
 * lookup a login waits on is bounded as a web middleware's is, 2.5 seconds and no retry: a player
 * held longer is worse off than one let in unchecked, which is what a failure does unless
 * fail-closed says otherwise.
 */
final class Guard {
    private static final LookupOptions LOGIN =
            new LookupOptions().retries(Options.DEFAULT_RETRIES).requestTimeout(Options.DEFAULT_TIMEOUT);

    private final Settings settings;
    private final Bypass bypass;
    private final VPNDetection client;
    private final Consumer<String> warnOnce;
    private final AtomicBoolean privateSeen = new AtomicBoolean();

    Guard(Settings settings, Bypass bypass, VPNDetection client, Consumer<String> warnOnce) {
        this.settings = settings;
        this.bypass = bypass;
        this.client = client;
        this.warnOnce = warnOnce;
    }

    /**
     * Looks up a joining player's address and judges the answer. Blocks for the lookup, so it runs on
     * the platform's login thread, never a server tick. Any of name and uuid may be null where the
     * platform does not know them yet.
     */
    Verdict check(String name, UUID uuid, InetAddress address) {
        if (bypass.covers(name, uuid, address)) {
            return Verdict.bypassed();
        }
        try {
            return judge(client.lookup(address.getHostAddress(), LOGIN));
        } catch (VPNDetectionException e) {
            return Verdict.failed(e, settings.failClosed);
        }
    }

    /**
     * Starts the lookup a later {@link #check} will need, without waiting for it. A proxy calls it as
     * the connection arrives, so the answer is in hand, or in the air and shared, by the time the
     * player has signed in.
     */
    void prefetch(InetAddress address) {
        if (!bypass.coversAddress(address)) {
            client.lookupAsync(address.getHostAddress(), LOGIN).exceptionally(e -> null);
        }
    }

    /** What config.yml makes of an answer, with nothing looked up. */
    Verdict judge(Result result) {
        if (result.isBogon()) {
            // Expected on a test server or a LAN. On a public server it means a proxy forwards its
            // players without passing their addresses on. Said once, whichever address it was.
            if (privateSeen.compareAndSet(false, true)) {
                warnOnce.accept("a player joined from " + result.ip() + ", a private address, so nothing was "
                        + "looked up. Behind a proxy, install VPNDetection on the proxy, or turn on its "
                        + "address forwarding.");
            }
            return new Verdict(Action.ALLOW, List.of(), false, Optional.of(result), Optional.empty());
        }
        Tally tally = new Tally();
        for (Map.Entry<Check, Action> entry : settings.checks.entrySet()) {
            Check check = entry.getKey();
            Action action = entry.getValue();
            if (action == Action.ALLOW) {
                continue;
            }
            if (!check.served(result)) {
                warnOnce.accept("checks." + check.key() + " is " + action.key() + ", but " + needs(check)
                        + ", so it never matches.");
                continue;
            }
            check.match(result).ifPresent(reason -> tally.add(action, reason, false));
        }
        countries(result, tally);
        for (Rule rule : settings.rules) {
            List<String> missing = rule.missing(result);
            if (!missing.isEmpty()) {
                warnOnce.accept("the rule '" + rule.reason() + "' names " + String.join(", ", missing)
                        + ", which your plan doesn't answer, so it never matches.");
            }
            if (rule.matches(result)) {
                tally.add(rule.action(), rule.reason(), false);
            }
        }
        return tally.verdict(result);
    }

    private void countries(Result result, Tally tally) {
        if (settings.blockCountries.isEmpty() && settings.allowCountries.isEmpty()) {
            return;
        }
        Optional<Location> location = result.location();
        if (location.isEmpty()) {
            warnOnce.accept("countries need an API key: without one, no answer says where an address is. "
                    + "Run /vpnd login to sign in.");
            return;
        }
        // A country we can't place, an anycast address say, is let in rather than guessed at.
        String code = location.get().getCountry();
        String country = code == null ? "" : code.toUpperCase(Locale.ROOT);
        if (country.isEmpty()) {
            return;
        }
        boolean refused = settings.blockCountries.contains(country)
                || (!settings.allowCountries.isEmpty() && !settings.allowCountries.contains(country));
        if (refused) {
            tally.add(Action.BLOCK, "country (" + country + ")", true);
        }
    }

    // Why a check's answer is missing, as config.yml's comments put it.
    private String needs(Check check) {
        if (check == Check.PROXY) {
            return "only a paid plan answers it";
        }
        return settings.apiKey == null ? "it needs an API key (run /vpnd login)"
                : "your plan doesn't answer it";
    }

    /** The reasons for each action seen, to report the strongest action's. */
    private static final class Tally {
        private final List<String> blocks = new ArrayList<>();
        private final List<String> alerts = new ArrayList<>();
        private boolean blockedOnlyByCountry = true;

        void add(Action action, String reason, boolean country) {
            if (action == Action.BLOCK) {
                blocks.add(reason);
                blockedOnlyByCountry &= country;
            } else if (action == Action.ALERT) {
                alerts.add(reason);
            }
        }

        Verdict verdict(Result result) {
            if (!blocks.isEmpty()) {
                return new Verdict(Action.BLOCK, List.copyOf(blocks), blockedOnlyByCountry, Optional.of(result),
                        Optional.empty());
            }
            if (!alerts.isEmpty()) {
                return new Verdict(Action.ALERT, List.copyOf(alerts), false, Optional.of(result),
                        Optional.empty());
            }
            return new Verdict(Action.ALLOW, List.of(), false, Optional.of(result), Optional.empty());
        }
    }
}
