package io.vpndetection.minecraft.velocity;

import com.velocitypowered.api.command.SimpleCommand;

import io.vpndetection.minecraft.Commands;

import java.util.List;

final class VelocityCommand implements SimpleCommand {
    private final Commands commands;

    VelocityCommand(Commands commands) {
        this.commands = commands;
    }

    @Override
    public void execute(Invocation invocation) {
        commands.execute(new VelocitySender(invocation.source()), invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        // Velocity passes no arguments at all for "/vpnd ", where core expects the empty one being typed.
        String[] args = invocation.arguments().length == 0 ? new String[] {""} : invocation.arguments();
        return commands.complete(new VelocitySender(invocation.source()), args);
    }
}
