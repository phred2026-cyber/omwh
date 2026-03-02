package com.omwh.utils;

import com.omwh.config.ConfigManager;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {
    private final ConcurrentHashMap<UUID, Long> regularCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> pvpCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> damageCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> joinCooldowns = new ConcurrentHashMap<>();

    public void setRegularCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enableRegularCooldown || cfg.regularCooldownSeconds <= 0) return;
        regularCooldowns.put(player.getUuid(), System.currentTimeMillis());
    }

    public void setPvpCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enablePvpCooldown || cfg.pvpCooldownSeconds <= 0) return;
        UUID playerUUID = player.getUuid();
        long now = System.currentTimeMillis();
        long newExpiry = now + (cfg.pvpCooldownSeconds * 1000L);

        long currentMaxExpiry = 0;
        if (isInPvpCooldown(player)) currentMaxExpiry = Math.max(currentMaxExpiry, pvpCooldowns.get(playerUUID) + (cfg.pvpCooldownSeconds * 1000L));
        if (isInDamageCooldown(player)) currentMaxExpiry = Math.max(currentMaxExpiry, damageCooldowns.get(playerUUID) + (cfg.damageCooldownSeconds * 1000L));
        if (isInJoinCooldown(player)) currentMaxExpiry = Math.max(currentMaxExpiry, joinCooldowns.get(playerUUID) + (cfg.joinCooldownSeconds * 1000L));

        if (newExpiry > currentMaxExpiry) {
            pvpCooldowns.put(playerUUID, now);
            damageCooldowns.remove(playerUUID);
            joinCooldowns.remove(playerUUID);
        }
    }

    public void setDamageCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enableDamageCooldown || cfg.damageCooldownSeconds <= 0) return;
        UUID playerUUID = player.getUuid();
        long now = System.currentTimeMillis();
        long newExpiry = now + (cfg.damageCooldownSeconds * 1000L);

        long currentMaxExpiry = 0;
        if (isInPvpCooldown(player)) currentMaxExpiry = Math.max(currentMaxExpiry, pvpCooldowns.get(playerUUID) + (cfg.pvpCooldownSeconds * 1000L));
        if (isInDamageCooldown(player)) currentMaxExpiry = Math.max(currentMaxExpiry, damageCooldowns.get(playerUUID) + (cfg.damageCooldownSeconds * 1000L));
        if (isInJoinCooldown(player)) currentMaxExpiry = Math.max(currentMaxExpiry, joinCooldowns.get(playerUUID) + (cfg.joinCooldownSeconds * 1000L));

        if (newExpiry > currentMaxExpiry) {
            damageCooldowns.put(playerUUID, now);
            pvpCooldowns.remove(playerUUID);
            joinCooldowns.remove(playerUUID);
        }
    }

    public void setJoinCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (cfg.joinCooldownSeconds <= 0) return;
        joinCooldowns.put(player.getUuid(), System.currentTimeMillis());
    }

    public boolean isInRegularCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enableRegularCooldown || cfg.regularCooldownSeconds <= 0) return false;
        UUID id = player.getUuid();
        if (regularCooldowns.containsKey(id)) {
            long cooldownTime = regularCooldowns.get(id);
            long duration = cfg.regularCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            if (now - cooldownTime < duration) return true;
            else regularCooldowns.remove(id);
        }
        return false;
    }

    public boolean isInPvpCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enablePvpCooldown || cfg.pvpCooldownSeconds <= 0) return false;
        UUID id = player.getUuid();
        if (pvpCooldowns.containsKey(id)) {
            long cooldownTime = pvpCooldowns.get(id);
            long duration = cfg.pvpCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            if (now - cooldownTime < duration) return true;
            else pvpCooldowns.remove(id);
        }
        return false;
    }

    public boolean isInDamageCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enableDamageCooldown || cfg.damageCooldownSeconds <= 0) return false;
        UUID id = player.getUuid();
        if (damageCooldowns.containsKey(id)) {
            long cooldownTime = damageCooldowns.get(id);
            long duration = cfg.damageCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            if (now - cooldownTime < duration) return true;
            else damageCooldowns.remove(id);
        }
        return false;
    }

    public boolean isInJoinCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (cfg.joinCooldownSeconds <= 0) return false;
        UUID id = player.getUuid();
        if (joinCooldowns.containsKey(id)) {
            long joinTime = joinCooldowns.get(id);
            long duration = cfg.joinCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            if (now - joinTime < duration) return true;
            else joinCooldowns.remove(id);
        }
        return false;
    }

    public int getRemainingRegularCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enableRegularCooldown || cfg.regularCooldownSeconds <= 0) return 0;
        UUID id = player.getUuid();
        if (regularCooldowns.containsKey(id)) {
            long cooldownTime = regularCooldowns.get(id);
            long duration = cfg.regularCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            long timeLeft = (cooldownTime + duration - now) / 1000;
            return Math.max(0, (int) timeLeft);
        }
        return 0;
    }

    public int getRemainingPvpCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enablePvpCooldown || cfg.pvpCooldownSeconds <= 0) return 0;
        UUID id = player.getUuid();
        if (pvpCooldowns.containsKey(id)) {
            long cooldownTime = pvpCooldowns.get(id);
            long duration = cfg.pvpCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            long timeLeft = (cooldownTime + duration - now) / 1000;
            return Math.max(0, (int) timeLeft);
        }
        return 0;
    }

    public int getRemainingDamageCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (!cfg.enableDamageCooldown || cfg.damageCooldownSeconds <= 0) return 0;
        UUID id = player.getUuid();
        if (damageCooldowns.containsKey(id)) {
            long cooldownTime = damageCooldowns.get(id);
            long duration = cfg.damageCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            long timeLeft = (cooldownTime + duration - now) / 1000;
            return Math.max(0, (int) timeLeft);
        }
        return 0;
    }

    public int getRemainingJoinCooldown(ServerPlayerEntity player) {
        var cfg = ConfigManager.get();
        if (cfg.joinCooldownSeconds <= 0) return 0;
        UUID id = player.getUuid();
        if (joinCooldowns.containsKey(id)) {
            long joinTime = joinCooldowns.get(id);
            long duration = cfg.joinCooldownSeconds * 1000L;
            long now = System.currentTimeMillis();
            long timeLeft = (joinTime + duration - now) / 1000;
            return Math.max(0, (int) timeLeft);
        }
        return 0;
    }

    public boolean shouldBlockTeleport(ServerPlayerEntity player) {
        return isInPvpCooldown(player) || isInDamageCooldown(player) || isInJoinCooldown(player) || isInRegularCooldown(player);
    }

    private boolean hasPermission(ServerPlayerEntity player, String permission) { return false; }
}
