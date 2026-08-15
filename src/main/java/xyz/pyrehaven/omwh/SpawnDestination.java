package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.feature.EndPlatformFeature;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public final class SpawnDestination {
    static final int RADIUS = 64;
    static final int MIN_Y_OFFSET = -2;
    static final int MAX_Y_OFFSET = 10;
    private static final List<HorizontalOffset> PRODUCTION_HORIZONTAL = horizontalOffsets(RADIUS);
    private static final Comparator<Offset> OFFSET_ORDER = Comparator.comparingLong(Offset::distanceSquared)
            .thenComparingInt(offset -> Math.abs(offset.y))
            .thenComparingInt(Offset::y).thenComparingInt(Offset::x).thenComparingInt(Offset::z);

    record Offset(int x, int y, int z) {
        long distanceSquared() { return (long) x * x + (long) y * y + (long) z * z; }
    }
    record Center(int x, int y, int z) { }
    record EndPlatform(BlockPos platformAnchor, Vec3 feet, float yaw, float pitch) { }
    enum Outcome { ACCEPT, VEHICLE_TOO_LARGE, UNSAFE, NO_WORLD_SPAWN }
    record Selection(Outcome outcome, Offset offset) { }
    record Result(Outcome outcome, DestinationSafety.Prepared destination) { }
    private record HorizontalOffset(int x, int z, long distanceSquared) { }

    private SpawnDestination() { }

    static Iterable<Offset> offsets(int radius, int minY, int maxY) {
        if (radius < 0 || minY > maxY) throw new IllegalArgumentException("invalid search bounds");
        List<HorizontalOffset> horizontal = radius == RADIUS ? PRODUCTION_HORIZONTAL : horizontalOffsets(radius);
        return () -> new OffsetIterator(horizontal, minY, maxY);
    }

    static Center fallbackCenter() { return new Center(0, 64, 0); }

    static Vec3 rawPosition(BlockPos spawn) { return Vec3.atBottomCenterOf(spawn); }

    static EndPlatform endPlatform(BlockPos spawn, float westYaw) {
        Vec3 vanillaPosition = Vec3.atBottomCenterOf(spawn);
        return new EndPlatform(BlockPos.containing(vanillaPosition).below(),
                vanillaPosition.subtract(0, 1, 0), westYaw, 0.0f);
    }

    static Outcome acceptEnd(BooleanSupplier rootFits, BooleanSupplier playerFits, Runnable createPlatform) {
        if (!rootFits.getAsBoolean()) {
            return playerFits != null && playerFits.getAsBoolean() ? Outcome.VEHICLE_TOO_LARGE : Outcome.UNSAFE;
        }
        createPlatform.run();
        return Outcome.ACCEPT;
    }

    static Selection select(Iterable<Offset> candidates, Predicate<Offset> rootFits,
                            Predicate<Offset> playerFits) {
        for (Offset candidate : candidates) {
            if (rootFits.test(candidate)) return new Selection(Outcome.ACCEPT, candidate);
        }
        if (playerFits != null) {
            for (Offset candidate : candidates) {
                if (playerFits.test(candidate)) return new Selection(Outcome.VEHICLE_TOO_LARGE, null);
            }
        }
        return new Selection(Outcome.UNSAFE, null);
    }

    static Result find(ServerPlayer player, ServerLevel level, boolean force) {
        Entity root = player.getRootVehicle();
        int rootWidth = root == player ? 1 : (int) Math.max(1, Math.ceil(root.getBbWidth()));
        int rootHeight = root == player ? 2 : (int) Math.max(3, Math.ceil(root.getBbHeight()) + 2);
        if (level.dimension().equals(Level.END)) return findEnd(level, root, player, force);

        BlockPos center;
        try {
            center = level.getRespawnData().pos();
            if (center == null) return new Result(Outcome.NO_WORLD_SPAWN, null);
        } catch (RuntimeException spawnReadFailure) {
            Center fallback = fallbackCenter();
            center = new BlockPos(fallback.x, fallback.y, fallback.z);
        }
        if (force) {
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                    rawPosition(center), root.getYRot(), root.getXRot()));
        }

        BlockPos resolvedCenter = center;
        Iterable<Offset> candidates = offsets(RADIUS, MIN_Y_OFFSET, MAX_Y_OFFSET);
        Set<Long> loadedChunks = new HashSet<>();
        Selection selection = select(candidates,
                offset -> DestinationSafety.spawnFits(
                        level, feet(resolvedCenter, offset), rootWidth, rootHeight, loadedChunks),
                root == player ? null
                        : offset -> DestinationSafety.spawnFits(
                                level, feet(resolvedCenter, offset), 1, 2, loadedChunks));
        if (selection.outcome != Outcome.ACCEPT) return new Result(selection.outcome, null);

        BlockPos feet = feet(resolvedCenter, selection.offset);
        double centerOffset = rootWidth % 2 == 0 ? 0.0 : 0.5;
        return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                new Vec3(feet.getX() + centerOffset, feet.getY(), feet.getZ() + centerOffset),
                root.getYRot(), root.getXRot()));
    }

    private static Result findEnd(ServerLevel level, Entity root, ServerPlayer player, boolean force) {
        EndPlatform platform = endPlatform(ServerLevel.END_SPAWN_POINT, Direction.WEST.toYRot());
        if (force) {
            EndPlatformFeature.createEndPlatform(level, platform.platformAnchor, true);
            return new Result(Outcome.ACCEPT, new DestinationSafety.Prepared(level,
                    platform.feet, platform.yaw, platform.pitch));
        }
        Set<Long> loadedChunks = new HashSet<>();
        Outcome outcome = acceptEnd(
                () -> DestinationSafety.endFits(
                        level, root, platform.feet, platform.platformAnchor, loadedChunks),
                root == player ? null : () -> DestinationSafety.endFits(
                        level, player, platform.feet, platform.platformAnchor, loadedChunks),
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

    private static final class OffsetIterator implements Iterator<Offset> {
        private final PriorityQueue<Cursor> cursors = new PriorityQueue<>((left, right) ->
                OFFSET_ORDER.compare(left.current(), right.current()));

        private OffsetIterator(List<HorizontalOffset> horizontal, int minY, int maxY) {
            for (int y = minY; y <= maxY; y++) cursors.add(new Cursor(horizontal, y));
        }

        @Override
        public boolean hasNext() {
            return !cursors.isEmpty();
        }

        @Override
        public Offset next() {
            if (cursors.isEmpty()) throw new NoSuchElementException();
            Cursor cursor = cursors.remove();
            Offset result = cursor.current();
            if (cursor.advance()) cursors.add(cursor);
            return result;
        }
    }

    private static final class Cursor {
        private final List<HorizontalOffset> horizontal;
        private final int y;
        private int index;

        private Cursor(List<HorizontalOffset> horizontal, int y) {
            this.horizontal = horizontal;
            this.y = y;
        }

        private Offset current() {
            HorizontalOffset offset = horizontal.get(index);
            return new Offset(offset.x, y, offset.z);
        }

        private boolean advance() {
            index++;
            return index < horizontal.size();
        }
    }
}
