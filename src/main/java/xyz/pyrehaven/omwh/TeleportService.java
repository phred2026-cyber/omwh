package xyz.pyrehaven.omwh;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

public final class TeleportService {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    private static final EntityTree<Entity> ENTITY_TREE = new MinecraftEntityTree();
    static final int MAX_PASSENGER_TREE_NODES = 64;
    static final int MAX_PASSENGER_TREE_EDGES = MAX_PASSENGER_TREE_NODES - 1;
    private static final int SNAPSHOT_NODE_PASSES = 5;
    private static final int SNAPSHOT_EDGE_PASSES = 3;
    private static final int SNAPSHOT_FIXED_WORK = 1;
    static final int SNAPSHOT_WORK = MAX_PASSENGER_TREE_NODES * SNAPSHOT_NODE_PASSES
            + MAX_PASSENGER_TREE_EDGES * SNAPSHOT_EDGE_PASSES + SNAPSHOT_FIXED_WORK;
    static final int LIFECYCLE_CAPTURE_WORK = SNAPSHOT_WORK
            + MAX_PASSENGER_TREE_NODES + 1;
    static final int LIFECYCLE_VALIDATION_WORK = SNAPSHOT_WORK
            + MAX_PASSENGER_TREE_NODES
            + MAX_PASSENGER_TREE_EDGES
            + MAX_PASSENGER_TREE_NODES
            + MAX_PASSENGER_TREE_NODES
            + 10;
    static final int COMPLETION_WORK = SNAPSHOT_WORK
            + MAX_PASSENGER_TREE_NODES
            + MAX_PASSENGER_TREE_NODES + MAX_PASSENGER_TREE_EDGES
            + SNAPSHOT_WORK
            + 1 + MAX_PASSENGER_TREE_NODES + MAX_PASSENGER_TREE_EDGES
            + 2 * MAX_PASSENGER_TREE_NODES
            + MAX_PASSENGER_TREE_EDGES
            + MAX_PASSENGER_TREE_NODES
            + MAX_PASSENGER_TREE_NODES
            + 2 * MAX_PASSENGER_TREE_NODES
            + 2 * MAX_PASSENGER_TREE_NODES
            + 3
            + MAX_PASSENGER_TREE_EDGES;

    static final class PassengerTreeTooLarge extends IllegalStateException {
        PassengerTreeTooLarge() {
            super("passenger tree exceeds the supported " + MAX_PASSENGER_TREE_NODES + " entities");
        }
    }

    interface EntityTree<T> {
        UUID uuid(T entity);
        List<T> children(T entity);
        T parent(T entity);
        boolean isPlayer(T entity);
        boolean isServerSide(T entity);
        boolean isRemoved(T entity);
        Object level(T entity);
    }

    enum Outcome { SUCCESS, FAILED, PARTIAL }
    enum LifecycleStatus { CURRENT, STALE, TOO_LARGE }
    record Attempt<T>(Outcome outcome, List<T> entities) {
        boolean success() { return outcome == Outcome.SUCCESS; }
    }
    record Result(Outcome outcome, List<ServerPlayer> passengerPlayers) {
        boolean success() { return outcome == Outcome.SUCCESS; }
        boolean partial() { return outcome == Outcome.PARTIAL; }
    }
    private record Snapshot<T>(UUID rootUuid, Map<UUID, T> entities, Map<UUID, UUID> parents,
                               Map<UUID, T> players, List<UUID> order) { }
    record LifecycleFence<T>(T player, UUID playerUuid, Object sourceLevel, T root,
                             boolean mounted, double rootWidth, double rootHeight,
                             Snapshot<T> tree) { }

    private TeleportService() { }

    static Result teleport(ServerPlayer source, DestinationSafety.Prepared destination) {
        Entity root = source.getRootVehicle();
        if (!(root.level() instanceof ServerLevel sourceLevel)) {
            LOGGER.error("OMWH refused a teleport whose root is not in a server level");
            return new Result(Outcome.FAILED, List.of());
        }
        Attempt<Entity> attempt = attempt(root, sourceLevel, destination.level(),
                sourceLevel == destination.level(), ENTITY_TREE,
                entity -> entity.teleport(destination.transition()),
                entity -> {
                    if (destination.clearVelocity()) entity.setDeltaMovement(Vec3.ZERO);
                });
        attempt = refreshTrackingSafely(attempt, ENTITY_TREE,
                entity -> destination.level().getChunkSource().move((ServerPlayer) entity),
                entity -> {
                    destination.level().getChunkSource().removeEntity(entity);
                    destination.level().getChunkSource().addEntity(entity);
                });
        List<ServerPlayer> passengers = passengerPlayers(attempt.entities(), ENTITY_TREE, source).stream()
                .map(entity -> (ServerPlayer) entity)
                .toList();
        return new Result(attempt.outcome(), passengers);
    }

    static LifecycleFence<Entity> captureLifecycle(ServerPlayer player) {
        Entity root = player.getRootVehicle();
        return captureLifecycle(player, root, player.level(), root != player,
                root.getBbWidth(), root.getBbHeight(), ENTITY_TREE);
    }

    static boolean isLifecycleCurrent(LifecycleFence<Entity> fence) {
        return lifecycleStatus(fence) == LifecycleStatus.CURRENT;
    }

    static LifecycleStatus lifecycleStatus(LifecycleFence<Entity> fence) {
        ServerPlayer player = (ServerPlayer) fence.player();
        Entity root = player.getRootVehicle();
        return lifecycleStatus(fence, player, root, player.level(), root != player,
                root.getBbWidth(), root.getBbHeight(), !player.hasDisconnected(), player.isAlive(), ENTITY_TREE);
    }

    static <T> LifecycleFence<T> captureLifecycle(T player, T root, Object sourceLevel,
                                                   boolean mounted, double rootWidth, double rootHeight,
                                                   EntityTree<T> tree) {
        Snapshot<T> snapshot = snapshot(root, tree);
        UUID playerUuid = requireUuid(tree.uuid(player));
        if (snapshot.entities().get(playerUuid) != player) {
            throw new IllegalStateException("command player is not in the captured passenger tree");
        }
        validateSourceTree(snapshot, sourceLevel, tree);
        return new LifecycleFence<>(player, playerUuid, sourceLevel, root, mounted,
                rootWidth, rootHeight, snapshot);
    }

    static <T> boolean isLifecycleCurrent(LifecycleFence<T> fence, T player, T root,
                                          Object sourceLevel, boolean mounted,
                                          double rootWidth, double rootHeight,
                                          boolean connected, boolean alive, EntityTree<T> tree) {
        return lifecycleStatus(fence, player, root, sourceLevel, mounted, rootWidth, rootHeight,
                connected, alive, tree) == LifecycleStatus.CURRENT;
    }

    static <T> LifecycleStatus lifecycleStatus(LifecycleFence<T> fence, T player, T root,
                                               Object sourceLevel, boolean mounted,
                                               double rootWidth, double rootHeight,
                                               boolean connected, boolean alive, EntityTree<T> tree) {
        if (!connected || !alive || player != fence.player() || root != fence.root()
                || sourceLevel != fence.sourceLevel() || mounted != fence.mounted()
                || Double.compare(rootWidth, fence.rootWidth()) != 0
                || Double.compare(rootHeight, fence.rootHeight()) != 0
                || !fence.playerUuid().equals(tree.uuid(player))) return LifecycleStatus.STALE;
        try {
            Snapshot<T> current = snapshot(root, tree);
            validateSourceTree(current, sourceLevel, tree);
            if (!fence.tree().rootUuid().equals(current.rootUuid())
                    || !fence.tree().parents().equals(current.parents())
                    || !fence.tree().entities().keySet().equals(current.entities().keySet())) {
                return LifecycleStatus.STALE;
            }
            for (Map.Entry<UUID, T> entry : fence.tree().entities().entrySet()) {
                if (current.entities().get(entry.getKey()) != entry.getValue()) return LifecycleStatus.STALE;
            }
            return LifecycleStatus.CURRENT;
        } catch (PassengerTreeTooLarge tooLarge) {
            return LifecycleStatus.TOO_LARGE;
        } catch (RuntimeException invalid) {
            return LifecycleStatus.STALE;
        }
    }

    static <T> void refreshTracking(Attempt<T> attempt, EntityTree<T> tree,
                                    Consumer<T> movePlayer, Consumer<T> retrackEntity) {
        if (!attempt.success()) return;
        for (T entity : attempt.entities()) {
            if (tree.isPlayer(entity)) movePlayer.accept(entity);
        }
        for (T entity : attempt.entities()) {
            if (!tree.isPlayer(entity)) retrackEntity.accept(entity);
        }
    }

    static <T> Attempt<T> refreshTrackingSafely(Attempt<T> attempt, EntityTree<T> tree,
                                                Consumer<T> movePlayer, Consumer<T> retrackEntity) {
        if (!attempt.success()) return attempt;
        try {
            refreshTracking(attempt, tree, movePlayer, retrackEntity);
            return attempt;
        } catch (RuntimeException failure) {
            LOGGER.error("OMWH tracking refresh failed after teleport mutation", failure);
            return new Attempt<>(Outcome.PARTIAL, attempt.entities());
        }
    }

    static <T> List<T> passengerPlayers(List<T> movedEntities, EntityTree<T> tree, T commandPlayer) {
        return movedEntities.stream()
                .filter(entity -> tree.isPlayer(entity) && entity != commandPlayer)
                .toList();
    }

    static <T> Attempt<T> attempt(T root, Object sourceLevel, Object destinationLevel, boolean sameDimension,
                                  EntityTree<T> tree, Function<T, T> teleporter,
                                  Consumer<T> clearVelocity) {
        Snapshot<T> expected;
        try {
            expected = snapshot(root, tree);
            validateSourceTree(expected, sourceLevel, tree);
        } catch (RuntimeException exception) {
            LOGGER.error("OMWH refused an invalid source passenger tree", exception);
            return new Attempt<>(Outcome.FAILED, List.of());
        }

        T movedRoot;
        try {
            movedRoot = teleporter.apply(root);
            if (movedRoot == null) {
                LOGGER.error("Minecraft returned no root entity from OMWH teleport");
                return new Attempt<>(Outcome.FAILED, List.of());
            }
        } catch (RuntimeException exception) {
            LOGGER.error("OMWH teleport mutation failed", exception);
            return new Attempt<>(Outcome.FAILED, List.of());
        }

        try {
            Snapshot<T> moved = snapshot(movedRoot, tree);
            if (!expected.rootUuid().equals(moved.rootUuid())
                    || !expected.entities().keySet().equals(moved.entities().keySet())
                    || !expected.parents().equals(moved.parents())) {
                LOGGER.error("Minecraft returned an incomplete or changed OMWH passenger tree");
                return partial(moved);
            }

            for (Map.Entry<UUID, T> entry : moved.entities().entrySet()) {
                UUID entityUuid = entry.getKey();
                T entity = entry.getValue();
                if (!tree.isServerSide(entity) || tree.isRemoved(entity) || tree.level(entity) != destinationLevel) {
                    LOGGER.error("Minecraft left an OMWH passenger tree member outside the destination level");
                    return partial(moved);
                }
                if (sameDimension && expected.entities().get(entityUuid) != entity) {
                    LOGGER.error("Minecraft replaced an entity during same-dimension OMWH teleport");
                    return partial(moved);
                }
            }

            for (Map.Entry<UUID, UUID> edge : expected.parents().entrySet()) {
                T movedChild = moved.entities().get(edge.getKey());
                T movedParent = moved.entities().get(edge.getValue());
                if (tree.parent(movedChild) != movedParent) {
                    LOGGER.error("Minecraft changed an OMWH passenger attachment during teleport");
                    return partial(moved);
                }
            }
            for (Map.Entry<UUID, T> originalPlayer : expected.players().entrySet()) {
                if (moved.entities().get(originalPlayer.getKey()) != originalPlayer.getValue()) {
                    LOGGER.error("Minecraft replaced a player during OMWH teleport");
                    return partial(moved);
                }
            }

            clearVelocity.accept(movedRoot);
            return new Attempt<>(Outcome.SUCCESS, orderedEntities(moved));
        } catch (RuntimeException exception) {
            LOGGER.error("OMWH passenger-tree reconciliation failed after teleport mutation", exception);
            return new Attempt<>(Outcome.PARTIAL, List.of());
        }
    }

    private static <T> Snapshot<T> snapshot(T root, EntityTree<T> tree) {
        Map<UUID, T> entities = new LinkedHashMap<>();
        Map<UUID, UUID> parents = new LinkedHashMap<>();
        Map<UUID, T> players = new LinkedHashMap<>();
        List<UUID> order = new ArrayList<>();
        Map<T, Boolean> identities = new IdentityHashMap<>();
        Deque<T> queue = new ArrayDeque<>();
        queue.add(root);
        identities.put(root, Boolean.TRUE);

        while (!queue.isEmpty()) {
            if (entities.size() == MAX_PASSENGER_TREE_NODES) throw new PassengerTreeTooLarge();
            T parent = queue.removeFirst();
            UUID parentUuid = requireUuid(tree.uuid(parent));
            if (entities.put(parentUuid, parent) != null) {
                throw new IllegalStateException("passenger tree contains duplicate UUID " + parentUuid);
            }
            order.add(parentUuid);
            List<T> directChildren = tree.children(parent);
            int remainingCapacity = MAX_PASSENGER_TREE_NODES - entities.size() - queue.size();
            if (directChildren.size() > remainingCapacity) throw new PassengerTreeTooLarge();
            if (tree.isPlayer(parent)) {
                players.put(parentUuid, parent);
                if (!directChildren.isEmpty()) {
                    throw new IllegalStateException("ServerPlayer passenger nodes are unsupported");
                }
            }
            for (T child : directChildren) {
                if (identities.put(child, Boolean.TRUE) != null) {
                    throw new IllegalStateException("passenger tree contains a cycle or duplicate entity");
                }
                UUID childUuid = requireUuid(tree.uuid(child));
                if (parents.put(childUuid, parentUuid) != null) {
                    throw new IllegalStateException("passenger tree contains duplicate child UUID " + childUuid);
                }
                queue.addLast(child);
            }
        }
        return new Snapshot<>(order.getFirst(), Map.copyOf(entities), Map.copyOf(parents),
                Map.copyOf(players), List.copyOf(order));
    }

    private static <T> void validateSourceTree(Snapshot<T> snapshot, Object sourceLevel, EntityTree<T> tree) {
        for (T entity : snapshot.entities().values()) {
            if (!tree.isServerSide(entity) || tree.isRemoved(entity) || tree.level(entity) != sourceLevel) {
                throw new IllegalStateException("invalid source passenger tree member");
            }
        }
    }

    private static UUID requireUuid(UUID uuid) {
        if (uuid == null) throw new IllegalStateException("passenger tree contains a null UUID");
        return uuid;
    }
    private static <T> Attempt<T> partial(Snapshot<T> snapshot) {
        return new Attempt<>(Outcome.PARTIAL, orderedEntities(snapshot));
    }

    private static <T> List<T> orderedEntities(Snapshot<T> snapshot) {
        return snapshot.order().stream().map(snapshot.entities()::get).toList();
    }

    private static final class MinecraftEntityTree implements EntityTree<Entity> {
        @Override
        public UUID uuid(Entity entity) {
            return entity.getUUID();
        }

        @Override
        public List<Entity> children(Entity entity) {
            return entity.getPassengers();
        }

        @Override
        public Entity parent(Entity entity) {
            return entity.getVehicle();
        }

        @Override
        public boolean isPlayer(Entity entity) {
            return entity instanceof ServerPlayer;
        }

        @Override
        public boolean isServerSide(Entity entity) {
            return entity.level() instanceof ServerLevel;
        }

        @Override
        public boolean isRemoved(Entity entity) {
            return entity.isRemoved();
        }

        @Override
        public Object level(Entity entity) {
            return entity.level();
        }
    }
}
