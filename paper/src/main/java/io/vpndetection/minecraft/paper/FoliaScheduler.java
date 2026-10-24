package io.vpndetection.minecraft.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/** Folia's: the global region for the server as a whole, and each player's own scheduler for them. */
final class FoliaScheduler implements Scheduler {
    private final Plugin plugin;

    FoliaScheduler(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void global(Runnable task) {
        if (plugin.isEnabled()) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        }
    }

    @Override
    public void player(Player player, Runnable task) {
        // A player who left takes the task with them; null is "nothing to do then".
        if (plugin.isEnabled()) {
            player.getScheduler().run(plugin, scheduled -> task.run(), null);
        }
    }
}
