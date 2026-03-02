package com.omwh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.omwh.OMWH;
import com.omwh.config.ConfigManager;
import com.omwh.utils.SafeTeleportUtils;
import com.omwh.utils.TeleportVehicles;
import com.omwh.utils.SpawnLocator;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnCommand {
    private static final Logger logger = LoggerFactory.getLogger("omwh");

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        String alias = ConfigManager.get().spawnCommand;

        var base = CommandManager.literal(alias)
                .requires(source -> source.getPlayer() != null)
                .executes(ctx -> executeWrapped(ctx));

        dispatcher.register(base);
    }

    private static int executeWrapped(CommandContext<ServerCommandSource> context) {
        ServerPlayerEntity player = context.getSource().getPlayer();
        if (player == null) return 0;
        ServerWorld currentWorld = null;
        try { currentWorld = context.getSource().getWorld(); } catch (Throwable ignored) {}
        try {
            return executeSpawnCommand(player, currentWorld) ? 1 : 0;
        } catch (Throwable t) {
            OMWH.MESSAGE_UTILS.sendMessage(player, "§cInternal error executing /spawn. Check server log.");
            LoggerFactory.getLogger("omwh").error("Error executing /spawn", t);
            return 0;
        }
    }

    private static boolean executeSpawnCommand(ServerPlayerEntity player, ServerWorld currentWorld) {
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

        String invokerName = "unknown";
        try { invokerName = player.getName().getString(); } catch (Throwable ignored) {}
        String worldKey = null;
        try { if (currentWorld != null) worldKey = currentWorld.getRegistryKey().getValue().toString(); } catch (Throwable ignored) {}
        logger.info("[SpawnCommand] /spawn invoked by {} in {} (coords omitted)", invokerName, worldKey);

        ServerWorld targetWorld = (currentWorld != null) ? currentWorld : (ServerWorld) com.omwh.utils.WorldCompat.getWorld(player);
        if (targetWorld == null) {
            OMWH.MESSAGE_UTILS.sendMessage(player, "§cCannot determine your current world.");
            return false;
        }

        net.minecraft.util.math.BlockPos spawnCenter = SpawnLocator.getSpawnCenter(targetWorld);
        if (spawnCenter == null) {
            OMWH.MESSAGE_UTILS.sendMessage(player, "§cCannot determine world spawn.");
            return false;
        }

        int width = 1;
        int height = 2;
        net.minecraft.entity.Entity root = player.getRootVehicle();
        if (root != player) {
             width = (int) Math.ceil(root.getWidth());
             height = (int) Math.ceil(root.getHeight());
        }

        // Random search in 4 chunks (64 blocks) radius. Favors Grass/Sky. Requires height + 1 for headroom (spawn 1 block up).
        net.minecraft.util.math.BlockPos candidateFeet = SafeTeleportUtils.findRandomSafeLocation(targetWorld, spawnCenter, 64, width, height + 1, true);

        if (candidateFeet == null) {
             // Fallback
             candidateFeet = SafeTeleportUtils.findSafeLocationForSize(targetWorld, spawnCenter, 64, width, height + 1, true);
        }

        if (candidateFeet == null) {
             OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeSpawnMessage);
             return false;
        }

        OMWH.EFFECTS_MANAGER.playTeleportEffects(player);

        // force-load chunks
        {
            int centerChunkX = candidateFeet.getX() >> 4;
            int centerChunkZ = candidateFeet.getZ() >> 4;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) targetWorld.getChunk(centerChunkX + dx, centerChunkZ + dz);
        }

        TeleportVehicles.Result result = TeleportVehicles.teleportWithMount(player, targetWorld, candidateFeet);
        boolean teleportSuccessful = result.success;
        java.util.List<ServerPlayerEntity> passengerPlayers = result.passengerPlayers;

        if (!teleportSuccessful) {
            OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeSpawnMessage);
            return false;
        }

        OMWH.COOLDOWN_MANAGER.setRegularCooldown(player);
        OMWH.MESSAGE_UTILS.sendMessage(player, cfg.spawnSuccessMessage);
        if (!passengerPlayers.isEmpty()) {
            for (ServerPlayerEntity p : passengerPlayers) {
                OMWH.MESSAGE_UTILS.sendMessage(p, "§e" + player.getName().getString() + " teleported you with their vehicle to spawn.");
            }
        }

        return true;
    }
}
