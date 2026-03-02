package com.omwh.listeners;

import com.omwh.utils.CooldownManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;

public class PlayerListener {
    private final CooldownManager cooldownManager;

    public PlayerListener(CooldownManager cooldownManager) {
        this.cooldownManager = cooldownManager;
    }

    public void registerEvents() {
        // preserve cooldown state across respawn/reconnect (no-op because cooldowns keyed by UUID)
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {});

        // Set join cooldown when a player connects to the server
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            try {
                ServerPlayerEntity player = handler.player;
                if (player != null) cooldownManager.setJoinCooldown(player);
            } catch (Throwable ignored) {}
        });
        
        // Register damage event listener to apply different cooldowns:
        // - PvP cooldown (45s) when attacker OR victim is a player involved in PvP
        // - Damage cooldown (10s) when a player is damaged by non-player sources
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, damageSource, originalDamage) -> {
            // If the victim is a player
            if (entity instanceof ServerPlayerEntity victim) {
                // If attacker is a player -> PvP between two players
                if (damageSource.getAttacker() instanceof ServerPlayerEntity attacker) {
                    cooldownManager.setPvpCooldown(victim);
                    cooldownManager.setPvpCooldown(attacker);
                } else {
                    // Non-player damage (mob, environment, etc.) -> apply short damage cooldown
                    cooldownManager.setDamageCooldown(victim);
                }
            }

            return true; // Allow damage to proceed
        });
    }
}
