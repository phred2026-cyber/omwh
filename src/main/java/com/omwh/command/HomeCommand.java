package com.omwh.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.omwh.OMWH;
import com.omwh.config.ConfigManager;
import com.omwh.utils.SafeTeleportUtils;
import com.omwh.utils.TeleportVehicles;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

public class HomeCommand {
   public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
       String alias = ConfigManager.get().homeCommand;

       var base = Commands.literal(alias)
               .requires(source -> source.getPlayer() != null)
               .executes(ctx -> executeWrapped(ctx));

       dispatcher.register(base);
   }

   private static int executeWrapped(CommandContext<CommandSourceStack> context) {
       ServerPlayer player = context.getSource().getPlayer();
       if (player == null) return 0;
       try {
           return executeHomeCommand(player) ? 1 : 0;
       } catch (Throwable t) {
           OMWH.MESSAGE_UTILS.sendMessage(player, "§cInternal error executing /home. Check server log.");
           org.slf4j.LoggerFactory.getLogger("omwh").error("Error executing /home", t);
           return 0;
       }
   }

   private static boolean executeHomeCommand(ServerPlayer player) {
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

        var respawnConfig = player.getRespawnConfig();
        var respawnData = respawnConfig != null ? respawnConfig.respawnData() : null;
        if (respawnData == null || respawnData.pos() == null) {
            OMWH.MESSAGE_UTILS.sendMessage(player, cfg.noHomepointMessage);
            return false;
        }

        var spawnLevel = player.level().getServer().getLevel(respawnData.dimension());
        if (spawnLevel == null) {
            OMWH.MESSAGE_UTILS.sendMessage(player, cfg.noHomepointMessage);
            return false;
        }
        BlockPos spawnPos = respawnData.pos();

       ServerLevel playerLevel = null;
       try {
           playerLevel = (ServerLevel) player.level();
       } catch (Throwable ignored) { }
       if (playerLevel == null) {
           playerLevel = (ServerLevel) com.omwh.utils.WorldCompat.getLevel(player);
           if (playerLevel == null) {
               OMWH.MESSAGE_UTILS.sendMessage(player, cfg.crossDimensionMessage);
               return false;
           }
       }

       if (!playerLevel.dimension().equals(spawnLevel.dimension())) {
           OMWH.MESSAGE_UTILS.sendMessage(player, cfg.crossDimensionMessage);
           return false;
       }

       OMWH.EFFECTS_MANAGER.playTeleportEffects(player);

       BlockPos bedTop = spawnPos.above();
       int widthBlocks = 1;
       int heightBlocks = 2;
       var root = player.getRootVehicle();
       boolean isRiding = root != player;
       if (isRiding) {
            widthBlocks = (int) Math.max(1, Math.ceil(root.getBbWidth()));
            heightBlocks = (int) Math.max(2, Math.ceil(root.getBbHeight()));
       }

       // Search for safe spot within 5 blocks. Height + 1 for headroom (spawn 1 block up).
       BlockPos safePos = SafeTeleportUtils.findSafeLocationForSize(spawnLevel, bedTop, 5, widthBlocks, heightBlocks + 1, false);

       if (safePos == null) {
             if (isRiding) {
                  // Check if player alone could fit
                  BlockPos fitPlayer = SafeTeleportUtils.findSafeLocationForSize(spawnLevel, bedTop, 5, 1, 3, false);
                  if (fitPlayer != null) {
                       OMWH.MESSAGE_UTILS.sendMessage(player, "§cYour vehicle is too big. Please dismount and try again.");
                       return false;
                  }
             }
             OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeHomeMessage);
             return false;
       }

       TeleportVehicles.Result result = TeleportVehicles.teleportWithMount(player, spawnLevel, safePos);
       boolean teleportSuccessful = result.success;
       java.util.List<ServerPlayer> passengerPlayers = result.passengerPlayers;

       if (!teleportSuccessful) {
           OMWH.MESSAGE_UTILS.sendMessage(player, cfg.unsafeHomeMessage);
           return false;
       }

       OMWH.COOLDOWN_MANAGER.setRegularCooldown(player);
       OMWH.MESSAGE_UTILS.sendMessage(player, cfg.homeSuccessMessage);

       if (!passengerPlayers.isEmpty()) {
           for (ServerPlayer p : passengerPlayers) {
               OMWH.MESSAGE_UTILS.sendMessage(p, "§e" + player.getName().getString() + " teleported you with their vehicle to their home.");
           }
       }

       return true;
   }
}

