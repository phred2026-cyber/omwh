package com.omwh.utils;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.*;

public class TeleportVehicles {
    public static class Result {
        public final boolean success;
        public final List<ServerPlayerEntity> passengerPlayers;
        public Result(boolean success, List<ServerPlayerEntity> passengerPlayers) { this.success = success; this.passengerPlayers = passengerPlayers; }
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
                        if (child.hasVehicle()) { iterator.remove(); continue; }
                        int attempt = 1 + (MAX_REMOUNT_ATTEMPTS - pending.attemptsLeft);
                        boolean success = child.startRiding(parent, true, true);
                        debug(() -> "TeleportVehicles: multi-remount attempt#"+attempt+" success="+success);
                        if (success || child.hasVehicle()) iterator.remove();
                        else {
                            pending.attemptsLeft -= 1;
                            if (pending.attemptsLeft <= 0) {
                                if (child instanceof ServerPlayerEntity) {
                                    ((ServerPlayerEntity) child).sendMessage(Text.literal("Could not automatically remount you on your vehicle after teleport."), false);
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

    public static Result teleportWithMount(ServerPlayerEntity player, ServerWorld targetWorld, BlockPos targetPos) {
        Entity root = player.getRootVehicle();
        BlockPos destPos = targetPos;
        debug(() -> "TeleportVehicles: start player="+player.getName().getString());
        if (root != player) {
            try {
                int widthBlocks = (int) Math.max(1, Math.ceil(root.getWidth()));
                int heightBlocks = (int) Math.max(2, Math.ceil(root.getHeight()));
                BlockPos vehicleSafe = SafeTeleportUtils.findSafeLocationForSize(targetWorld, targetPos, 16, widthBlocks, heightBlocks, true);
                if (vehicleSafe == null) {
                    player.sendMessage(Text.literal("§cYour vehicle is too large to safely spawn near the chosen location."), false);
                    return new Result(false, Collections.emptyList());
                }
                destPos = vehicleSafe;
            } catch (Throwable t) { debug(() -> "TeleportVehicles: exception during size-check: "+t.getMessage()); }
        }
        if (root == player) {
            player.teleport(targetWorld, targetPos.getX() + 0.5, targetPos.getY() + 1, targetPos.getZ() + 0.5, java.util.Set.of(), player.getYaw(), player.getPitch(), false);
            return new Result(true, Collections.emptyList());
        }

        Graph graph = flatten(root, player);
        List<Entity> ordered = graph.ordered;
        Map<Entity, Entity> parentMap = graph.parent;
        List<ServerPlayerEntity> passengerPlayers = graph.passengerPlayers;

        ordered.stream().skip(1).forEach(e -> { if (e.hasVehicle()) e.stopRiding(); });

        double safeX = destPos.getX() + 0.5;
        double safeY = destPos.getY() + 1;
        double safeZ = destPos.getZ() + 0.5;

        try {
            int centerChunkX = destPos.getX() >> 4;
            int centerChunkZ = destPos.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) targetWorld.getChunk(centerChunkX + dx, centerChunkZ + dz);
            var rootWorld = WorldCompat.getWorld(root);
            ServerWorld rw = rootWorld != null ? rootWorld : targetWorld;
            boolean sameDimension = rw.getRegistryKey().equals(targetWorld.getRegistryKey());
            if (!sameDimension) {
                if (BLOCK_CROSS_DIMENSION) {
                    player.sendMessage(Text.literal(CROSS_DIMENSION_BLOCKED_MESSAGE), false);
                    return new Result(false, Collections.emptyList());
                } else {
                    player.teleport(targetWorld, safeX, safeY, safeZ, java.util.Set.of(), player.getYaw(), player.getPitch(), false);
                    return new Result(false, Collections.emptyList());
                }
            }

            teleportEntity(root, targetWorld, safeX, safeY, safeZ);
            root.setVelocity(0.0, 0.0, 0.0);

            targetWorld.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);

            ordered.stream().filter(e -> e != root && e != player).forEach(e -> {
                teleportEntity(e, targetWorld, safeX, safeY, safeZ);
                e.setVelocity(0.0, 0.0, 0.0);
            });

            player.teleport(targetWorld, safeX, safeY, safeZ, java.util.Set.of(), player.getYaw(), player.getPitch(), false);
            player.setVelocity(0.0, 0.0, 0.0);
        } catch (Exception e) {
            player.teleport(targetWorld, safeX, safeY, safeZ, java.util.Set.of(), player.getYaw(), player.getPitch(), false);
            return new Result(false, Collections.emptyList());
        }

        remountQueue.add(() -> {
            ordered.stream().skip(1).forEach(child -> {
                Entity parent = parentMap.get(child);
                if (parent != null && !child.hasVehicle()) {
                    child.startRiding(parent, true, true);
                    if (!child.hasVehicle()) multiRemount.add(new PendingRemount(child, parent, MAX_REMOUNT_ATTEMPTS));
                }
            });
            Runnable deferred = () -> {
                // correction logic
                var rootAfter = root;
                double dx = Math.abs(rootAfter.getX() - safeX);
                double dy = Math.abs(rootAfter.getY() - safeY);
                double dz = Math.abs(rootAfter.getZ() - safeZ);
                if (dx > 0.5 || dy > 0.5 || dz > 0.5) {
                    teleportEntity(rootAfter, targetWorld, safeX, safeY, safeZ);
                    rootAfter.setVelocity(0.0, 0.0, 0.0);
                }
                if (player.hasVehicle()) {
                    // Player is mounted on vehicle; reset yaw/pitch to prevent camera spinning
                    player.setYaw(0.0f);
                    player.setPitch(0.0f);
                } else {
                    double pdx = Math.abs(player.getX() - safeX);
                    double pdy = Math.abs(player.getY() - safeY);
                    double pdz = Math.abs(player.getZ() - safeZ);
                    if (pdx > 0.5 || pdy > 0.5 || pdz > 0.5) {
                        player.teleport(targetWorld, safeX, safeY, safeZ, java.util.Set.of(), player.getYaw(), player.getPitch(), false);
                    }
                }
            };
            postSyncQueue.add(() -> deferredCorrectionQueue.add(deferred));
        });

        return new Result(true, passengerPlayers);
    }

    private static class Graph { List<Entity> ordered; Map<Entity, Entity> parent; List<ServerPlayerEntity> passengerPlayers; Graph(List<Entity> o, Map<Entity, Entity> p, List<ServerPlayerEntity> pp){ordered=o;parent=p;passengerPlayers=pp;} }

    private static Graph flatten(Entity root, ServerPlayerEntity sourcePlayer) {
        List<Entity> ordered = new ArrayList<>();
        Map<Entity, Entity> parent = new HashMap<>();
        List<ServerPlayerEntity> passengerPlayers = new ArrayList<>();
        Deque<Entity> stack = new ArrayDeque<>();
        stack.push(root);
        while (!stack.isEmpty()) {
            Entity e = stack.pop();
            ordered.add(e);
            List<Entity> list = e.getPassengerList();
            for (Entity p : list) {
                parent.put(p, e);
                if (p instanceof ServerPlayerEntity && p != sourcePlayer) passengerPlayers.add((ServerPlayerEntity) p);
                stack.push(p);
            }
        }
        return new Graph(ordered, parent, passengerPlayers);
    }

    private static void teleportEntity(Entity e, ServerWorld world, double x, double y, double z) {
        if (e instanceof ServerPlayerEntity) {
            ((ServerPlayerEntity)e).teleport(world, x, y, z, java.util.Set.of(), e.getYaw(), e.getPitch(), false);
        } else {
            e.teleport(world, x, y, z, java.util.Set.of(), e.getYaw(), e.getPitch(), false);
        }
    }

    private static final int MAX_REMOUNT_ATTEMPTS = 3;
}
