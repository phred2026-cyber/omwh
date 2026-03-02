package com.omwh.utils;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

public class WorldCompat {
    public static ServerWorld getWorld(ServerPlayerEntity player) {
        try {
            return (ServerWorld) player.getEntityWorld();
        } catch (Throwable t) {
            return null;
        }
    }

    public static ServerWorld getWorld(Object entity) {
        try {
            if (entity instanceof net.minecraft.entity.Entity) {
                return (ServerWorld) ((net.minecraft.entity.Entity) entity).getEntityWorld();
            }
        } catch (Throwable ignored) {}
        return null;
    }
}
