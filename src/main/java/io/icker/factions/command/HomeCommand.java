package io.icker.factions.command;


import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.icker.factions.FactionsMod;
import io.icker.factions.util.Command;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;

public class HomeCommand implements Command {
    private int go(CommandContext<ServerCommandSource> context) {
        return 0;
    }

    private int set(CommandContext<ServerCommandSource> context) {
        return 0;
    }

    @Override
    public LiteralCommandNode<ServerCommandSource> getNode() {
        return CommandManager
            .literal("home")
            .requires(Requires.multiple(Requires.isMember(), s -> FactionsMod.CONFIG.HOME != null, a -> false))
            .executes(this::go)
            .then(
                CommandManager.literal("set")
                .requires(Requires.multiple(Requires.hasPerms("factions.home.set", 0), Requires.isLeader(), a -> false))
                .executes(this::set)
            )
            .build();
    }
}
