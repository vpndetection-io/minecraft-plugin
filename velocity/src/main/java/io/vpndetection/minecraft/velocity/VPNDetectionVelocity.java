package io.vpndetection.minecraft.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.ResultedEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.LoginEvent;
import com.velocitypowered.api.event.connection.PreLoginEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import io.vpndetection.minecraft.Engine;
import io.vpndetection.minecraft.Verdict;

import org.slf4j.Logger;

import java.net.InetAddress;
import java.nio.file.Path;

/**
 * The plugin on Velocity.
 *
 * <p>The lookup starts at {@link PreLoginEvent}, as the connection arrives, and the decision is made
 * at {@link LoginEvent}, once the player has signed in, so a bypass by UUID is a UUID Mojang vouched
 * for. The lookup runs while the player signs in, so a login rarely waits on it at all.
 */
public final class VPNDetectionVelocity {
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private Engine engine;

    @Inject
    public VPNDetectionVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        engine = new Engine(new VelocityPlatform(proxy, logger, dataDirectory));
        engine.enable();
        CommandMeta meta = proxy.getCommandManager().metaBuilder("vpndetection").aliases("vpnd").plugin(this).build();
        proxy.getCommandManager().register(meta, new VelocityCommand(engine.commands()));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (engine != null) {
            engine.disable();
        }
    }

    @Subscribe(order = PostOrder.LATE)
    public void onPreLogin(PreLoginEvent event) {
        if (event.getResult().isAllowed()) {
            engine.prefetch(event.getConnection().getRemoteAddress().getAddress());
        }
    }

    // LATE, after the ban and whitelist plugins that refuse at the usual order: a login one of them
    // refused costs no lookup.
    @Subscribe(order = PostOrder.LATE)
    public EventTask onLogin(LoginEvent event) {
        return EventTask.async(() -> {
            if (!event.getResult().isAllowed()) {
                return;
            }
            Player player = event.getPlayer();
            InetAddress address = player.getRemoteAddress().getAddress();
            Verdict verdict = engine.login(player.getUsername(), player.getUniqueId(), address);
            if (verdict.isBlocked()) {
                String message = engine.kickMessage(verdict, player.getUsername(), address);
                event.setResult(ResultedEvent.ComponentResult.denied(VelocitySender.component(message)));
            }
        });
    }
}
