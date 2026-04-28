package com.omwh.utils;

import com.omwh.config.ConfigManager;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class CooldownManager {
  private final ConcurrentHashMap<UUID, Long> regularCooldowns = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> pvpCooldowns = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> damageCooldowns = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<UUID, Long> joinCooldowns = new ConcurrentHashMap<>();

  public void setRegularCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enableRegularCooldown || cfg.regularCooldownSeconds <= 0) return;
      regularCooldowns.put(player.getUUID(), System.currentTimeMillis());
  }

  public void setPvpCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enablePvpCooldown || cfg.pvpCooldownSeconds <= 0) return;
      UUID playerUUID = player.getUUID();
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

  public void setDamageCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enableDamageCooldown || cfg.damageCooldownSeconds <= 0) return;
      UUID playerUUID = player.getUUID();
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

  public void setJoinCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (cfg.joinCooldownSeconds <= 0) return;
      joinCooldowns.put(player.getUUID(), System.currentTimeMillis());
  }

  public boolean isInRegularCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enableRegularCooldown || cfg.regularCooldownSeconds <= 0) return false;
      UUID id = player.getUUID();
      if (regularCooldowns.containsKey(id)) {
          long cooldownTime = regularCooldowns.get(id);
          long duration = cfg.regularCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          if (now - cooldownTime < duration) return true;
          else regularCooldowns.remove(id);
      }
      return false;
  }

  public boolean isInPvpCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enablePvpCooldown || cfg.pvpCooldownSeconds <= 0) return false;
      UUID id = player.getUUID();
      if (pvpCooldowns.containsKey(id)) {
          long cooldownTime = pvpCooldowns.get(id);
          long duration = cfg.pvpCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          if (now - cooldownTime < duration) return true;
          else pvpCooldowns.remove(id);
      }
      return false;
  }

  public boolean isInDamageCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enableDamageCooldown || cfg.damageCooldownSeconds <= 0) return false;
      UUID id = player.getUUID();
      if (damageCooldowns.containsKey(id)) {
          long cooldownTime = damageCooldowns.get(id);
          long duration = cfg.damageCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          if (now - cooldownTime < duration) return true;
          else damageCooldowns.remove(id);
      }
      return false;
  }

  public boolean isInJoinCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (cfg.joinCooldownSeconds <= 0) return false;
      UUID id = player.getUUID();
      if (joinCooldowns.containsKey(id)) {
          long joinTime = joinCooldowns.get(id);
          long duration = cfg.joinCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          if (now - joinTime < duration) return true;
          else joinCooldowns.remove(id);
      }
      return false;
  }

  public int getRemainingRegularCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enableRegularCooldown || cfg.regularCooldownSeconds <= 0) return 0;
      UUID id = player.getUUID();
      if (regularCooldowns.containsKey(id)) {
          long cooldownTime = regularCooldowns.get(id);
          long duration = cfg.regularCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          long timeLeft = (cooldownTime + duration - now) / 1000;
          return Math.max(0, (int) timeLeft);
      }
      return 0;
  }

  public int getRemainingPvpCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enablePvpCooldown || cfg.pvpCooldownSeconds <= 0) return 0;
      UUID id = player.getUUID();
      if (pvpCooldowns.containsKey(id)) {
          long cooldownTime = pvpCooldowns.get(id);
          long duration = cfg.pvpCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          long timeLeft = (cooldownTime + duration - now) / 1000;
          return Math.max(0, (int) timeLeft);
      }
      return 0;
  }

  public int getRemainingDamageCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (!cfg.enableDamageCooldown || cfg.damageCooldownSeconds <= 0) return 0;
      UUID id = player.getUUID();
      if (damageCooldowns.containsKey(id)) {
          long cooldownTime = damageCooldowns.get(id);
          long duration = cfg.damageCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          long timeLeft = (cooldownTime + duration - now) / 1000;
          return Math.max(0, (int) timeLeft);
      }
      return 0;
  }

  public int getRemainingJoinCooldown(ServerPlayer player) {
      var cfg = ConfigManager.get();
      if (cfg.joinCooldownSeconds <= 0) return 0;
      UUID id = player.getUUID();
      if (joinCooldowns.containsKey(id)) {
          long joinTime = joinCooldowns.get(id);
          long duration = cfg.joinCooldownSeconds * 1000L;
          long now = System.currentTimeMillis();
          long timeLeft = (joinTime + duration - now) / 1000;
          return Math.max(0, (int) timeLeft);
      }
      return 0;
  }

  public boolean shouldBlockTeleport(ServerPlayer player) {
      return isInPvpCooldown(player) || isInDamageCooldown(player) || isInJoinCooldown(player) || isInRegularCooldown(player);
  }

  private boolean hasPermission(ServerPlayer player, String permission) { return false; }
}

