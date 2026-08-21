package xyz.pyrehaven.omwh;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.portal.TeleportTransition;
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

    interface EntityTree<T> {
        UUID uuid(T entity);
        List<T> children(T entity);
        T parent(T entity);
        boolean isPlayer(T entity);
        boolean isServerSide(T entity);
        boolean isRemoved(T entity);
        Object level(T entity);
    }

    record Attempt<T>(boolean success, List<T> entities) { }
    record Result(boolean success, List<ServerPlayer> passengerPlayers) { }
    private record Snapshot<T>(UUID rootUuid, Map<UUID, T> entities, Map<UUID, UUID> parents,
                               Map<UUID, T> players, List<UUID> order) { }

    private TeleportService() { }

    static Result teleport(ServerPlayer source, DestinationSafety.Prepared destination) {
        Entity root = source.getRootVehicle();
        if (!(root.level() instanceof ServerLevel sourceLevel)) {
            LOGGER.error("OMWH refused a teleport whose root is not in a server level");
            return new Result(false, List.of());
        }
        Attempt<Entity> attempt = attempt(root, sourceLevel, destination.level(),
                sourceLevel == destination.level(), ENTITY_TREE,
                entity -> entity.teleport(new TeleportTransition(destination.level(), destination.position(),
                        Vec3.ZERO, destination.yaw(), destination.pitch(), TeleportTransition.DO_NOTHING)),
                entity -> entity.setDeltaMovement(Vec3.ZERO));
        if (!attempt.success()) return new Result(false, List.of());
        List<ServerPlayer> passengers = passengerPlayers(attempt.entities(), ENTITY_TREE, source).stream()
                .map(entity -> (ServerPlayer) entity)
                .toList();
        return new Result(true, passengers);
    }

    static <T> List<T> passengerPlayers(List<T> movedEntities, EntityTree<T> tree, T commandPlayer) {
        return movedEntities.stream()
                .filter(entity -> tree.isPlayer(entity) && entity != commandPlayer)
                .toList();
    }

    static <T> Attempt<T> attempt(T root, Object sourceLevel, Object destinationLevel, boolean sameDimension,
                                  EntityTree<T> tree, Function<T, T> teleporter,
                                  Consumer<T> clearVelocity) {
        try {
            Snapshot<T> expected = snapshot(root, tree);
            for (T entity : expected.entities().values()) {
                if (!tree.isServerSide(entity) || tree.isRemoved(entity) || tree.level(entity) != sourceLevel) {
                    LOGGER.error("OMWH refused an invalid source passenger tree");
                    return failed(expected);
                }
            }

            T movedRoot = teleporter.apply(root);
            if (movedRoot == null) {
                LOGGER.error("Minecraft returned no root entity from OMWH teleport");
                return failed(expected);
            }

            Snapshot<T> moved = snapshot(movedRoot, tree);
            if (!expected.rootUuid().equals(moved.rootUuid())
                    || !expected.entities().keySet().equals(moved.entities().keySet())
                    || !expected.parents().equals(moved.parents())) {
                LOGGER.error("Minecraft returned an incomplete or changed OMWH passenger tree");
                return failed(moved);
            }

            for (Map.Entry<UUID, T> entry : moved.entities().entrySet()) {
                UUID entityUuid = entry.getKey();
                T entity = entry.getValue();
                if (!tree.isServerSide(entity) || tree.isRemoved(entity) || tree.level(entity) != destinationLevel) {
                    LOGGER.error("Minecraft left an OMWH passenger tree member outside the destination level");
                    return failed(moved);
                }
                if (sameDimension && expected.entities().get(entityUuid) != entity) {
                    LOGGER.error("Minecraft replaced an entity during same-dimension OMWH teleport");
                    return failed(moved);
                }
            }

            for (Map.Entry<UUID, UUID> edge : expected.parents().entrySet()) {
                T movedChild = moved.entities().get(edge.getKey());
                T movedParent = moved.entities().get(edge.getValue());
                if (tree.parent(movedChild) != movedParent) {
                    LOGGER.error("Minecraft changed an OMWH passenger attachment during teleport");
                    return failed(moved);
                }
            }
            for (Map.Entry<UUID, T> originalPlayer : expected.players().entrySet()) {
                if (moved.entities().get(originalPlayer.getKey()) != originalPlayer.getValue()) {
                    LOGGER.error("Minecraft replaced a player during OMWH teleport");
                    return failed(moved);
                }
            }

            clearVelocity.accept(movedRoot);
            return new Attempt<>(true, orderedEntities(moved));
        } catch (RuntimeException exception) {
            LOGGER.error("OMWH teleport attempt failed", exception);
            return new Attempt<>(false, List.of());
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
            T parent = queue.removeFirst();
            UUID parentUuid = requireUuid(tree.uuid(parent));
            if (entities.put(parentUuid, parent) != null) {
                throw new IllegalStateException("passenger tree contains duplicate UUID " + parentUuid);
            }
            order.add(parentUuid);
            List<T> directChildren = List.copyOf(tree.children(parent));
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

    private static UUID requireUuid(UUID uuid) {
        if (uuid == null) throw new IllegalStateException("passenger tree contains a null UUID");
        return uuid;
    }

    private static <T> Attempt<T> failed(Snapshot<T> snapshot) {
        return new Attempt<>(false, orderedEntities(snapshot));
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
