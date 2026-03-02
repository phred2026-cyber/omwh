package com.omwh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.omwh.OMWH;
import com.omwh.config.ConfigManager;
import com.omwh.utils.SafeTeleportUtils;
import com.omwh.utils.TeleportVehicles;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class HomeCommand {
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        String alias = ConfigManager.get().homeCommand;

        var base = CommandManager.literal(alias)
                .requires(source -> source.getPlayer() != null)
                .executes(ctx -> executeWrapped(ctx));

        dispatcher.register(base);
    }

    private static int executeWrapped(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) return 0;
        try {
            return executeHomeCommand(player) ? 1 : 0;
        } catch (Throwable t) {
            OMWH.MESSAGE_UTILS.sendMessage(player, "§cInternal error executing /home. Check server log.");
            org.slf4j.LoggerFactory.getLogger("omwh").error("Error executing /home", t);
            return 0;
        }
    }

    private static boolean executeHomeCommand(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();

        if (OMWH.COOLDOWN_MANAGER.shouldBlockTeleport(player)) {
            if (OMWH.COOLDOWN_MANAGER.isInPvpCooldown(player)) {
                OMWH.MESSAGE_UTILS.sendMessage(player, cfg.pvpCooldownMessage.replace("{time}", String.valueOf(OMWH.COOLDOWN_MANAGER.getRemainingPvpCooldown(player))));
            } else if (OMWH.COOLDOWN_MANAGER.isInDamageCooldown(player)) {
                OMWH.MESSAGE_UTILS.sendMessage(player, cfg.damageCooldownMessage.replace("{time}", String.valueOf(OMWH.COOLDOWN_MANAGER.getRemainingDamageCooldown(player))));
            } else if (OMWH.COOLDOWN_MANAGER.isInJoinCooldown(player)) {
                OMWH.MESSAGE_UTILS.sendMessage(player, cfg.joinCooldownMessage.replace("{time}", String.valueOf(OMWH.COOLDOWN_MANAGER.getRemainingJoinCooldown(player))));
            } else if (OMWH.COOLDOWN_MANAGER.isInRegularCooldown(player)) {
                OMWH.MESSAGE_UTILS.sendMessage(player, cfg.regularCooldownMessage.replace("{time}", String.valueOf(OMWH.COOLDOWN_MANAGER.getRemainingRegularCooldown(player))));
            }
            return false;
        }

        var respawnTarget = player.getRespawnTarget(true, null);
        if (respawnTarget == null) {
            OMWH.MESSAGE_UTILS.sendMessage(player, cfg.noHomepointMessage);
            return false;
        }

        var spawnWorld = respawnTarget.world();
        BlockPos spawnPos = BlockPos.ofFloored(respawnTarget.position());

        ServerWorld playerWorld = null;
        try {
            playerWorld = (ServerWorld) player.getEntityWorld();
        } catch (Throwable ignored) { }
        if (playerWorld == null) {
            playerWorld = (ServerWorld) com.omwh.utils.WorldCompat.getWorld(player);
            if (playerWorld == null) {
                OMWH.MESSAGE_UTILS.sendMessage(player, cfg.crossDimensionMessage);
                return false;
            }
        }

        if (!playerWorld.getRegistryKey().equals(spawnWorld.getRegistryKey())) {
            OMWH.MESSAGE_UTILS.sendMessage(player, cfg.crossDimensionMessage);
            return false;
        }

        OMWH.EFFECTS_MANAGER.playTeleportEffects(player);

        BlockPos bedTop = spawnPos.up();
        int widthBlocks = 1;
        int heightBlocks = 2;
        var root = player.getRootVehicle();
        boolean isRiding = root != player;
        if (isRiding) {
             widthBlocks = (int) Math.max(1, Math.ceil(root.getWidth()));
             heightBlocks = (int) Math.max(2, Math.ceil(root.getHeight()));
        }

        // Search for safe spot within 5 blocks. Height + 1 for headroom (spawn 1 block up).
        BlockPos safePos = SafeTeleportUtils.findSafeLocationForSize(spawnWorld, bedTop, 5, widthBlocks, heightBlocks + 1, false);

        if (safePos == null) {
              if (isRiding) {
                   // Check if player alone could fit
                   BlockPos fitPlayer = SafeTeleportUtils.findSafeLocationForSize(spawnWorld, bedTop, 5, 1, 3, false);
                   if (fitPlayer != null) {
                        OMWH.MESSAGE_UTILS.sendMessage(player, "§cYour vehicle is too big. Please dismount and try again.");
                        return false;
                   }
              }
              OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeHomeMessage);
              return false;
        }

        TeleportVehicles.Result result = TeleportVehicles.teleportWithMount(player, spawnWorld, safePos);
        boolean teleportSuccessful = result.success;
        java.util.List<ServerPlayerEntity> passengerPlayers = result.passengerPlayers;

        if (!teleportSuccessful) {
            OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeHomeMessage);
            return false;
        }

        OMWH.COOLDOWN_MANAGER.setRegularCooldown(player);
        OMWH.MESSAGE_UTILS.sendMessage(player, cfg.homeSuccessMessage);

        if (!passengerPlayers.isEmpty()) {
            for (ServerPlayerEntity p : passengerPlayers) {
                OMWH.MESSAGE_UTILS.sendMessage(p, "§e" + player.getName().getString() + " teleported you with their vehicle to their home.");
            }
        }

        return true;
    }
}
