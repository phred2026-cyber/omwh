package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public final class SpawnDestination {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    static final int HORIZONTAL_BOUND = 48;
    static final int VERTICAL_BOUND = 48;
    static final int PREPARATION_CHUNKS_PER_VISIT = 2;

    static final int PENDING_START_WORK = 1;

    record Offset(int x, int y, int z) { }
    enum Dimension { OVERWORLD, NETHER, END, OTHER }
    enum Target { CURRENT, OVERWORLD, DISABLED }
    enum Outcome { ACCEPT, VEHICLE_TOO_LARGE, UNSAFE, NO_WORLD_SPAWN }
    record Selection(Outcome outcome, Offset offset, int candidatesVisited,
                     int rootChecks, int playerChecks) { }
    record Result(Outcome outcome, DestinationSafety.Prepared destination,
                  boolean destinationPrepared, BlockPos searchAnchor) {
        Result(Outcome outcome, DestinationSafety.Prepared destination) {
            this(outcome, destination, false, null);
        }
    }
    enum ProbeKind { ROOT, PLAYER }
    enum ProbeOutcome { INCOMPLETE, FITS, REJECTED }
    record ProbeStep(ProbeOutcome outcome, int worldWork) { }
    record Tick(int candidatesStarted, int worldWork) { }
    record Plan(Result immediate, Pending pending) { }

    interface CandidateProbe {
        void begin(Offset offset, ProbeKind kind);
        ProbeStep step(int availableWorldWork);
    }

    static final class Search {
        private final Iterator<Offset> candidates;
        private final CandidateProbe probe;
        private final boolean mounted;
        private Offset active;
        private ProbeKind activeKind;
        private boolean playerCouldFit;
        private int visited;
        private int rootChecks;
        private int playerChecks;
        private Selection selection;

        Search(Iterator<Offset> candidates, CandidateProbe probe, boolean mounted) {
            this.candidates = candidates;
            this.probe = probe;
            this.mounted = mounted;
        }

        Tick tick(int candidateBudget, int worldWorkBudget) {
            if (candidateBudget <= 0 || worldWorkBudget <= 0) {
                throw new IllegalArgumentException("search budgets must be positive");
            }
            int candidatesStarted = 0;
            int worldWork = 0;
            while (selection == null && candidatesStarted < candidateBudget && worldWork < worldWorkBudget) {
                if (active == null) {
                    if (!candidates.hasNext()) {
                        selection = new Selection(playerCouldFit ? Outcome.VEHICLE_TOO_LARGE : Outcome.UNSAFE,
                                null, visited, rootChecks, playerChecks);
                        break;
                    }
                    active = candidates.next();
                    activeKind = ProbeKind.ROOT;
                    visited++;
                    rootChecks++;
                    candidatesStarted++;
                    probe.begin(active, activeKind);
                }
                ProbeStep step = probe.step(worldWorkBudget - worldWork);
                if (step.worldWork < 0 || worldWork + step.worldWork > worldWorkBudget) {
                    throw new IllegalStateException("candidate probe exceeded its world-work allowance");
                }
                worldWork += step.worldWork;
                if (step.outcome == ProbeOutcome.INCOMPLETE) break;
                if (step.outcome == ProbeOutcome.FITS) {
                    if (activeKind == ProbeKind.ROOT) {
                        selection = new Selection(Outcome.ACCEPT, active, visited, rootChecks, playerChecks);
                        break;
                    }
                    playerCouldFit = true;
                }
                if (activeKind == ProbeKind.ROOT && mounted && !playerCouldFit) {
                    activeKind = ProbeKind.PLAYER;
                    playerChecks++;
                    probe.begin(active, activeKind);
                } else {
                    active = null;
                }
            }
            return new Tick(candidatesStarted, worldWork);
        }

        boolean complete() { return selection != null; }

        Selection selection() {
            if (selection == null) throw new IllegalStateException("search is not complete");
            return selection;
        }
    }

    static final class Pending implements AutoCloseable {
        private Search search;
        private final Iterator<Offset> candidates;
        private final boolean mounted;
        private final DestinationSafety.ChunkPreparation preparation;
        private final Function<DestinationSafety.ChunkResidency, CandidateProbe> preparedProbe;
        private final ServerLevel level;
        private final BlockPos center;
        private final BlockPos searchAnchor;
        private final int rootWidth;
        private final float yaw;
        private final float pitch;
        private final Function<Vec3, DestinationSafety.ChunkPreparation> finalPreparationFactory;
        private final BiFunction<BlockPos, DestinationSafety.ChunkResidency, CandidateProbe> finalProbeFactory;
        private DestinationSafety.ChunkPreparation finalPreparation;
        private CandidateProbe revalidation;
        private DestinationSafety.Prepared destination;
        private Result result;

        private Pending(Search search, ServerLevel level, BlockPos center, BlockPos searchAnchor,
                        int rootWidth, float yaw, float pitch,
                        Function<BlockPos, DestinationSafety.SpawnProbe> freshProbe) {
            this.search = search;
            this.candidates = null;
            this.mounted = false;
            this.preparation = null;
            this.preparedProbe = null;
            this.level = level;
            this.center = center;
            this.searchAnchor = searchAnchor;
            this.rootWidth = rootWidth;
            this.yaw = yaw;
            this.pitch = pitch;
            this.finalPreparationFactory = null;
            this.finalProbeFactory = (feet, ignored) -> freshProbe.apply(feet);
        }

        private Pending(Iterator<Offset> candidates, boolean mounted,
                        DestinationSafety.ChunkPreparation preparation,
                        Function<DestinationSafety.ChunkResidency, CandidateProbe> preparedProbe,
                        ServerLevel level, BlockPos center, BlockPos searchAnchor,
                        int rootWidth, float yaw, float pitch,
                        Function<Vec3, DestinationSafety.ChunkPreparation> finalPreparationFactory,
                        BiFunction<BlockPos, DestinationSafety.ChunkResidency, CandidateProbe> finalProbeFactory) {
            this.search = null;
            this.candidates = candidates;
            this.mounted = mounted;
            this.preparation = preparation;
            this.preparedProbe = preparedProbe;
            this.level = level;
            this.center = center;
            this.searchAnchor = searchAnchor;
            this.rootWidth = rootWidth;
            this.yaw = yaw;
            this.pitch = pitch;
            this.finalPreparationFactory = finalPreparationFactory;
            this.finalProbeFactory = finalProbeFactory;
        }

        Tick tick(int candidateBudget, int worldWorkBudget) {
            if (result != null) throw new IllegalStateException("pending spawn is already complete");
            if (preparation != null && !preparation.complete()) {
                int prepared = preparation.prepare(PREPARATION_CHUNKS_PER_VISIT, worldWorkBudget);
                return new Tick(0, prepared);
            }
            if (search == null) {
                search = new Search(candidates, preparedProbe.apply(preparation.residency()), mounted);
            }
            if (!search.complete()) {
                Tick used = search.tick(candidateBudget, worldWorkBudget);
                if (!search.complete()) return used;
                Selection selection = search.selection();
                if (selection.outcome != Outcome.ACCEPT) {
                    result = new Result(selection.outcome, null);
                    return used;
                }
                BlockPos feet = feet(center, selection.offset);
                double centerOffset = rootWidth % 2 == 0 ? 0.0 : 0.5;
                destination = DestinationSafety.Prepared.ordinary(level,
                        new Vec3(feet.getX() + centerOffset, feet.getY(), feet.getZ() + centerOffset), yaw, pitch);
                if (finalPreparationFactory != null) {
                    finalPreparation = finalPreparationFactory.apply(destination.position());
                }
                return used;
            }

            if (finalPreparation != null && !finalPreparation.complete()) {
                int prepared = finalPreparation.prepare(PREPARATION_CHUNKS_PER_VISIT, worldWorkBudget);
                return new Tick(0, prepared);
            }
            if (revalidation == null) {
                BlockPos feet = BlockPos.containing(destination.position());
                DestinationSafety.ChunkResidency residency = finalPreparation == null
                        ? null : finalPreparation.residency();
                revalidation = finalProbeFactory.apply(feet, residency);
                revalidation.begin(new Offset(0, 0, 0), ProbeKind.ROOT);
            }
            ProbeStep checked = revalidation.step(worldWorkBudget);
            if (checked.outcome == ProbeOutcome.REJECTED) {
                result = new Result(Outcome.UNSAFE, null);
            } else if (checked.outcome == ProbeOutcome.FITS) {
                result = new Result(Outcome.ACCEPT, destination, true, searchAnchor);
            }
            return new Tick(0, checked.worldWork);
        }

        boolean complete() { return result != null; }

        Result result() {
            if (result == null) throw new IllegalStateException("pending spawn is not complete");
            return result;
        }

        static Pending controlled(Search search, BlockPos center, int rootWidth,
                                  Function<BlockPos, DestinationSafety.SpawnProbe> freshProbe) {
            return new Pending(search, null, center, center, rootWidth, 0, 0,
                    freshProbe);
        }

        static Pending controlledPreparing(Iterator<Offset> candidates, boolean mounted,
                                           DestinationSafety.ChunkPreparation preparation,
                                           Function<DestinationSafety.ChunkResidency, CandidateProbe> preparedProbe,
                                           BlockPos center, int rootWidth,
                                           Function<BlockPos, DestinationSafety.SpawnProbe> freshProbe) {
            return new Pending(candidates, mounted, preparation, preparedProbe,
                    null, center, center, rootWidth, 0, 0, null,
                    (feet, ignored) -> freshProbe.apply(feet));
        }

        static Pending controlledPreparing(Iterator<Offset> candidates, boolean mounted,
                                           DestinationSafety.ChunkPreparation preparation,
                                           Function<DestinationSafety.ChunkResidency, CandidateProbe> preparedProbe,
                                           BlockPos center, int rootWidth,
                                           Function<Vec3, DestinationSafety.ChunkPreparation> finalPreparationFactory,
                                           BiFunction<BlockPos, DestinationSafety.ChunkResidency, CandidateProbe> finalProbeFactory) {
            return new Pending(candidates, mounted, preparation, preparedProbe,
                    null, center, center, rootWidth, 0, 0,
                    finalPreparationFactory, finalProbeFactory);
        }

        int closeWork() {
            int work = preparation == null ? 0 : preparation.closeWork();
            return work + (finalPreparation == null ? 0 : finalPreparation.closeWork());
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            if (finalPreparation != null) {
                try {
                    finalPreparation.close();
                } catch (RuntimeException closeFailure) {
                    failure = closeFailure;
                }
            }
            if (preparation != null) {
                try {
                    preparation.close();
                } catch (RuntimeException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }

    @FunctionalInterface
    interface AnchorClamp {
        BlockPos clamp(double x, double y, double z);
    }

    private SpawnDestination() { }

    static Dimension dimension(ServerLevel level) {
        if (level.dimension().equals(Level.OVERWORLD)) return Dimension.OVERWORLD;
        if (level.dimension().equals(Level.NETHER)) return Dimension.NETHER;
        if (level.dimension().equals(Level.END)) return Dimension.END;
        return Dimension.OTHER;
    }

    static Target route(Dimension current, boolean crossDimensionEnabled,
                        boolean overworldEnabled, boolean netherEnabled, boolean endEnabled,
                        boolean moddedDimensionEnabled) {
        boolean currentEnabled = switch (current) {
            case OVERWORLD -> overworldEnabled;
            case NETHER -> netherEnabled;
            case END -> endEnabled;
            case OTHER -> moddedDimensionEnabled;
        };
        if (currentEnabled) return Target.CURRENT;
        if (current != Dimension.OVERWORLD && crossDimensionEnabled && overworldEnabled) {
            return Target.OVERWORLD;
        }
        return Target.DISABLED;
    }

    static Iterable<Offset> offsets(int horizontalBound, int verticalBound) {
        if (horizontalBound < 0 || verticalBound < horizontalBound) {
            throw new IllegalArgumentException("invalid search bounds");
        }
        return () -> new OffsetIterator(horizontalBound, verticalBound);
    }

    static boolean rootGeometrySupported(int rootWidth, int rootHeight) {
        return DestinationSafety.rootGeometrySupported(rootWidth, rootHeight);
    }

    static int searchRootWidth(int rootWidth, int rootHeight) {
        return rootGeometrySupported(rootWidth, rootHeight) ? rootWidth : 1;
    }

    static Vec3 rawPosition(BlockPos spawn) {
        return Vec3.atBottomCenterOf(spawn);
    }

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

    static BlockPos scaledAnchor(BlockPos overworldSpawn, double scale, AnchorClamp clamp) {
        Vec3 center = Vec3.atCenterOf(overworldSpawn);
        return clamp.clamp(center.x * scale, overworldSpawn.getY(), center.z * scale);
    }

    static BlockPos currentAnchor(LevelData.RespawnData spawnData, boolean nether,
                                  double scale, AnchorClamp clamp) {
        return currentAnchor(spawnData == null ? null : spawnData.pos(), nether, scale, clamp);
    }

    static BlockPos currentAnchor(BlockPos overworldSpawn, boolean nether,
                                  double scale, AnchorClamp clamp) {
        if (overworldSpawn == null) return null;
        return nether ? scaledAnchor(overworldSpawn, scale, clamp) : overworldSpawn;
    }

    static boolean matchesSearchAnchor(BlockPos searchAnchor, BlockPos currentAnchor) {
        return searchAnchor != null && searchAnchor.equals(currentAnchor);
    }

    static BlockPos currentAnchor(ServerLevel level) {
        LevelData.RespawnData spawnData = readSpawnData(
                () -> level.getServer().overworld().getRespawnData(),
                failure -> LOGGER.error("Could not re-read Overworld spawn for OMWH /spawn", failure));
        boolean nether = level.dimension().equals(Level.NETHER);
        double scale = nether ? DimensionType.getTeleportationScale(
                level.getServer().overworld().dimensionType(), level.dimensionType()) : 1.0;
        return currentAnchor(spawnData, nether, scale, level.getWorldBorder()::clampToBounds);
    }

    static Outcome acceptEnd(boolean force, BooleanSupplier rootFits, BooleanSupplier playerFits) {
        return acceptEnd(force, 1, 2, rootFits, playerFits);
    }

    static Outcome acceptEnd(boolean force, int rootWidth, int rootHeight,
                             BooleanSupplier rootFits, BooleanSupplier playerFits) {
        if (force) return Outcome.ACCEPT;
        if (!rootGeometrySupported(rootWidth, rootHeight)) return Outcome.VEHICLE_TOO_LARGE;
        if (rootFits.getAsBoolean()) return Outcome.ACCEPT;
        return playerFits != null && playerFits.getAsBoolean()
                ? Outcome.VEHICLE_TOO_LARGE : Outcome.UNSAFE;
    }

    static Plan plan(ServerPlayer player, ServerLevel level, boolean force) {
        if (level.dimension().equals(Level.END)) return new Plan(findEnd(player, level, force), null);

        LevelData.RespawnData spawnData = readSpawnData(
                () -> level.getServer().overworld().getRespawnData(),
                failure -> LOGGER.error("Could not read Overworld spawn for OMWH /spawn", failure));
        if (spawnData == null || spawnData.pos() == null) {
            return new Plan(new Result(Outcome.NO_WORLD_SPAWN, null), null);
        }

        boolean nether = level.dimension().equals(Level.NETHER);
        double scale = nether ? DimensionType.getTeleportationScale(
                level.getServer().overworld().dimensionType(), level.dimensionType()) : 1.0;
        BlockPos anchor = currentAnchor(spawnData, nether, scale, level.getWorldBorder()::clampToBounds);

        Entity root = player.getRootVehicle();
        if (force) {
            return new Plan(accepted(DestinationSafety.Prepared.ordinary(
                    level, rawPosition(anchor), root.getYRot(), root.getXRot())), null);
        }

        int rootWidth = root == player ? 1 : (int) Math.max(1, Math.ceil(root.getBbWidth()));
        int rootHeight = root == player ? 2 : (int) Math.max(3, Math.ceil(root.getBbHeight()) + 2);
        boolean rootGeometrySupported = rootGeometrySupported(rootWidth, rootHeight);
        int residencyWidth = searchRootWidth(rootWidth, rootHeight);
        BlockPos center = anchor;
        int halfWidth = (residencyWidth + 1) / 2;
        DestinationSafety.ChunkPreparation preparation = DestinationSafety.ChunkPreparation.forLevel(
                level,
                center.getX() - HORIZONTAL_BOUND - halfWidth - 1,
                center.getX() + HORIZONTAL_BOUND + halfWidth + 1,
                center.getZ() - HORIZONTAL_BOUND - halfWidth - 1,
                center.getZ() + HORIZONTAL_BOUND + halfWidth + 1);
        return new Plan(null, new Pending(offsets(HORIZONTAL_BOUND, VERTICAL_BOUND).iterator(),
                root != player, preparation,
                residency -> new DestinationSafety.SpawnProbe(
                        level, center, rootWidth, rootHeight, rootGeometrySupported, residency),
                level, center, anchor, rootWidth, root.getYRot(), root.getXRot(),
                position -> DestinationSafety.ChunkPreparation.forDestination(level, position),
                (feet, residency) -> new DestinationSafety.SpawnProbe(
                        level, feet, rootWidth, rootHeight, true, residency)));
    }

    private static Result findEnd(ServerPlayer player, ServerLevel endLevel, boolean force) {
        Entity root = player.getRootVehicle();
        DestinationSafety.RootGeometry geometry = DestinationSafety.rootGeometry(root);
        TeleportTransition transition = ((Portal) Blocks.END_PORTAL).getPortalDestination(
                endLevel.getServer().overworld(), root, BlockPos.ZERO);
        if (transition == null) return new Result(Outcome.NO_WORLD_SPAWN, null);

        Vec3 playerPosition = Vec3.atBottomCenterOf(ServerLevel.END_SPAWN_POINT).subtract(0, 1, 0);
        Outcome outcome = acceptEnd(force, geometry.width(), geometry.clearHeight(),
                () -> DestinationSafety.endFits(transition.newLevel(), root, transition.position()),
                root == player ? null
                        : () -> DestinationSafety.endFits(transition.newLevel(), player, playerPosition));
        return outcome == Outcome.ACCEPT
                ? accepted(new DestinationSafety.Prepared(transition))
                : new Result(outcome, null);
    }

    private static Result accepted(DestinationSafety.Prepared destination) {
        return new Result(Outcome.ACCEPT, destination);
    }

    private static BlockPos feet(BlockPos center, Offset offset) {
        return center.offset(offset.x, offset.y, offset.z);
    }

    /** Expands by Chebyshev radius and emits x, then y, then z lexicographically within each shell. */
    private static final class OffsetIterator implements Iterator<Offset> {
        private final int horizontalBound;
        private final int verticalBound;
        private int radius;
        private int x;
        private int y;
        private int z;
        private boolean available = true;

        private OffsetIterator(int horizontalBound, int verticalBound) {
            this.horizontalBound = horizontalBound;
            this.verticalBound = verticalBound;
        }

        @Override
        public boolean hasNext() {
            return available;
        }

        @Override
        public Offset next() {
            if (!available) throw new NoSuchElementException();
            Offset result = new Offset(x, y, z);
            advance();
            return result;
        }

        private void advance() {
            if (radius == 0) {
                if (verticalBound == 0) {
                    available = false;
                    return;
                }
                radius = 1;
                initializeShell();
                return;
            }

            int horizontalRadius = Math.min(radius, horizontalBound);
            boolean fullZRange = Math.abs(x) == radius || Math.abs(y) == radius;
            if (fullZRange && z < horizontalRadius) {
                z++;
                return;
            }
            if (!fullZRange && z == -radius) {
                z = radius;
                return;
            }

            if (radius > horizontalBound) {
                if (y == -radius) {
                    y = radius;
                    z = -horizontalRadius;
                    return;
                }
            } else if (y < radius) {
                y++;
                z = -horizontalRadius;
                return;
            }

            if (x < horizontalRadius) {
                x++;
                y = -radius;
                z = -horizontalRadius;
                return;
            }

            radius++;
            if (radius > verticalBound) {
                available = false;
                return;
            }
            initializeShell();
        }

        private void initializeShell() {
            int horizontalRadius = Math.min(radius, horizontalBound);
            x = -horizontalRadius;
            y = -radius;
            z = -horizontalRadius;
        }
    }
}
