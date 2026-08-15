package com.omwh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.omwh.utils.TeleportVehicles;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public final class SpawnCommand {
    private static final int SEARCH_RADIUS = 64;

    private SpawnCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandSupport support) {
        dispatcher.register(Commands.literal(support.config.spawnCommand)
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> execute(context.getSource().getPlayer(), support)));
    }

    private static int execute(ServerPlayer player, CommandSupport support) {
        if (player == null || !support.admit(player)) return 0;
        ServerLevel level = (ServerLevel) player.level();
        var destination = TeleportVehicles.prepareSpawn(
                player, level, level.getRespawnData().pos(), SEARCH_RADIUS);
        if (destination.isEmpty()) {
            support.send(player, support.config.unsafeSpawnMessage);
            return 0;
        }

        var result = TeleportVehicles.teleport(destination.orElseThrow());
        support.complete(player, support.config.spawnSuccessMessage,
                result.passengerPlayers(), "spawn");
        return 1;
    }
}
