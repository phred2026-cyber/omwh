package com.omwh.utils;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;

import java.util.*;

public class SafeTeleportUtils {
  public static BlockPos findSafeLocation(ServerLevel world, BlockPos targetPos, int maxRadius, boolean isSpawn) {
      if (isSafeLocation(world, targetPos)) return targetPos;
      int searchRadius = isSpawn ? 128 : maxRadius;
      for (int radius = 1; radius <= searchRadius; radius++) {
          for (int x = -radius; x <= radius; x++) {
              for (int z = -radius; z <= radius; z++) {
                  if (Math.abs(x) == radius || Math.abs(z) == radius) {
                      BlockPos checkPos = targetPos.offset(x, 0, z);
                      for (int yOffset = -2; yOffset <= 10; yOffset++) {
                          BlockPos testPos = checkPos.offset(0, yOffset, 0);
                          if (testPos.getY() >= world.getMinY() && testPos.getY() < world.getMaxY() && isSafeLocation(world, testPos)) return testPos;
                      }
                  }
              }
          }
      }
      return null;
  }

  public static BlockPos findSafeLocationForSize(ServerLevel world, BlockPos targetPos, int maxRadius, int widthBlocks, int heightBlocks, boolean isSpawn) {
      // local helper
      java.util.function.Predicate<BlockPos> isClearAt = (pos) -> {
          if (pos.getY() < world.getMinY() || pos.getY() + heightBlocks - 1 >= world.getMaxY()) return false;
          var floor = world.getBlockState(pos.below());
          if (!floor.getFluidState().isEmpty()) return false;
          String floorId = floor.getBlock().getDescriptionId();
          if (floor.isAir() || floorId.contains("water") || floorId.contains("lava") || isDangerousBlock(floorId)) return false;
          int halfW = (widthBlocks / 2);
          int extW = (widthBlocks % 2 == 0) ? widthBlocks : widthBlocks;
          for (int dx = -halfW; dx <= (halfW + (extW - 1 - halfW)); dx++) {
              for (int dz = -halfW; dz <= (halfW + (extW - 1 - halfW)); dz++) {
                  for (int dy = 0; dy < heightBlocks; dy++) {
                      BlockPos p = pos.offset(dx, dy, dz);
                      var s = world.getBlockState(p);
                      if (!s.isAir() && !s.getCollisionShape(world, p).isEmpty()) return false;
                      if (!s.getFluidState().isEmpty()) return false;
                      if (isDangerousBlock(s.getBlock().getDescriptionId())) return false;
                  }
              }
          }
          for (int dx = -1; dx <= widthBlocks; dx++) for (int dz = -1; dz <= widthBlocks; dz++) for (int dy = -1; dy <= heightBlocks; dy++) {
              BlockPos p = pos.offset(dx - 1, dy, dz - 1);
              var b = world.getBlockState(p).getBlock();
              if (isDangerousBlock(b.getDescriptionId())) return false;
          }
          return true;
      };

      if (isClearAt.test(targetPos)) return targetPos;
      int searchRadius = isSpawn ? 128 : maxRadius;
      for (int radius = 1; radius <= searchRadius; radius++) {
          for (int x = -radius; x <= radius; x++) {
              for (int z = -radius; z <= radius; z++) {
                  if (Math.abs(x) == radius || Math.abs(z) == radius) {
                      BlockPos checkPos = targetPos.offset(x, 0, z);
                      for (int yOffset = -2; yOffset <= 10; yOffset++) {
                          BlockPos testPos = checkPos.offset(0, yOffset, 0);
                          if (testPos.getY() >= world.getMinY() && testPos.getY() < world.getMaxY() && isClearAt.test(testPos)) return testPos;
                      }
                  }
              }
          }
      }
      return null;
  }

  private static boolean isSafeLocation(ServerLevel world, BlockPos pos) {
      if (pos.getY() < world.getMinY() || pos.getY() >= world.getMaxY() - 1) return false;
      var blockAtFeet = world.getBlockState(pos);
      var blockAtHead = world.getBlockState(pos.above());
      var blockBelow = world.getBlockState(pos.below());
      if (!blockBelow.getFluidState().isEmpty()) return false;
      if (!blockAtFeet.getFluidState().isEmpty()) return false;
      if (!blockAtHead.getFluidState().isEmpty()) return false;
      String belowId = blockBelow.getBlock().getDescriptionId();
      if (blockBelow.isAir() || belowId.contains("water") || belowId.contains("lava") || isDangerousBlock(belowId)) return false;
      if (!blockAtFeet.isAir() && !blockAtFeet.getCollisionShape(world, pos).isEmpty()) return false;
      if (!blockAtHead.isAir() && !blockAtHead.getCollisionShape(world, pos.above()).isEmpty()) return false;
      for (int x = -1; x <= 1; x++) for (int y = -1; y <= 2; y++) for (int z = -1; z <= 1; z++) {
          var nearbyPos = pos.offset(x, y, z);
          var nearbyBlock = world.getBlockState(nearbyPos);
          if (isDangerousBlock(nearbyBlock.getBlock().getDescriptionId())) return false;
      }
      return true;
  }

  private static boolean isDangerousBlock(String blockId) {
      if (blockId == null) return false;
      blockId = blockId.toLowerCase(Locale.ROOT);
      return blockId.contains("lava") || blockId.contains("fire") || blockId.contains("magma") || blockId.contains("cactus") || blockId.contains("sweet_berry_bush") || blockId.contains("wither_rose") || blockId.contains("powder_snow");
  }

  private static boolean isPassableForWalking(ServerLevel world, BlockPos feet) {
      if (feet.getY() < world.getMinY() || feet.getY() >= world.getMaxY() - 1) return false;
      var feetState = world.getBlockState(feet);
      var headState = world.getBlockState(feet.above());
      var floorState = world.getBlockState(feet.below());
      if (!floorState.getFluidState().isEmpty()) return false;
      if (!feetState.getFluidState().isEmpty()) return false;
      if (!headState.getFluidState().isEmpty()) return false;
      boolean feetClear = feetState.isAir() || feetState.getCollisionShape(world, feet).isEmpty();
      boolean headClear = headState.isAir() || headState.getCollisionShape(world, feet.above()).isEmpty();
      if (!feetClear || !headClear) return false;
      var floorBlock = floorState.getBlock();
      String id = floorBlock.getDescriptionId();
      if (floorState.isAir() || id.contains("water") || id.contains("lava") || isDangerousBlock(id)) return false;
      return true;
  }

  public static boolean hasNavigablePath(ServerLevel world, BlockPos start, BlockPos goal, int maxSteps) {
      if (start.equals(goal)) return true;
      HashSet<BlockPos> visited = new HashSet<>();
      ArrayDeque<java.util.Map.Entry<BlockPos, Integer>> queue = new ArrayDeque<>();
      queue.add(Map.entry(start, 0));
      visited.add(start);
      while (!queue.isEmpty()) {
          var e = queue.removeFirst();
          BlockPos pos = e.getKey();
          int steps = e.getValue();
          if (steps >= maxSteps) continue;
          int[][] dirs = new int[][]{{1,0},{-1,0},{0,1},{0,-1}};
          for (int[] d : dirs) {
              for (int dy = -1; dy <= 1; dy++) {
                  BlockPos np = pos.offset(d[0], dy, d[1]);
                  if (visited.contains(np)) continue;
                  if (np.getY() < world.getMinY() || np.getY() >= world.getMaxY()) continue;
                  if (!isPassableForWalking(world, np)) continue;
                  if (np.equals(goal)) return true;
                  visited.add(np);
                  queue.add(Map.entry(np, steps + 1));
                  if (visited.size() > maxSteps) return false;
              }
          }
      }
      return false;
  }

  public static BlockPos findSafeLocationWithPath(ServerLevel world, BlockPos targetPos, int maxRadius, boolean isSpawn, BlockPos pathTarget) {
      if (isSafeLocation(world, targetPos) && hasNavigablePath(world, targetPos, pathTarget, 2048)) return targetPos;
      int searchRadius = isSpawn ? 128 : maxRadius;
      for (int radius = 1; radius <= searchRadius; radius++) {
          for (int x = -radius; x <= radius; x++) {
              for (int z = -radius; z <= radius; z++) {
                  if (Math.abs(x) == radius || Math.abs(z) == radius) {
                      BlockPos checkPos = targetPos.offset(x, 0, z);
                      for (int yOffset = -2; yOffset <= 10; yOffset++) {
                          BlockPos testPos = checkPos.offset(0, yOffset, 0);
                          if (testPos.getY() >= world.getMinY() && testPos.getY() < world.getMaxY() && isSafeLocation(world, testPos)) {
                              if (hasNavigablePath(world, testPos, pathTarget, 2048)) return testPos;
                          }
                      }
                  }
              }
          }
      }
      return null;
  }

  public static boolean isSafeForTeleport(ServerLevel world, BlockPos pos) {
      return isSafeLocation(world, pos);
  }

  public static boolean safeTeleport(net.minecraft.server.level.ServerPlayer player, ServerLevel world, BlockPos targetPos, int maxRadius, boolean isSpawn) {
      BlockPos safePos = findSafeLocation(world, targetPos, maxRadius, isSpawn);
      if (safePos != null) {
          player.teleportTo(world, safePos.getX() + 0.5, safePos.getY(), safePos.getZ() + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), false);
          return true;
      }
      return false;
  }

  public static java.util.Map.Entry<Boolean, java.util.List<net.minecraft.server.level.ServerPlayer>> safeTeleportWithVehicleCollect(net.minecraft.server.level.ServerPlayer player, ServerLevel world, BlockPos targetPos, int maxRadius, boolean isSpawn) {
      BlockPos safePos = findSafeLocation(world, targetPos, maxRadius, isSpawn);
      if (safePos == null) return Map.entry(false, java.util.Collections.emptyList());
      TeleportVehicles.Result result = TeleportVehicles.teleportWithMount(player, world, safePos);
      return Map.entry(result.success, result.passengerPlayers);
  }

  public static BlockPos findRandomSafeLocation(ServerLevel world, BlockPos center, int radius, int width, int height, boolean preferGrassAndSky) {
      java.util.Random rand = new java.util.Random();
      BlockPos bestFallback = null;
      
      // Try up to 64 random columns in the radius
      for(int i=0; i<64; i++) {
          int rx = rand.nextInt(radius * 2 + 1) - radius;
          int rz = rand.nextInt(radius * 2 + 1) - radius;
          BlockPos c = center.offset(rx, 0, rz);
          
          int topY = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, c.getX(), c.getZ());
          // Scan around the surface
          for(int dy = 2; dy >= -5; dy--) {
              BlockPos pos = new BlockPos(c.getX(), topY + dy, c.getZ());
              if (checkVolumeSafe(world, pos, width, height)) {
                  boolean isGrass = isGrassBlock(world, pos.below());
                  boolean isSky = world.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) <= pos.getY() + 1;
                  
                  if (preferGrassAndSky) {
                       if (isGrass && isSky) return pos;
                       if (bestFallback == null && (isGrass || isSky)) bestFallback = pos;
                  } else {
                      return pos;
                  }
                  if (bestFallback == null) bestFallback = pos;
              }
          }
      }
      return bestFallback;
  }

  private static boolean checkVolumeSafe(ServerLevel world, BlockPos pos, int width, int height) {
      if (pos.getY() < world.getMinY() || pos.getY() + height > world.getMaxY()) return false;
      
      BlockPos ground = pos.below();
      var groundState = world.getBlockState(ground);
      if (groundState.getCollisionShape(world, ground).isEmpty()) return false;
      if (isDangerousBlock(groundState.getBlock().getDescriptionId())) return false;
      if (!groundState.getFluidState().isEmpty()) return false;
      
      int xMin = -(width / 2);
      int xMax = xMin + width - 1;
      int zMin = -(width / 2);
      int zMax = zMin + width - 1;

      if (width <= 1) { xMin=0; xMax=0; zMin=0; zMax=0; }

      for(int x = xMin; x <= xMax; x++) {
           for(int z = zMin; z <= zMax; z++) {
               for(int y = 0; y < height; y++) {
                   BlockPos p = pos.offset(x, y, z);
                   var state = world.getBlockState(p);
                   if (!state.getCollisionShape(world, p).isEmpty()) return false;
                   if (isDangerousBlock(state.getBlock().getDescriptionId())) return false;
                   if (!state.getFluidState().isEmpty()) return false;
               }
           }
      }
      return true;
   }

  private static boolean isGrassBlock(ServerLevel world, BlockPos pos) {
      String id = world.getBlockState(pos).getBlock().getDescriptionId();
      if (id == null) return false;
      id = id.toLowerCase(Locale.ROOT);
      return id.contains("grass_block") || id.contains("dirt") || id.contains("podzol");
  }
}

