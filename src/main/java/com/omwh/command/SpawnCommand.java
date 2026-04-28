package com.omwh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.omwh.OMWH;
import com.omwh.config.ConfigManager;
import com.omwh.utils.SafeTeleportUtils;
import com.omwh.utils.TeleportVehicles;
import com.omwh.utils.SpawnLocator;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpawnCommand {
   private static final Logger logger = LoggerFactory.getLogger("omwh");

   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
       String alias = ConfigManager.get().spawnCommand;

       var base = Commands.literal(alias)
               .requires(source -> source.getPlayer() != null)
               .executes(ctx -> executeWrapped(ctx));

       dispatcher.register(base);
   }

   private static int executeWrapped(CommandContext<CommandSourceStack> context) {
       ServerPlayer player = context.getSource().getPlayer();
       if (player == null) return 0;
       ServerLevel currentLevel = null;
       try { currentLevel = context.getSource().getLevel(); } catch (Throwable ignored) {}
       try {
           return executeSpawnCommand(player, currentLevel) ? 1 : 0;
       } catch (Throwable t) {
           OMWH.MESSAGE_UTILS.sendMessage(player, "§cInternal error executing /spawn. Check server log.");
           LoggerFactory.getLogger("omwh").error("Error executing /spawn", t);
           return 0;
       }
   }

   private static boolean executeSpawnCommand(ServerPlayer player, ServerLevel currentLevel) {
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
        try { if (currentLevel != null) worldKey = currentLevel.dimension().identifier().toString(); } catch (Throwable ignored) {}
logger.info("[SpawnCommand] /spawn invoked by {} in {} (coords omitted)", invokerName, worldKey);

       ServerLevel targetLevel = (currentLevel != null) ? currentLevel : (ServerLevel) com.omwh.utils.WorldCompat.getLevel(player);
       if (targetLevel == null) {
           OMWH.MESSAGE_UTILS.sendMessage(player, "§cCannot determine your current world.");
           return false;
       }

       net.minecraft.core.BlockPos spawnCenter = SpawnLocator.getSpawnCenter(targetLevel);
       if (spawnCenter == null) {
           OMWH.MESSAGE_UTILS.sendMessage(player, "§cCannot determine world spawn.");
           return false;
       }

       int width = 1;
       int height = 2;
       net.minecraft.world.entity.Entity root = player.getRootVehicle();
       if (root != player) {
            width = (int) Math.ceil(root.getBbWidth());
             height = (int) Math.max(2, Math.ceil(root.getBbHeight()));
}

       // Random search in 4 chunks (64 blocks) radius. Favors Grass/Sky. Requires height + 1 for headroom (spawn 1 block up).
       net.minecraft.core.BlockPos candidateFeet = SafeTeleportUtils.findRandomSafeLocation(targetLevel, spawnCenter, 64, width, height + 1, true);

       if (candidateFeet == null) {
            // Fallback
            candidateFeet = SafeTeleportUtils.findSafeLocationForSize(targetLevel, spawnCenter, 64, width, height + 1, true);
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
           for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) targetLevel.getChunk(centerChunkX + dx, centerChunkZ + dz);
       }

       TeleportVehicles.Result result = TeleportVehicles.teleportWithMount(player, targetLevel, candidateFeet);
       boolean teleportSuccessful = result.success;
       java.util.List<ServerPlayer> passengerPlayers = result.passengerPlayers;

       if (!teleportSuccessful) {
           OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeSpawnMessage);
           return false;
       }

       OMWH.COOLDOWN_MANAGER.setRegularCooldown(player);
       OMWH.MESSAGE_UTILS.sendMessage(player, cfg.spawnSuccessMessage);
       if (!passengerPlayers.isEmpty()) {
           for (ServerPlayer p : passengerPlayers) {
               OMWH.MESSAGE_UTILS.sendMessage(p, "§e" + player.getName().getString() + " teleported you with their vehicle to spawn.");
           }
       }

       return true;
   }
}

