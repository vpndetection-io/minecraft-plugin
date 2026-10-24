package io.vpndetection.minecraft.paper;

import io.vpndetection.minecraft.Engine;
import io.vpndetection.minecraft.Verdict;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabExecutor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * The plugin on Paper, Spigot, Folia and their forks.
 *
 * <p>A login is checked on {@link AsyncPlayerPreLoginEvent}, which the server fires off its main
 * thread and waits on, so the lookup holds that one login and never a tick. On a server behind a
 * proxy the event carries the address the proxy forwarded; installed on the proxy instead, the plugin
 * checks each player once for the whole network.
 */
public final class VPNDetectionPaper extends JavaPlugin implements Listener, TabExecutor {
    private Engine engine;
    private Scheduler scheduler;

    @Override
    public void onEnable() {
        scheduler = Scheduler.of(this);
        engine = new Engine(new PaperPlatform(this, scheduler));
        engine.enable();
        getServer().getPluginManager().registerEvents(this, this);
        PluginCommand command = getCommand("vpndetection");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (engine != null) {
            engine.disable();
        }
    }

    // HIGH, after the ban and whitelist plugins that refuse at the usual priorities: a login one of
    // them refused costs no lookup.
    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        Verdict verdict = engine.login(event.getName(), event.getUniqueId(), event.getAddress());
        if (verdict.isBlocked()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    engine.kickMessage(verdict, event.getName(), event.getAddress()));
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        engine.commands().execute(new PaperSender(sender, scheduler), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return engine.commands().complete(new PaperSender(sender, scheduler), args);
    }
}
