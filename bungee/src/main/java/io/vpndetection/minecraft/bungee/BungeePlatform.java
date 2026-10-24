package io.vpndetection.minecraft.bungee;

import io.vpndetection.minecraft.Platform;

import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.plugin.Plugin;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

final class BungeePlatform implements Platform {
    private final Plugin plugin;

    BungeePlatform(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String server() {
        // "git:BungeeCord-Bootstrap:1.21-R0.4-SNAPSHOT:abc1234:1997": the release, without the build.
        String version = ProxyServer.getInstance().getVersion();
        String[] parts = version.split(":");
        String release = parts.length >= 3 ? parts[2].split("-")[0] : version;
        return ProxyServer.getInstance().getName() + " " + release;
    }

    @Override
    public Path dataDirectory() {
        return plugin.getDataFolder().toPath();
    }

    @Override
    public void info(String message) {
        plugin.getLogger().info(message);
    }

    @Override
    public void warn(String message) {
        plugin.getLogger().warning(message);
    }

    @Override
    public void notify(String permission, String text) {
        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(TextComponent.fromLegacyText(text));
            }
        }
    }

    @Override
    public Optional<OnlinePlayer> player(String name) {
        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(name);
        if (player == null || !(player.getSocketAddress() instanceof InetSocketAddress socket)) {
            return Optional.empty();
        }
        return Optional.of(new OnlinePlayer(player.getName(), player.getUniqueId(), socket.getAddress()));
    }

    @Override
    public List<String> playerNames() {
        return ProxyServer.getInstance().getPlayers().stream().map(ProxiedPlayer::getName).toList();
    }
}
