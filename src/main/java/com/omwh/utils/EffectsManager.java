package com.omwh.utils;

import com.omwh.config.ConfigManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.server.level.ServerLevel;

public class EffectsManager {
   public void playTeleportEffects(ServerPlayer player) {
       var cfg = ConfigManager.get();

       if (cfg.playTeleportSound) {
           player.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
       }

       if (cfg.spawnTeleportParticles) {
           ServerLevel serverLevel = (ServerLevel) WorldCompat.getLevel(player);
           if (serverLevel != null) {
               for (int i = 0; i < 40; i++) {
                   double angle = (i * 2 * Math.PI) / 40.0;
                   double x = player.getX() + Math.cos(angle) * 1.0;
                   double z = player.getZ() + Math.sin(angle) * 1.0;
                   double y = player.getY() + 0.5;
                   serverLevel.sendParticles(ParticleTypes.PORTAL, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
               }
           }
       }
   }
}

