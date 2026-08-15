package com.omwh.utils;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class TeleportVehicles {
    public static final class Destination {
        private final ServerLevel level;
        private final Vec3 position;
        private final float yaw;
        private final float pitch;
        private final MountGraph graph;

        private Destination(ServerLevel level, Vec3 position, float yaw, float pitch, MountGraph graph) {
            this.level = level;
            this.position = position;
            this.yaw = yaw;
            this.pitch = pitch;
            this.graph = graph;
        }
    }

    public record Result(List<ServerPlayer> passengerPlayers) { }
    private record MountGraph(Entity root, MountTreeSnapshot<Entity> snapshot,
                              List<Entity> entities, List<ServerPlayer> passengerPlayers) { }

    private static final int MIN_SPAWN_Y_OFFSET = -2;
    private static final int MAX_SPAWN_Y_OFFSET = 10;

    private TeleportVehicles() { }

    public static Optional<Destination> prepareHome(ServerPlayer player, ServerLevel level,
                                                    Vec3 position, BlockPos homeBlock) {
        MountGraph graph = capture(player);
        requireSameDimension(graph.root(), level);
        Vec3 offset = position.subtract(graph.root().position());
        AABB vehicle = graph.root().getBoundingBox().move(offset);
        VehicleClearanceBox.Bounds vehicleBounds = bounds(vehicle);
        VehicleClearanceBox.Bounds policyBounds = VehicleClearanceBox.around(vehicleBounds);
        AABB policy = box(policyBounds);
        if (!VehicleClearanceBox.withinBuildHeight(vehicleBounds, level.getMinY(), level.getMaxY())
                || !level.getWorldBorder().isWithinBounds(vehicle)
                || !level.getWorldBorder().isWithinBounds(policy)) {
            return Optional.empty();
        }
        loadChunks(level, List.of(policy), new HashSet<>());
        if (!hasHomeClearance(graph, level, vehicleBounds, policyBounds, policy, homeBlock)) {
            return Optional.empty();
        }
        return Optional.of(new Destination(level, position, graph.root().getYRot(),
                graph.root().getXRot(), graph));
    }

    public static Optional<Destination> prepareSpawn(ServerPlayer player, ServerLevel level,
                                                     BlockPos center, int radius) {
        MountGraph graph = capture(player);
        requireSameDimension(graph.root(), level);
        Set<Long> loadedChunks = new HashSet<>();
        for (SafeLocationPlanner.Pos offset : SafeLocationPlanner.nearestOffsets(
                radius, MIN_SPAWN_Y_OFFSET, MAX_SPAWN_Y_OFFSET)) {
            Vec3 position = new Vec3(center.getX() + offset.x() + 0.5,
                    center.getY() + offset.y(), center.getZ() + offset.z() + 0.5);
            List<AABB> translated = translatedBoxes(graph, position);
            if (!withinWorldBounds(level, translated)) continue;
            loadChunks(level, translated, loadedChunks);
            if (!fitsSpawn(graph, level, translated)) continue;
            return Optional.of(new Destination(level, position, graph.root().getYRot(),
                    graph.root().getXRot(), graph));
        }
        return Optional.empty();
    }

    public static Result teleportVanillaHome(ServerPlayer player, TeleportTransition transition) {
        requireSameDimension(player, transition.newLevel());
        Vec3 offset = transition.position().subtract(player.position());
        loadChunks(transition.newLevel(), List.of(player.getBoundingBox().move(offset)), new HashSet<>());
        if (player.teleport(transition) == null) {
            throw new IllegalStateException("vanilla accepted /home but returned no player");
        }
        return new Result(List.of());
    }

    public static Result teleport(Destination destination) {
        Entity moved = destination.graph.root().teleport(new TeleportTransition(
                destination.level, destination.position, Vec3.ZERO,
                destination.yaw, destination.pitch, TeleportTransition.DO_NOTHING));
        if (moved == null) {
            throw new IllegalStateException("Minecraft returned no root entity from an accepted teleport");
        }
        moved.setDeltaMovement(Vec3.ZERO);
        if (!destination.graph.snapshot().isIntact(Entity::getVehicle)) {
            throw new IllegalStateException("Minecraft changed the passenger tree during recursive teleport");
        }
        return new Result(destination.graph.passengerPlayers());
    }

    private static boolean hasHomeClearance(MountGraph graph, ServerLevel level,
                                            VehicleClearanceBox.Bounds vehicle,
                                            VehicleClearanceBox.Bounds policy, AABB policyBox,
                                            BlockPos homeBlock) {
        Entity root = graph.root();
        CollisionContext context = CollisionContext.withPosition(root, vehicle.minY());
        for (BlockPos blockPos : blocksIn(policyBox)) {
            var state = level.getBlockState(blockPos);
            boolean homeBedPart = isHomeBedPart(level, blockPos, homeBlock);
            for (AABB local : state.getCollisionShape(level, blockPos, context).toAabbs()) {
                if (VehicleClearanceBox.blocks(vehicle, policy, bounds(local.move(blockPos)), homeBedPart)) {
                    return false;
                }
            }
        }
        AABB actual = box(vehicle);
        return hasNoExternalEntityCollision(level, graph, root, actual)
                && !containsFluidOrDanger(level, actual);
    }

    private static boolean fitsSpawn(MountGraph graph, ServerLevel level, List<AABB> boxes) {
        for (int i = 0; i < graph.entities().size(); i++) {
            Entity entity = graph.entities().get(i);
            AABB box = boxes.get(i);
            if (!hasNoBlockCollisionAtDestination(level, entity, box, box.minY)
                    || !hasNoExternalEntityCollision(level, graph, entity, box)
                    || containsFluidOrDanger(level, box)) {
                return false;
            }
        }
        return hasFullSafeSupport(graph.root(), level, boxes.getFirst());
    }

    private static boolean hasFullSafeSupport(Entity root, ServerLevel level, AABB box) {
        int supportY = Mth.floor(box.minY - 1.0E-7);
        int minX = Mth.floor(box.minX);
        int maxX = Mth.floor(box.maxX - 1.0E-7);
        int minZ = Mth.floor(box.minZ);
        int maxZ = Mth.floor(box.maxZ - 1.0E-7);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos support = new BlockPos(x, supportY, z);
                var state = level.getBlockState(support);
                if (!state.getFluidState().isEmpty() || isDangerous(state.getBlock().getDescriptionId())) {
                    return false;
                }
                AABB contact = new AABB(Math.max(box.minX, x), box.minY - 0.05,
                        Math.max(box.minZ, z), Math.min(box.maxX, x + 1.0), box.minY,
                        Math.min(box.maxZ, z + 1.0));
                if (hasNoBlockCollisionAtDestination(level, root, contact, box.minY)) return false;
            }
        }
        return true;
    }

    private static boolean containsFluidOrDanger(ServerLevel level, AABB box) {
        BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        BlockPos max = BlockPos.containing(box.maxX - 1.0E-7,
                box.maxY - 1.0E-7, box.maxZ - 1.0E-7);
        for (BlockPos pos : BlockPos.betweenClosed(min, max)) {
            var state = level.getBlockState(pos);
            if (!state.getFluidState().isEmpty()) return true;
            if (isDangerous(state.getBlock().getDescriptionId())) return true;
        }
        return false;
    }

    private static boolean isDangerous(String id) {
        return id.contains("lava") || id.contains("fire") || id.contains("magma")
                || id.contains("cactus") || id.contains("sweet_berry_bush")
                || id.contains("wither_rose") || id.contains("powder_snow");
    }

    private static List<AABB> translatedBoxes(MountGraph graph, Vec3 rootPosition) {
        Vec3 offset = rootPosition.subtract(graph.root().position());
        return graph.entities().stream().map(entity -> entity.getBoundingBox().move(offset)).toList();
    }

    private static boolean withinWorldBounds(ServerLevel level, List<AABB> boxes) {
        for (AABB box : boxes) {
            if (!VehicleClearanceBox.withinBuildHeight(bounds(box), level.getMinY(), level.getMaxY())
                    || !level.getWorldBorder().isWithinBounds(box)) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasNoBlockCollisionAtDestination(ServerLevel level, Entity entity,
                                                            AABB box, double destinationY) {
        CollisionContext context = CollisionContext.withPosition(entity, destinationY);
        for (BlockPos blockPos : blocksIn(box)) {
            var state = level.getBlockState(blockPos);
            for (AABB local : state.getCollisionShape(level, blockPos, context).toAabbs()) {
                if (box.intersects(local.move(blockPos))) return false;
            }
        }
        return true;
    }

    private static boolean hasNoExternalEntityCollision(ServerLevel level, MountGraph graph,
                                                        Entity entity, AABB box) {
        return level.getEntities(entity, box.inflate(1.0E-7), candidate ->
                EntityOwnership.isExternal(graph.entities(), candidate)
                        && !candidate.isSpectator()
                        && entity.canCollideWith(candidate)).isEmpty();
    }

    private static Iterable<BlockPos> blocksIn(AABB box) {
        ChunkCoverage.BlockRange range = ChunkCoverage.blockOwners(bounds(box));
        return BlockPos.betweenClosed(
                new BlockPos(range.minX(), range.minY(), range.minZ()),
                new BlockPos(range.maxX(), range.maxY(), range.maxZ()));
    }

    private static void loadChunks(ServerLevel level, List<AABB> boxes, Set<Long> loadedChunks) {
        for (AABB box : boxes) {
            ChunkCoverage.Range range = ChunkCoverage.chunkRange(
                    ChunkCoverage.blockOwners(bounds(box)));
            ChunkCoverage.loadNew(loadedChunks, range, level::getChunk);
        }
    }

    private static boolean isHomeBedPart(ServerLevel level, BlockPos candidate, BlockPos homeBlock) {
        var homeState = level.getBlockState(homeBlock);
        if (!(homeState.getBlock() instanceof BedBlock)) return false;
        if (candidate.equals(homeBlock)) return true;
        BlockPos other = homeBlock.relative(BedBlock.getConnectedDirection(homeState));
        return candidate.equals(other) && level.getBlockState(other).getBlock() instanceof BedBlock;
    }

    private static MountGraph capture(ServerPlayer source) {
        Entity root = source.getRootVehicle();
        List<MountTreeSnapshot.Edge<Entity>> edges = new ArrayList<>();
        List<Entity> entities = new ArrayList<>();
        List<ServerPlayer> passengers = new ArrayList<>();
        Deque<Entity> queue = new ArrayDeque<>();
        Map<Entity, Boolean> seen = new IdentityHashMap<>();
        queue.add(root);
        seen.put(root, Boolean.TRUE);
        while (!queue.isEmpty()) {
            Entity parent = queue.removeFirst();
            entities.add(parent);
            for (Entity child : parent.getPassengers()) {
                if (seen.put(child, Boolean.TRUE) != null) {
                    throw new IllegalStateException("passenger tree contains a cycle");
                }
                edges.add(new MountTreeSnapshot.Edge<>(parent, child));
                if (child instanceof ServerPlayer passenger && passenger != source) passengers.add(passenger);
                queue.addLast(child);
            }
        }
        return new MountGraph(root, new MountTreeSnapshot<>(edges),
                List.copyOf(entities), List.copyOf(passengers));
    }

    private static void requireSameDimension(Entity root, ServerLevel target) {
        if (!(root.level() instanceof ServerLevel current)
                || !current.dimension().equals(target.dimension())) {
            throw new IllegalArgumentException("mounted teleports must remain in one dimension");
        }
    }

    private static VehicleClearanceBox.Bounds bounds(AABB box) {
        return new VehicleClearanceBox.Bounds(box.minX, box.minY, box.minZ,
                box.maxX, box.maxY, box.maxZ);
    }

    private static AABB box(VehicleClearanceBox.Bounds bounds) {
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }
}
