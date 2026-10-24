package io.vpndetection.minecraft;

import io.vpndetection.DeviceAuthorizationOptions;
import io.vpndetection.OauthAccessDeniedException;
import io.vpndetection.OauthApi;
import io.vpndetection.OauthExpiredTokenException;
import io.vpndetection.VPNDetectionException;
import io.vpndetection.model.DeviceAuthorization;
import io.vpndetection.model.TokenResponse;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * Signing in from the server console: OAuth's device flow, which hands this server one of the
 * person's API keys without anyone pasting it.
 *
 * <p>The person opens a link, signs in or creates an account, and picks a key; the key arrives here
 * and goes into config.yml. The grant itself is revoked once the key is saved, since nothing here
 * uses its tokens, so the console lists no app holding access it never uses. One sign-in runs at a
 * time; asking again while one waits shows its code again.
 */
final class Login {
    /** This plugin's client at our authorization server, which names it on the approval page. */
    static final String CLIENT_ID = "vpndetection-minecraft";
    private static final String SCOPE = "apikeys.read apikeys.reveal";

    private final OauthApi oauth;
    private final Executor executor;
    private final AtomicReference<DeviceAuthorization> pending = new AtomicReference<>();

    Login(OauthApi oauth, Executor executor) {
        this.oauth = oauth;
        this.executor = executor;
    }

    /**
     * Starts a sign-in, or shows the one already waiting, and saves the key once it is approved.
     *
     * @param save stores the key and reloads, and says whether it could; it runs on a background thread
     */
    void start(Sender sender, Predicate<String> save) {
        DeviceAuthorization waiting = pending.get();
        if (waiting != null) {
            prompt(sender, waiting);
            return;
        }
        executor.execute(() -> {
            DeviceAuthorization device;
            try {
                device = oauth.deviceAuthorization(CLIENT_ID, new DeviceAuthorizationOptions().scope(SCOPE));
            } catch (VPNDetectionException e) {
                say(sender, "&cCouldn't start signing in: " + e.getMessage());
                return;
            }
            if (!pending.compareAndSet(null, device)) {
                prompt(sender, pending.get());
                return;
            }
            prompt(sender, device);
            try {
                TokenResponse token = oauth.pollDeviceToken(CLIENT_ID, device);
                finish(sender, token, save);
            } catch (OauthAccessDeniedException e) {
                say(sender, "&cSigning in was declined. Run &f/vpnd login &cto try again.");
            } catch (OauthExpiredTokenException e) {
                say(sender, "&cThe code expired before it was approved. Run &f/vpnd login &cto start again.");
            } catch (VPNDetectionException e) {
                say(sender, "&cSigning in failed: " + e.getMessage());
            } finally {
                pending.compareAndSet(device, null);
            }
        });
    }

    private void finish(Sender sender, TokenResponse token, Predicate<String> save) {
        String key = token.getApikey();
        if (token.getRefreshToken() != null) {
            try {
                oauth.revoke(CLIENT_ID, token.getRefreshToken());
            } catch (VPNDetectionException e) {
                // The grant stays listed in the console, where it can be revoked; the key is what matters.
            }
        }
        if (key == null || key.isBlank()) {
            // Either no key was picked, or the one picked was made before its secret could be read
            // back. Saying which is not possible from here, so say what to do about both.
            say(sender, "&cSigned in, but no API key came back. Pick a key on the approval page, "
                    + "or make a new one at &fhttps://app.vpndetection.io&c, then run &f/vpnd login &cagain.");
            return;
        }
        if (!save.test(key)) {
            say(sender, "&cSigned in, but the key couldn't be saved in config.yml; the console says why.");
            return;
        }
        say(sender, "&aSigned in. This server now checks players with the key &f" + mask(key)
                + "&a, saved in config.yml.");
    }

    private static void prompt(Sender sender, DeviceAuthorization device) {
        String url = device.getVerificationUriComplete() != null ? device.getVerificationUriComplete()
                : device.getVerificationUri();
        say(sender, "&7To connect this server to VPNDetection, open this page and confirm the code &f"
                + device.getUserCode() + "&7:");
        sender.sendLink(Text.colorize("&b&n" + url), url);
        int minutes = device.getExpiresIn() == null ? 15 : Math.max(1, device.getExpiresIn() / 60);
        say(sender, "&7Waiting for you to approve. The code works for " + minutes + " minutes.");
    }

    private static void say(Sender sender, String text) {
        sender.send(Text.colorize(text));
    }

    /** A key as it may be shown: its first and last four characters. */
    static String mask(String key) {
        return key.length() <= 12 ? "****" : key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }
}
