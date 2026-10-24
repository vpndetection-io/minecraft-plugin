package io.vpndetection.minecraft.paper;

import io.vpndetection.minecraft.Platform;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class PaperPlatform implements Platform {
    private static final Pattern RELEASE = Pattern.compile("^\\d+(\\.\\d+)*");

    private final JavaPlugin plugin;
    private final Scheduler scheduler;

    PaperPlatform(JavaPlugin plugin, Scheduler scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
    }

    @Override
    public String server() {
        // "1.21.11-R0.1-SNAPSHOT", or "26.3.build.222-stable": the release at its start.
        Matcher release = RELEASE.matcher(Bukkit.getBukkitVersion());
        return Bukkit.getName() + " " + (release.find() ? release.group() : Bukkit.getBukkitVersion());
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
        scheduler.global(() -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                scheduler.player(player, () -> {
                    if (player.hasPermission(permission)) {
                        player.sendMessage(text);
                    }
                });
            }
        });
    }

    @Override
    public Optional<OnlinePlayer> player(String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().equalsIgnoreCase(name)) {
                    player = online;
                }
            }
        }
        InetSocketAddress address = player == null ? null : player.getAddress();
        if (address == null || address.getAddress() == null) {
            return Optional.empty();
        }
        return Optional.of(new OnlinePlayer(player.getName(), player.getUniqueId(), address.getAddress()));
    }

    @Override
    public List<String> playerNames() {
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
    }
}
