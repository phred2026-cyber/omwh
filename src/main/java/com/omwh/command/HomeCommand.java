package com.omwh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.omwh.utils.HomeRespawnDecision;
import com.omwh.utils.TeleportVehicles;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;

import java.util.List;

public final class HomeCommand {
    private HomeCommand() { }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandSupport support) {
        dispatcher.register(Commands.literal(support.config.homeCommand)
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> execute(context.getSource().getPlayer(), support)));
    }

    private static int execute(ServerPlayer player, CommandSupport support) {
        if (player == null || !support.admit(player)) return 0;
        var respawnConfig = player.getRespawnConfig();
        if (respawnConfig == null) {
            support.send(player, support.config.noHomepointMessage);
            return 0;
        }

        // Vanilla owns bed, anchor, and forced-respawn placement. false prevents /home from
        // consuming an anchor charge.
        TeleportTransition respawn = player.findRespawnPositionAndUseSpawnBlock(
                false, TeleportTransition.DO_NOTHING);
        ServerLevel current = (ServerLevel) player.level();
        boolean mounted = player.getRootVehicle() != player;
        HomeRespawnDecision.Outcome decision = HomeRespawnDecision.decide(
                respawn.missingRespawnBlock(),
                current.dimension().equals(respawn.newLevel().dimension()));
        if (decision == HomeRespawnDecision.Outcome.NO_HOME) {
            support.send(player, support.config.noHomepointMessage);
            return 0;
        }
        if (decision == HomeRespawnDecision.Outcome.CROSS_DIMENSION) {
            support.send(player, support.config.crossDimensionMessage);
            return 0;
        }

        List<ServerPlayer> passengers;
        if (!mounted) {
            passengers = TeleportVehicles.teleportVanillaHome(player, respawn).passengerPlayers();
        } else {
            var destination = TeleportVehicles.prepareHome(player, respawn.newLevel(),
                    respawn.position(), respawnConfig.respawnData().pos());
            if (destination.isEmpty()) {
                support.send(player, support.config.unsafeHomeMessage);
                return 0;
            }
            passengers = TeleportVehicles.teleport(destination.orElseThrow()).passengerPlayers();
        }

        support.complete(player, support.config.homeSuccessMessage, passengers, "their home");
        return 1;
    }
}
