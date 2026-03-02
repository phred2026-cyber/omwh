package com.omwh.utils;

import com.omwh.config.ConfigManager;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.sound.SoundEvents;
import net.minecraft.server.world.ServerWorld;

public class EffectsManager {
    public void playTeleportEffects(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();

        if (cfg.playTeleportSound) {
            player.playSound(SoundEvents.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.0f);
        }

        if (cfg.spawnTeleportParticles) {
            ServerWorld serverWorld = (ServerWorld) WorldCompat.getWorld(player);
            if (serverWorld != null) {
                for (int i = 0; i < 40; i++) {
                    double angle = (i * 2 * Math.PI) / 40.0;
                    double x = player.getX() + Math.cos(angle) * 1.0;
                    double z = player.getZ() + Math.sin(angle) * 1.0;
                    double y = player.getY() + 0.5;
                    serverWorld.spawnParticles(ParticleTypes.PORTAL, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
                }
            }
        }
    }
}
