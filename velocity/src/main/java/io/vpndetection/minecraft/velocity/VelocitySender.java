package io.vpndetection.minecraft.velocity;

import com.velocitypowered.api.command.CommandSource;

import io.vpndetection.minecraft.Sender;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class VelocitySender implements Sender {
    // Core colors text with section signs, the 1.16 hex form included.
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final CommandSource source;

    VelocitySender(CommandSource source) {
        this.source = source;
    }

    static Component component(String text) {
        return LEGACY.deserialize(text);
    }

    @Override
    public boolean hasPermission(String permission) {
        return source.hasPermission(permission);
    }

    @Override
    public void send(String text) {
        source.sendMessage(component(text));
    }

    @Override
    public void sendLink(String text, String url) {
        source.sendMessage(component(text).clickEvent(ClickEvent.openUrl(url)));
    }
}
