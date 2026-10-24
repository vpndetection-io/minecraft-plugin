package io.vpndetection.minecraft;

import io.vpndetection.Result;
import io.vpndetection.model.ClassDetail;
import io.vpndetection.model.ProxyDetail;
import io.vpndetection.model.VpnDetail;

import java.util.Locale;
import java.util.Optional;

/**
 * The five kinds of address config.yml's checks act on.
 *
 * <p>Each reads the answer through the SDK's own accessors, where an empty Optional means a member
 * the plan does not serve: {@link #served} says whether the check can work on this plan at all, so
 * a check that never could is reported once rather than silently never firing.
 */
public enum Check {
    VPN {
        @Override
        boolean served(Result result) {
            return true;
        }

        @Override
        Optional<String> match(Result result) {
            if (!result.isVpn()) {
                return Optional.empty();
            }
            return Optional.of(named("VPN", result.vpn().map(VpnDetail::getProvider)));
        }
    },
    TOR {
        @Override
        boolean served(Result result) {
            return result.isTor().isPresent();
        }

        @Override
        Optional<String> match(Result result) {
            return result.isTor().orElse(false) ? Optional.of("Tor") : Optional.empty();
        }
    },
    HOSTING {
        // A free key's answer carries no hosting flag but does say what kind of network announces
        // the address, which tells a datacenter apart from an ISP well enough to keep bots out. The
        // flag, where the plan serves it, is per address and wins.
        @Override
        boolean served(Result result) {
            return result.isHosting().isPresent() || result.asn().isPresent();
        }

        @Override
        Optional<String> match(Result result) {
            if (result.isHosting().isPresent()) {
                if (!result.isHosting().get()) {
                    return Optional.empty();
                }
                return Optional.of(named("hosting", result.hosting().map(ClassDetail::getProvider)));
            }
            return result.asn()
                    .filter(asn -> "hosting".equalsIgnoreCase(asn.getType()))
                    .map(asn -> named("hosting network", Optional.ofNullable(asn.getName())));
        }
    },
    PROXY {
        @Override
        boolean served(Result result) {
            return result.isResproxy().isPresent() || result.isDcproxy().isPresent()
                    || result.isMobproxy().isPresent() || result.isPubproxy().isPresent();
        }

        @Override
        Optional<String> match(Result result) {
            if (result.isResproxy().orElse(false)) {
                return Optional.of(named("residential proxy", result.resproxy().map(ProxyDetail::getProvider)));
            }
            if (result.isDcproxy().orElse(false)) {
                return Optional.of(named("datacenter proxy", result.dcproxy().map(ProxyDetail::getProvider)));
            }
            if (result.isMobproxy().orElse(false)) {
                return Optional.of(named("mobile proxy", result.mobproxy().map(ProxyDetail::getProvider)));
            }
            return result.isPubproxy().orElse(false) ? Optional.of("public proxy") : Optional.empty();
        }
    },
    RELAY {
        @Override
        boolean served(Result result) {
            return result.isRelay().isPresent();
        }

        @Override
        Optional<String> match(Result result) {
            if (!result.isRelay().orElse(false)) {
                return Optional.empty();
            }
            return Optional.of(named("privacy relay", result.relay().map(ClassDetail::getProvider)));
        }
    };

    /** The spelling config.yml uses. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The check config.yml calls {@code key}, or null. */
    static Check of(String key) {
        for (Check check : values()) {
            if (check.key().equals(key)) {
                return check;
            }
        }
        return null;
    }

    /** Whether the answer carries what this check reads. False means the plan does not serve it. */
    abstract boolean served(Result result);

    /** Why this check matches the answer, or empty when it does not. */
    abstract Optional<String> match(Result result);

    // "VPN (nordvpn)", or "VPN" where nobody is named: an unattributed range, or a plan that serves
    // the flag without its detail.
    private static String named(String what, Optional<String> who) {
        return who.filter(name -> !name.isBlank()).map(name -> what + " (" + name + ")").orElse(what);
    }
}
