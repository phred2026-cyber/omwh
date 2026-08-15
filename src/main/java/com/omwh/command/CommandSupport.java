package com.omwh.command;

import com.omwh.config.OmwhConfig;
import com.omwh.utils.CooldownManager;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;

import java.util.List;

public final class CommandSupport {
    final OmwhConfig config;
    private final CooldownManager cooldowns;

    public CommandSupport(OmwhConfig config, CooldownManager cooldowns) {
        this.config = config;
        this.cooldowns = cooldowns;
    }

    boolean admit(ServerPlayer player) {
        CooldownManager.Restriction restriction = cooldowns.restriction(player.getUUID());
        if (restriction.type() == CooldownManager.Type.NONE) return true;
        String message = switch (restriction.type()) {
            case PVP -> config.pvpCooldownMessage;
            case DAMAGE -> config.damageCooldownMessage;
            case JOIN -> config.joinCooldownMessage;
            case REGULAR -> config.regularCooldownMessage;
            case NONE -> throw new IllegalStateException("unreachable cooldown type");
        };
        send(player, message.replace("{time}", Integer.toString(restriction.remainingSeconds())));
        return false;
    }

    void complete(ServerPlayer player, String successMessage, List<ServerPlayer> passengers,
                  String passengerDestination) {
        cooldowns.recordRegular(player.getUUID());
        send(player, successMessage);
        String rider = player.getName().getString();
        for (ServerPlayer passenger : passengers) {
            send(passenger, "§e" + rider + " teleported you with their vehicle to "
                    + passengerDestination + ".");
        }
        playEffects(player);
    }

    void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(message.replace('&', '§')));
    }

    private void playEffects(ServerPlayer player) {
        if (config.playTeleportSound) {
            player.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
        }
        if (!config.spawnTeleportParticles) return;
        ServerLevel level = (ServerLevel) player.level();
        for (int i = 0; i < 40; i++) {
            double angle = i * 2 * Math.PI / 40.0;
            level.sendParticles(ParticleTypes.PORTAL,
                    player.getX() + Math.cos(angle), player.getY() + 0.5,
                    player.getZ() + Math.sin(angle), 1, 0, 0, 0, 0);
        }
    }
}
