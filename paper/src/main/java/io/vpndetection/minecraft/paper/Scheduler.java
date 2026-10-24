package io.vpndetection.minecraft.paper;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Where server state may be touched: Bukkit's main thread, or on Folia the region that owns it.
 *
 * <p>Folia's schedulers live in {@link FoliaScheduler}, a class of their own that only a Folia server
 * ever loads, so Spigot, which has none of their types, never resolves them.
 */
interface Scheduler {
    /** Runs a task that reads the server as a whole: the online players, the console. */
    void global(Runnable task);

    /** Runs a task that touches one player. */
    void player(Player player, Runnable task);

    static Scheduler of(Plugin plugin) {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return new FoliaScheduler(plugin);
        } catch (ClassNotFoundException notFolia) {
            return new MainThread(plugin);
        }
    }

    /** Bukkit's: everything on the main thread. */
    final class MainThread implements Scheduler {
        private final Plugin plugin;

        MainThread(Plugin plugin) {
            this.plugin = plugin;
        }

        @Override
        public void global(Runnable task) {
            if (Bukkit.isPrimaryThread()) {
                task.run();
            } else if (plugin.isEnabled()) {
                Bukkit.getScheduler().runTask(plugin, task);
            }
        }

        @Override
        public void player(Player player, Runnable task) {
            global(task);
        }
    }
}
