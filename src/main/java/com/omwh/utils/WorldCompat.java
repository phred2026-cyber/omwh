package com.omwh.utils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;

public class WorldCompat {
  public static ServerLevel getLevel(ServerPlayer player) {
      try {
          return (ServerLevel) player.level();
      } catch (Throwable t) {
          return null;
      }
  }

  public static ServerLevel getLevel(Object entity) {
      try {
          if (entity instanceof net.minecraft.world.entity.Entity) {
              return (ServerLevel) ((net.minecraft.world.entity.Entity) entity).level();
          }
      } catch (Throwable ignored) {}
      return null;
  }
}

