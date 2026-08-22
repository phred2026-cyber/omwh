package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class SpawnDestination {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    static final int RADIUS = 64;
    static final int MIN_Y_OFFSET = -2;
    static final int MAX_Y_OFFSET = 10;
    static final int MAX_CANDIDATES = 4_096;
    static final int HORIZONTAL_COLUMNS = (RADIUS * 2 + 1) * (RADIUS * 2 + 1);
    private static final List<HorizontalOffset> PRODUCTION_HORIZONTAL = horizontalOffsets(RADIUS);

    record Offset(int x, int y, int z) { }

    record EndPlatform(BlockPos platformAnchor, Vec3 feet, float yaw, float pitch) { }
    enum Dimension { OVERWORLD, NETHER, END, OTHER }
    enum Target { CURRENT, OVERWORLD, DISABLED }
    enum Outcome { ACCEPT, VEHICLE_TOO_LARGE, UNSAFE, NO_WORLD_SPAWN }
    record Selection(Outcome outcome, Offset offset, int columnsVisited, int candidatesVisited,
                     int rootChecks, int playerChecks) { }
    record Result(Outcome outcome, DestinationSafety.Prepared destination) { }
    private record HorizontalOffset(int x, int z, long distanceSquared) { }

    private SpawnDestination() { }

    static Dimension dimension(ServerLevel level) {
        if (level.dimension().equals(Level.OVERWORLD)) return Dimension.OVERWORLD;
        if (level.dimension().equals(Level.NETHER)) return Dimension.NETHER;
        if (level.dimension().equals(Level.END)) return Dimension.END;
        return Dimension.OTHER;
    }

    static Target route(Dimension current, boolean crossDimensionEnabled,
                        boolean overworldEnabled, boolean netherEnabled, boolean endEnabled) {
        boolean currentEnabled = switch (current) {
            case OVERWORLD -> overworldEnabled;
            case NETHER -> netherEnabled;
            case END -> endEnabled;
            case OTHER -> false;
        };
        if (currentEnabled) return Target.CURRENT;
        if ((current == Dimension.NETHER || current == Dimension.END)
                && crossDimensionEnabled && overworldEnabled) {
            return Target.OVERWORLD;
        }
        return Target.DISABLED;
    }

    static Iterable<Offset> offsets(int radius, int minY, int maxY) {
        if (radius < 0 || minY > maxY) throw new IllegalArgumentException("invalid search bounds");
        List<HorizontalOffset> horizontal = radius == RADIUS ? PRODUCTION_HORIZONTAL : horizontalOffsets(radius);
        return () -> new OffsetIterator(horizontal, verticalOffsets(minY, maxY));
    }

    static Vec3 rawPosition(BlockPos spawn) { return Vec3.atBottomCenterOf(spawn); }

    static <T> T readSpawnData(Supplier<T> reader, Consumer<RuntimeException> failureHandler) {
        try {
            return reader.get();
        } catch (RuntimeException failure) {
            failureHandler.accept(failure);
            return null;
        }
    }

    static BlockPos readSpawnCenter(Supplier<BlockPos> reader, Consumer<RuntimeException> failureHandler) {
        return readSpawnData(reader, failureHandler);
    }

    static BlockPos searchCenter(BlockPos spawn, boolean spawnDimensionMatches, Dimension targetDimension,
                                 int seaLevel, int minY, int logicalHeight) {
        if (spawnDimensionMatches || targetDimension != Dimension.NETHER) return spawn;
        int minimumFeetY = minY + 1;
        int maximumFeetY = minY + logicalHeight - 1;
        int anchoredY = Math.max(minimumFeetY, Math.min(maximumFeetY, seaLevel));
        return new BlockPos(spawn.getX(), anchoredY, spawn.getZ());
    }

    static EndPlatform endPlatform(BlockPos spawn, float westYaw) {
        Vec3 vanillaPosition = Vec3.atBottomCenterOf(spawn);
        return new EndPlatform(BlockPos.containing(vanillaPosition).below(),
                vanillaPosition.subtract(0, 1, 0), westYaw, 0.0f);
    }

    static Outcome acceptEnd(boolean rebuildPlatform, BooleanSupplier rootFits,
                             BooleanSupplier playerFits, Runnable createPlatform) {
        if (!rootFits.getAsBoolean()) {
            return playerFits != null && playerFits.getAsBoolean() ? Outcome.VEHICLE_TOO_LARGE : Outcome.UNSAFE;
        }
        createEndPlatformIfEnabled(rebuildPlatform, createPlatform);
        return Outcome.ACCEPT;
    }

    static void createEndPlatformIfEnabled(boolean rebuildPlatform, Runnable createPlatform) {
        if (rebuildPlatform) createPlatform.run();
    }

    static Selection select(Iterable<Offset> candidates, Predicate<Offset> rootFits,
                            Predicate<Offset> playerFits) {
        return select(candidates, MAX_CANDIDATES, rootFits, playerFits);
    }

    static Selection select(Iterable<Offset> candidates, int maxCandidates,
                            Predicate<Offset> rootFits, Predicate<Offset> playerFits) {
        if (maxCandidates < 0) throw new IllegalArgumentException("maxCandidates must be nonnegative");
        int columnsVisited = 0;
        int visited = 0;
        int rootChecks = 0;
        int playerChecks = 0;
        boolean playerCouldFit = false;
        int previousX = 0;
        int previousZ = 0;
        boolean first = true;
        for (Offset candidate : candidates) {
            boolean newColumn = first || candidate.x != previousX || candidate.z != previousZ;
            if (newColumn) {
                if (columnsVisited >= maxCandidates) break;
                columnsVisited++;
                previousX = candidate.x;
                previousZ = candidate.z;
                first = false;
            }
            visited++;
            rootChecks++;
            if (rootFits.test(candidate)) {
                return new Selection(Outcome.ACCEPT, candidate, columnsVisited, visited,
                        rootChecks, playerChecks);
            }
            if (playerFits != null) {
                playerChecks++;
                playerCouldFit |= playerFits.test(candidate);
            }
        }
        return new Selection(playerCouldFit ? Outcome.VEHICLE_TOO_LARGE : Outcome.UNSAFE,
                null, columnsVisited, visited, rootChecks, playerChecks);
    }

    static Result find(ServerPlayer player, ServerLevel level, boolean force, boolean rebuildEndPlatform) {
        Entity root = player.getRootVehicle();
        int rootWidth = root == player ? 1 : (int) Math.max(1, Math.ceil(root.getBbWidth()));
        int rootHeight = root == player ? 2 : (int) Math.max(3, Math.ceil(root.getBbHeight()) + 2);
        if (level.dimension().equals(Level.END)) {
            return findEnd(level, root, player, force, rebuildEndPlatform);
        }

        LevelData.RespawnData spawnData = readSpawnData(level::getRespawnData, failure ->
                LOGGER.error("Could not read world spawn for OMWH /spawn", failure));
        if (spawnData == null || spawnData.pos() == null) {
            return new Result(Outcome.NO_WORLD_SPAWN, null);
        }
        BlockPos rawCenter = spawnData.pos();
        if (force) {
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                    rawPosition(rawCenter), root.getYRot(), root.getXRot()));
        }

        Dimension targetDimension = dimension(level);
        boolean spawnDimensionMatches = spawnData.dimension().equals(level.dimension());
        BlockPos center = searchCenter(rawCenter, spawnDimensionMatches, targetDimension,
                level.getSeaLevel(), level.getMinY(), level.getLogicalHeight());
        Selection selection = targetDimension == Dimension.NETHER && !spawnDimensionMatches
                ? selectLoaded(level, center, rootWidth, rootHeight, root,
                        offsets(RADIUS, 0, 0), HORIZONTAL_COLUMNS)
                : null;
        if (selection == null || selection.outcome() != Outcome.ACCEPT) {
            Selection fallback = selectLoaded(level, center, rootWidth, rootHeight, root,
                    offsets(RADIUS, MIN_Y_OFFSET, MAX_Y_OFFSET), MAX_CANDIDATES);
            if (fallback.outcome() == Outcome.ACCEPT
                    || selection == null
                    || selection.outcome() != Outcome.VEHICLE_TOO_LARGE) {
                selection = fallback;
            }
        }
        if (selection.outcome != Outcome.ACCEPT) return new Result(selection.outcome, null);

        BlockPos feet = feet(center, selection.offset);
        double centerOffset = rootWidth % 2 == 0 ? 0.0 : 0.5;
        return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                new Vec3(feet.getX() + centerOffset, feet.getY(), feet.getZ() + centerOffset),
                root.getYRot(), root.getXRot()));
    }

    private static Selection selectLoaded(ServerLevel level, BlockPos center,
                                          int rootWidth, int rootHeight, Entity root,
                                          Iterable<Offset> candidates, int maxColumns) {
        return select(candidates, maxColumns,
                offset -> DestinationSafety.spawnFits(
                        level, feet(center, offset), rootWidth, rootHeight),
                root instanceof ServerPlayer ? null
                        : offset -> DestinationSafety.spawnFits(
                                level, feet(center, offset), 1, 2));
    }

    private static Result findEnd(ServerLevel level, Entity root, ServerPlayer player,
                                  boolean force, boolean rebuildPlatform) {
        EndPlatform platform = endPlatform(ServerLevel.END_SPAWN_POINT, Direction.WEST.toYRot());
        if (force) {
            createEndPlatformIfEnabled(rebuildPlatform,
                    () -> EndPlatformFeature.createEndPlatform(level, platform.platformAnchor, true));
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                    platform.feet, platform.yaw, platform.pitch));
        }
        Outcome outcome = acceptEnd(rebuildPlatform,
                () -> DestinationSafety.endFits(
                        level, root, platform.feet, platform.platformAnchor, rebuildPlatform),
                root == player ? null : () -> DestinationSafety.endFits(
                        level, player, platform.feet, platform.platformAnchor, rebuildPlatform),
                () -> EndPlatformFeature.createEndPlatform(level, platform.platformAnchor, true));
        if (outcome != Outcome.ACCEPT) return new Result(outcome, null);
        return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                platform.feet,
                platform.yaw, platform.pitch));
    }

    private static BlockPos feet(BlockPos center, Offset offset) {
        return center.offset(offset.x, offset.y, offset.z);
    }

    private static List<HorizontalOffset> horizontalOffsets(int radius) {
        List<HorizontalOffset> horizontal = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                horizontal.add(new HorizontalOffset(x, z, (long) x * x + (long) z * z));
            }
        }
        horizontal.sort(Comparator.comparingLong(HorizontalOffset::distanceSquared)
                .thenComparingInt(HorizontalOffset::x).thenComparingInt(HorizontalOffset::z));
        return List.copyOf(horizontal);
    }

    private static List<Integer> verticalOffsets(int minY, int maxY) {
        List<Integer> vertical = new ArrayList<>(maxY - minY + 1);
        for (int y = minY; y <= maxY; y++) vertical.add(y);
        vertical.sort(Comparator.comparingInt((Integer y) -> Math.abs(y)).thenComparingInt(y -> y));
        return List.copyOf(vertical);
    }

    private static final class OffsetIterator implements Iterator<Offset> {
        private final List<HorizontalOffset> horizontal;
        private final List<Integer> vertical;
        private int horizontalIndex;
        private int verticalIndex;

        private OffsetIterator(List<HorizontalOffset> horizontal, List<Integer> vertical) {
            this.horizontal = horizontal;
            this.vertical = vertical;
        }

        @Override
        public boolean hasNext() {
            return horizontalIndex < horizontal.size();
        }

        @Override
        public Offset next() {
            if (!hasNext()) throw new NoSuchElementException();
            HorizontalOffset column = horizontal.get(horizontalIndex);
            Offset result = new Offset(column.x, vertical.get(verticalIndex), column.z);
            verticalIndex++;
            if (verticalIndex == vertical.size()) {
                verticalIndex = 0;
                horizontalIndex++;
            }
            return result;
        }
    }
}
