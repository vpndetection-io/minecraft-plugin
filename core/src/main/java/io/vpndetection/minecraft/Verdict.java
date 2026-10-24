package io.vpndetection.minecraft;

import io.vpndetection.Result;
import io.vpndetection.VPNDetectionException;

import java.util.List;
import java.util.Optional;

/**
 * What to do with one login, and why.
 *
 * @param action what happens to the login
 * @param reasons every check, country list and rule that matched with that action, in config order
 * @param country true when a country list decided it, so the country's kick message is shown
 * @param result the answer, empty for a bypassed login or a failed lookup
 * @param error why the lookup failed, empty when it did not
 */
public record Verdict(Action action, List<String> reasons, boolean country, Optional<Result> result,
        Optional<VPNDetectionException> error) {

    static Verdict bypassed() {
        return new Verdict(Action.ALLOW, List.of("bypassed"), false, Optional.empty(), Optional.empty());
    }

    static Verdict failed(VPNDetectionException error, boolean failClosed) {
        return new Verdict(failClosed ? Action.BLOCK : Action.ALLOW, List.of("lookup failed"), false,
                Optional.empty(), Optional.of(error));
    }

    public boolean isBlocked() {
        return action == Action.BLOCK;
    }

    public boolean isBypassed() {
        return result.isEmpty() && error.isEmpty();
    }

    /** The reasons as one phrase for a message: "VPN (nordvpn), hosting (m247)". */
    public String reason() {
        return reasons.isEmpty() ? "nothing matched" : String.join(", ", reasons);
    }
}
