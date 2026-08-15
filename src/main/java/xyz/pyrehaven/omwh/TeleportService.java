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
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public final class TeleportService {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");

    record Attempt<T>(boolean success, List<T> entities) { }
    record Result(boolean success, List<ServerPlayer> passengerPlayers) { }
    private record Edge<T>(T parent, T child) { }

    private TeleportService() { }

    static Result teleport(ServerPlayer source, DestinationSafety.Prepared destination) {
        Entity root = source.getRootVehicle();
        if (!(root.level() instanceof ServerLevel current)
                || !current.dimension().equals(destination.level().dimension())) {
            LOGGER.error("OMWH refused a cross-dimension mutation after destination preparation");
            return new Result(false, List.of());
        }
        Attempt<Entity> attempt = attempt(root, Entity::getPassengers, Entity::getVehicle,
                entity -> entity.teleport(new TeleportTransition(destination.level(), destination.position(),
                        Vec3.ZERO, destination.yaw(), destination.pitch(), TeleportTransition.DO_NOTHING)),
                entity -> entity.setDeltaMovement(Vec3.ZERO));
        if (!attempt.success()) return new Result(false, List.of());
        List<ServerPlayer> passengers = attempt.entities().stream()
                .filter(entity -> entity instanceof ServerPlayer && entity != source)
                .map(entity -> (ServerPlayer) entity)
                .toList();
        return new Result(true, passengers);
    }

    static <T> Attempt<T> attempt(T root, Function<T, List<T>> children,
                                  Function<T, T> currentParent, Function<T, T> teleporter,
                                  Consumer<T> clearVelocity) {
        List<T> entities = new ArrayList<>();
        List<Edge<T>> edges = new ArrayList<>();
        Deque<T> queue = new ArrayDeque<>();
        Map<T, Boolean> seen = new IdentityHashMap<>();
        queue.add(root);
        seen.put(root, Boolean.TRUE);
        while (!queue.isEmpty()) {
            T parent = queue.removeFirst();
            entities.add(parent);
            for (T child : children.apply(parent)) {
                if (seen.put(child, Boolean.TRUE) != null) {
                    throw new IllegalStateException("passenger tree contains a cycle");
                }
                edges.add(new Edge<>(parent, child));
                queue.addLast(child);
            }
        }

        try {
            T moved = teleporter.apply(root);
            if (moved == null) {
                LOGGER.error("Minecraft returned no root entity from OMWH teleport");
                return new Attempt<>(false, List.copyOf(entities));
            }
            for (Edge<T> edge : edges) {
                if (currentParent.apply(edge.child) != edge.parent) {
                    LOGGER.error("Minecraft changed the OMWH passenger tree during teleport");
                    return new Attempt<>(false, List.copyOf(entities));
                }
            }
            clearVelocity.accept(moved);
            return new Attempt<>(true, List.copyOf(entities));
        } catch (RuntimeException exception) {
            LOGGER.error("OMWH teleport mutation failed", exception);
            return new Attempt<>(false, List.copyOf(entities));
        }
    }
}
