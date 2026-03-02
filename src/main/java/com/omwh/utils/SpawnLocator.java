package com.omwh.utils;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public class SpawnLocator {
    public static class Resolved { public final ServerWorld world; public final BlockPos feetPos; public Resolved(ServerWorld w, BlockPos p){this.world=w;this.feetPos=p;} }

    public static Resolved resolveSpawn(ServerWorld world) {
        BlockPos c = getSpawnCenter(world);
        if (c == null) return null;
        BlockPos safe = SafeTeleportUtils.findSafeLocation(world, c, 16, true);
        if (safe == null) return null;
        return new Resolved(world, safe);
    }

    public static Resolved resolveSpawn(net.minecraft.server.network.ServerPlayerEntity player) {
        ServerWorld w = (ServerWorld) WorldCompat.getWorld(player);
        if (w == null) return null;
        return resolveSpawn(w);
    }

    public static BlockPos getSpawnCenter(ServerWorld world) {
        try {
            return world.getSpawnPoint().getPos();
        } catch (Throwable t) {
            try {
                return new BlockPos(0, 64, 0);
            } catch (Throwable ignored) { return null; }
        }
    }
}
