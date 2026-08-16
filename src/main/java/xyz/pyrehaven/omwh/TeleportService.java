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
import java.util.function.Predicate;

public final class TeleportService {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");

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
                sourceLevel == destination.level(), Entity::getUUID, Entity::getPassengers,
                Entity::getVehicle, entity -> entity instanceof ServerPlayer,
                entity -> entity.level() instanceof ServerLevel, Entity::isRemoved, Entity::level,
                entity -> entity.teleport(new TeleportTransition(destination.level(), destination.position(),
                        Vec3.ZERO, destination.yaw(), destination.pitch(), TeleportTransition.DO_NOTHING)),
                entity -> entity.setDeltaMovement(Vec3.ZERO));
        if (!attempt.success()) return new Result(false, List.of());
        List<ServerPlayer> passengers = passengerPlayers(attempt.entities(),
                        entity -> entity instanceof ServerPlayer, source).stream()
                .map(entity -> (ServerPlayer) entity)
                .toList();
        return new Result(true, passengers);
    }

    static <T> List<T> passengerPlayers(List<T> movedEntities, Predicate<T> player, T commandPlayer) {
        return movedEntities.stream()
                .filter(entity -> player.test(entity) && entity != commandPlayer)
                .toList();
    }

    static <T> Attempt<T> attempt(T root, Object sourceLevel, Object destinationLevel, boolean sameDimension,
                                  Function<T, UUID> uuid, Function<T, List<T>> children,
                                  Function<T, T> currentParent, Predicate<T> player,
                                  Predicate<T> serverEntity, Predicate<T> removed,
                                  Function<T, Object> level, Function<T, T> teleporter,
                                  Consumer<T> clearVelocity) {
        try {
            Snapshot<T> expected = snapshot(root, uuid, children, player);
            for (T entity : expected.entities().values()) {
                if (!serverEntity.test(entity) || removed.test(entity) || level.apply(entity) != sourceLevel) {
                    LOGGER.error("OMWH refused an invalid source passenger tree");
                    return failed(expected);
                }
            }

            T movedRoot = teleporter.apply(root);
            if (movedRoot == null) {
                LOGGER.error("Minecraft returned no root entity from OMWH teleport");
                return failed(expected);
            }

            Snapshot<T> moved = snapshot(movedRoot, uuid, children, player);
            if (!expected.rootUuid().equals(moved.rootUuid())
                    || !expected.entities().keySet().equals(moved.entities().keySet())
                    || !expected.parents().equals(moved.parents())) {
                LOGGER.error("Minecraft returned an incomplete or changed OMWH passenger tree");
                return failed(moved);
            }

            for (Map.Entry<UUID, T> entry : moved.entities().entrySet()) {
                UUID entityUuid = entry.getKey();
                T entity = entry.getValue();
                if (!serverEntity.test(entity) || removed.test(entity) || level.apply(entity) != destinationLevel) {
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
                if (currentParent.apply(movedChild) != movedParent) {
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

    private static <T> Snapshot<T> snapshot(T root, Function<T, UUID> uuid,
                                             Function<T, List<T>> children, Predicate<T> player) {
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
            UUID parentUuid = requireUuid(uuid.apply(parent));
            if (entities.put(parentUuid, parent) != null) {
                throw new IllegalStateException("passenger tree contains duplicate UUID " + parentUuid);
            }
            order.add(parentUuid);
            List<T> directChildren = List.copyOf(children.apply(parent));
            if (player.test(parent)) {
                players.put(parentUuid, parent);
                if (!directChildren.isEmpty()) {
                    throw new IllegalStateException("ServerPlayer passenger nodes are unsupported");
                }
            }
            for (T child : directChildren) {
                if (identities.put(child, Boolean.TRUE) != null) {
                    throw new IllegalStateException("passenger tree contains a cycle or duplicate entity");
                }
                UUID childUuid = requireUuid(uuid.apply(child));
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
}
