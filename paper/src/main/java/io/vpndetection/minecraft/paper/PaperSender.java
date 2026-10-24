package io.vpndetection.minecraft.paper;

import io.vpndetection.minecraft.Sender;

import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

final class PaperSender implements Sender {
    private final CommandSender sender;
    private final Scheduler scheduler;

    PaperSender(CommandSender sender, Scheduler scheduler) {
        this.sender = sender;
        this.scheduler = scheduler;
    }

    @Override
    public boolean hasPermission(String permission) {
        return sender.hasPermission(permission);
    }

    @Override
    public void send(String text) {
        if (sender instanceof Player player) {
            scheduler.player(player, () -> player.sendMessage(text));
        } else {
            scheduler.global(() -> sender.sendMessage(text));
        }
    }

    @Override
    public void sendLink(String text, String url) {
        if (!(sender instanceof Player player)) {
            send(text);
            return;
        }
        // Spigot's chat components, which Paper keeps too, so one call makes a link on both.
        scheduler.player(player, () -> {
            TextComponent link = new TextComponent(TextComponent.fromLegacyText(text));
            link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
            player.spigot().sendMessage(link);
        });
    }
}
