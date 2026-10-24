package io.vpndetection.minecraft.bungee;

import io.vpndetection.minecraft.Engine;
import io.vpndetection.minecraft.Verdict;

import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.PendingConnection;
import net.md_5.bungee.api.event.LoginEvent;
import net.md_5.bungee.api.event.PreLoginEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;
import net.md_5.bungee.event.EventPriority;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * The plugin on BungeeCord and its forks.
 *
 * <p>As on Velocity, the lookup starts at {@link PreLoginEvent} and the decision waits for
 * {@link LoginEvent}, once the player has signed in, holding the login with an intent while the
 * engine's thread decides.
 */
public final class VPNDetectionBungee extends Plugin implements Listener {
    private Engine engine;

    @Override
    public void onEnable() {
        engine = new Engine(new BungeePlatform(this));
        engine.enable();
        getProxy().getPluginManager().registerListener(this, this);
        getProxy().getPluginManager().registerCommand(this, new BungeeCommand(engine.commands()));
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.disable();
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(PreLoginEvent event) {
        InetAddress address = address(event.getConnection());
        if (!event.isCancelled() && address != null) {
            engine.prefetch(address);
        }
    }

    // HIGH, after the ban and whitelist plugins that refuse at the usual priorities: a login one of
    // them refused costs no lookup.
    @EventHandler(priority = EventPriority.HIGH)
    public void onLogin(LoginEvent event) {
        PendingConnection connection = event.getConnection();
        InetAddress address = address(connection);
        if (event.isCancelled() || address == null) {
            return;
        }
        event.registerIntent(this);
        getProxy().getScheduler().runAsync(this, () -> {
            try {
                Verdict verdict = engine.login(connection.getName(), connection.getUniqueId(), address);
                if (verdict.isBlocked()) {
                    event.setCancelled(true);
                    event.setCancelReason(TextComponent.fromLegacyText(
                            engine.kickMessage(verdict, connection.getName(), address)));
                }
            } finally {
                event.completeIntent(this);
            }
        });
    }

    // A listener on a Unix socket has no address to look up.
    private static InetAddress address(PendingConnection connection) {
        SocketAddress socket = connection.getSocketAddress();
        return socket instanceof InetSocketAddress inet ? inet.getAddress() : null;
    }
}
