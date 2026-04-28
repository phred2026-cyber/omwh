package com.omwh.utils;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.core.BlockPos;

import java.util.*;

public class TeleportVehicles {
   public static class Result {
       public final boolean success;
       public final List<ServerPlayer> passengerPlayers;
       public Result(boolean success, List<ServerPlayer> passengerPlayers) { this.success = success; this.passengerPlayers = passengerPlayers; }
   }

   private static final List<Runnable> remountQueue = new ArrayList<>();
   private static final List<Runnable> postSyncQueue = new ArrayList<>();
   private static final List<Runnable> deferredCorrectionQueue = new ArrayList<>();
   private static class PendingRemount { Entity child; Entity parent; int attemptsLeft; PendingRemount(Entity c, Entity p, int a){child=c;parent=p;attemptsLeft=a;} }
   private static final List<PendingRemount> multiRemount = new ArrayList<>();
   private static boolean tickHookInstalled = false;
   private static final Logger logger = LoggerFactory.getLogger("omwh:teleport");
   private static final boolean ENABLE_TELEPORT_DEBUG = false;
   private static final boolean BLOCK_CROSS_DIMENSION = true;
   private static final String CROSS_DIMENSION_BLOCKED_MESSAGE = "§cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!";

   private static void debug(java.util.function.Supplier<String> s) { if (ENABLE_TELEPORT_DEBUG) logger.info(s.get()); }

   public static void ensureTickHook() {
       if (!tickHookInstalled) {
           ServerTickEvents.END_SERVER_TICK.register(server -> {
               if (!remountQueue.isEmpty()) {
                   List<Runnable> tasks = new ArrayList<>(remountQueue);
                   remountQueue.clear();
                   tasks.forEach(Runnable::run);
               }
               if (!multiRemount.isEmpty()) {
                   Iterator<PendingRemount> iterator = multiRemount.iterator();
                   while (iterator.hasNext()) {
                       PendingRemount pending = iterator.next();
                       Entity child = pending.child;
                       Entity parent = pending.parent;
                       if (child.isRemoved() || parent.isRemoved()) { iterator.remove(); continue; }
                       if (child.isPassenger()) { iterator.remove(); continue; }
                       int attempt = 1 + (MAX_REMOUNT_ATTEMPTS - pending.attemptsLeft);
                       boolean success = child.startRiding(parent, true, true);
                       debug(() -> "TeleportVehicles: multi-remount attempt#"+attempt+" success="+success);
                       if (success || child.isPassenger()) iterator.remove();
                       else {
                           pending.attemptsLeft -= 1;
                           if (pending.attemptsLeft <= 0) {
                               if (child instanceof ServerPlayer) {
                                   ((ServerPlayer) child).sendSystemMessage(Component.literal("Could not automatically remount you on your vehicle after teleport."), false);
                               }
                               iterator.remove();
                           }
                       }
                   }
               }
               if (!postSyncQueue.isEmpty()) {
                   List<Runnable> tasks = new ArrayList<>(postSyncQueue);
                   postSyncQueue.clear();
                   tasks.forEach(Runnable::run);
               }
               if (!deferredCorrectionQueue.isEmpty()) {
                   List<Runnable> tasks = new ArrayList<>(deferredCorrectionQueue);
                   deferredCorrectionQueue.clear();
                   tasks.forEach(Runnable::run);
               }
           });
           tickHookInstalled = true;
       }
   }

   public static Result teleportWithMount(ServerPlayer player, ServerLevel targetLevel, BlockPos targetPos) {
       Entity root = player.getRootVehicle();
       BlockPos destPos = targetPos;
       debug(() -> "TeleportVehicles: start player="+player.getName().getString());
       if (root != player) {
           try {
               int widthBlocks = (int) Math.max(1, Math.ceil(root.getBbWidth()));
               int heightBlocks = (int) Math.max(2, Math.ceil(root.getBbHeight()));
               BlockPos vehicleSafe = SafeTeleportUtils.findSafeLocationForSize(targetLevel, targetPos, 16, widthBlocks, heightBlocks, true);
               if (vehicleSafe == null) {
                   player.sendSystemMessage(Component.literal("§cYour vehicle is too large to safely spawn near the chosen location."), false);
                   return new Result(false, Collections.emptyList());
               }
               destPos = vehicleSafe;
           } catch (Throwable t) { debug(() -> "TeleportVehicles: exception during size-check: "+t.getMessage()); }
       }
       if (root == player) {
            teleportEntity(player, targetLevel, targetPos.getX() + 0.5, targetPos.getY() + 1, targetPos.getZ() + 0.5);
            return new Result(true, Collections.emptyList());
}

       Graph graph = flatten(root, player);
       List<Entity> ordered = graph.ordered;
       Map<Entity, Entity> parentMap = graph.parent;
       List<ServerPlayer> passengerPlayers = graph.passengerPlayers;

       ordered.stream().skip(1).forEach(e -> { if (e.isPassenger()) e.stopRiding(); });

       double safeX = destPos.getX() + 0.5;
       double safeY = destPos.getY() + 1;
       double safeZ = destPos.getZ() + 0.5;

       try {
           int centerChunkX = destPos.getX() >> 4;
           int centerChunkZ = destPos.getZ() >> 4;
           for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) targetLevel.getChunk(centerChunkX + dx, centerChunkZ + dz);
           var rootLevel = WorldCompat.getLevel(root);
           ServerLevel rw = rootLevel != null ? rootLevel : targetLevel;
           boolean sameDimension = rw.dimension().equals(targetLevel.dimension());
           if (!sameDimension) {
               if (BLOCK_CROSS_DIMENSION) {
                   player.sendSystemMessage(Component.literal(CROSS_DIMENSION_BLOCKED_MESSAGE), false);
                   return new Result(false, Collections.emptyList());
               } else {
                   teleportEntity(player, targetLevel, safeX, safeY, safeZ);
                   return new Result(false, Collections.emptyList());
               }
           }

           teleportEntity(root, targetLevel, safeX, safeY, safeZ);
           root.setDeltaMovement(0.0, 0.0, 0.0);

           targetLevel.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);

           ordered.stream().filter(e -> e != root && e != player).forEach(e -> {
               teleportEntity(e, targetLevel, safeX, safeY, safeZ);
               e.setDeltaMovement(0.0, 0.0, 0.0);
           });

           teleportEntity(player, targetLevel, safeX, safeY, safeZ);
           player.setDeltaMovement(0.0, 0.0, 0.0);
       } catch (Exception e) {
           teleportEntity(player, targetLevel, safeX, safeY, safeZ);
           return new Result(false, Collections.emptyList());
       }

       remountQueue.add(() -> {
           ordered.stream().skip(1).forEach(child -> {
               Entity parent = parentMap.get(child);
               if (parent != null && !child.isPassenger()) {
                   child.startRiding(parent, true, true);
                   if (!child.isPassenger()) multiRemount.add(new PendingRemount(child, parent, MAX_REMOUNT_ATTEMPTS));
               }
           });
           Runnable deferred = () -> {
               // correction logic
               var rootAfter = root;
               double dx = Math.abs(rootAfter.getX() - safeX);
               double dy = Math.abs(rootAfter.getY() - safeY);
               double dz = Math.abs(rootAfter.getZ() - safeZ);
               if (dx > 0.5 || dy > 0.5 || dz > 0.5) {
                   teleportEntity(rootAfter, targetLevel, safeX, safeY, safeZ);
                   rootAfter.setDeltaMovement(0.0, 0.0, 0.0);
               }
               if (player.isPassenger()) {
                   // Player is mounted on vehicle; reset yaw/pitch to prevent camera spinning
                   player.setYRot(0.0f);
                   player.setXRot(0.0f);
               } else {
                   double pdx = Math.abs(player.getX() - safeX);
                   double pdy = Math.abs(player.getY() - safeY);
                   double pdz = Math.abs(player.getZ() - safeZ);
                   if (pdx > 0.5 || pdy > 0.5 || pdz > 0.5) {
                       teleportEntity(player, targetLevel, safeX, safeY, safeZ);
                   }
               }
           };
           postSyncQueue.add(() -> deferredCorrectionQueue.add(deferred));
       });

       return new Result(true, passengerPlayers);
   }

   private static class Graph { List<Entity> ordered; Map<Entity, Entity> parent; List<ServerPlayer> passengerPlayers; Graph(List<Entity> o, Map<Entity, Entity> p, List<ServerPlayer> pp){ordered=o;parent=p;passengerPlayers=pp;} }

   private static Graph flatten(Entity root, ServerPlayer sourcePlayer) {
       List<Entity> ordered = new ArrayList<>();
       Map<Entity, Entity> parent = new HashMap<>();
       List<ServerPlayer> passengerPlayers = new ArrayList<>();
       Deque<Entity> stack = new ArrayDeque<>();
       stack.push(root);
       while (!stack.isEmpty()) {
           Entity e = stack.pop();
           ordered.add(e);
           List<Entity> list = e.getPassengers();
           for (Entity p : list) {
               parent.put(p, e);
               if (p instanceof ServerPlayer && p != sourcePlayer) passengerPlayers.add((ServerPlayer) p);
               stack.push(p);
           }
       }
       return new Graph(ordered, parent, passengerPlayers);
   }

    private static void teleportEntity(Entity e, ServerLevel world, double x, double y, double z) {
        var transition = new net.minecraft.world.level.portal.TeleportTransition(
                world,
                new net.minecraft.world.phys.Vec3(x, y, z),
                net.minecraft.world.phys.Vec3.ZERO,
                e.getYRot(),
                e.getXRot(),
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING
        );
        e.teleport(transition);
    }

   private static final int MAX_REMOUNT_ATTEMPTS = 3;
}

