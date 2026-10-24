package io.vpndetection.minecraft.bungee;

import io.vpndetection.minecraft.Commands;
import io.vpndetection.minecraft.Sender;

import net.md_5.bungee.api.CommandSender;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.plugin.Command;
import net.md_5.bungee.api.plugin.TabExecutor;

final class BungeeCommand extends Command implements TabExecutor {
    private final Commands commands;

    BungeeCommand(Commands commands) {
        // No permission on the command itself, so a player without one is told which one it takes.
        super("vpndetection", null, "vpnd");
        this.commands = commands;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        commands.execute(sender(sender), args);
    }

    @Override
    public Iterable<String> onTabComplete(CommandSender sender, String[] args) {
        return commands.complete(sender(sender), args);
    }

    private static Sender sender(CommandSender sender) {
        return new Sender() {
            @Override
            public boolean hasPermission(String permission) {
                return sender.hasPermission(permission);
            }

            @Override
            public void send(String text) {
                sender.sendMessage(TextComponent.fromLegacyText(text));
            }

            @Override
            public void sendLink(String text, String url) {
                TextComponent link = new TextComponent(TextComponent.fromLegacyText(text));
                link.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url));
                sender.sendMessage(link);
            }
        };
    }
}
