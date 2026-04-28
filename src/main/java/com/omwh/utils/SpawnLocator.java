package com.omwh.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

public class SpawnLocator {
   public static class Resolved { public final ServerLevel world; public final BlockPos feetPos; public Resolved(ServerLevel w, BlockPos p){this.world=w;this.feetPos=p;} }

   public static Resolved resolveSpawn(ServerLevel world) {
       BlockPos c = getSpawnCenter(world);
       if (c == null) return null;
       BlockPos safe = SafeTeleportUtils.findSafeLocation(world, c, 16, true);
       if (safe == null) return null;
       return new Resolved(world, safe);
   }

   public static Resolved resolveSpawn(net.minecraft.server.level.ServerPlayer player) {
       ServerLevel w = (ServerLevel) WorldCompat.getLevel(player);
       if (w == null) return null;
       return resolveSpawn(w);
   }

   public static BlockPos getSpawnCenter(ServerLevel world) {
       try {
           var respawn = world.getRespawnData();
          if (respawn != null && respawn.pos() != null) return respawn.pos();
          int y = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, 0, 0);
          return new BlockPos(0, y, 0);
       } catch (Throwable t) {
           try {
               return new BlockPos(0, 64, 0);
           } catch (Throwable ignored) { return null; }
       }
   }
}

