package com.omwh.utils;

import java.util.Set;

final class ChunkCoverage {
    record BlockRange(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) { }
    record Range(int minX, int maxX, int minZ, int maxZ) { }

    @FunctionalInterface
    interface Loader {
        void load(int chunkX, int chunkZ);
    }

    private ChunkCoverage() { }

    static BlockRange blockOwners(VehicleClearanceBox.Bounds box) {
        return new BlockRange(
                block(box.minX()) - 1,
                block(box.minY()) - 1,
                block(box.minZ()) - 1,
                block(Math.nextDown(box.maxX())) + 1,
                block(Math.nextDown(box.maxY())) + 1,
                block(Math.nextDown(box.maxZ())) + 1);
    }

    static Range chunkRange(BlockRange blocks) {
        return new Range(chunk(blocks.minX()), chunk(blocks.maxX()),
                chunk(blocks.minZ()), chunk(blocks.maxZ()));
    }

    static void loadNew(Set<Long> loaded, Range range, Loader loader) {
        for (int chunkX = range.minX(); chunkX <= range.maxX(); chunkX++) {
            for (int chunkZ = range.minZ(); chunkZ <= range.maxZ(); chunkZ++) {
                if (loaded.add(key(chunkX, chunkZ))) loader.load(chunkX, chunkZ);
            }
        }
    }

    static long key(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xffffffffL);
    }

    private static int block(double coordinate) {
        return (int) Math.floor(coordinate);
    }

    private static int chunk(int block) {
        return block >> 4;
    }
}
