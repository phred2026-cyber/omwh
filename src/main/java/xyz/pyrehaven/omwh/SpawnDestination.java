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
    static final int SEARCH_BOUND = 48;
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

    private static final class PreparingCandidateProbe implements CandidateProbe {
        private final DestinationSafety.ChunkPreparation preparation;
        private final CandidateProbe delegate;
        private final BlockPos center;
        private final int rootWidth;
        private Offset active;
        private ProbeKind kind;
        private boolean delegateStarted;

        private PreparingCandidateProbe(DestinationSafety.ChunkPreparation preparation,
                                        CandidateProbe delegate, BlockPos center, int rootWidth) {
            this.preparation = preparation;
            this.delegate = delegate;
            this.center = center;
            this.rootWidth = rootWidth;
        }

        @Override
        public void begin(Offset offset, ProbeKind kind) {
            this.active = offset;
            this.kind = kind;
            this.delegateStarted = false;
            preparation.requireCandidate(center, offset, kind == ProbeKind.ROOT ? rootWidth : 1);
        }

        @Override
        public ProbeStep step(int availableWorldWork) {
            int prepared = preparation.prepare(PREPARATION_CHUNKS_PER_VISIT, availableWorldWork);
            int width = kind == ProbeKind.ROOT ? rootWidth : 1;
            if (!preparation.candidateReady(center, active, width)) {
                return new ProbeStep(ProbeOutcome.INCOMPLETE, prepared);
            }
            if (!delegateStarted) {
                delegate.begin(active, kind);
                delegateStarted = true;
            }
            int remaining = availableWorldWork - prepared;
            if (remaining <= 0) return new ProbeStep(ProbeOutcome.INCOMPLETE, prepared);
            ProbeStep checked = delegate.step(remaining);
            return new ProbeStep(checked.outcome(), prepared + checked.worldWork());
        }
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
            // A candidate remains active until its root probe finishes and, only when needed,
            // its player diagnostic finishes. Budgets may pause either probe but never skip or
            // reorder the active offset; visited therefore counts each offset exactly once.
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
                if (step.worldWork() < 0 || worldWork + step.worldWork() > worldWorkBudget) {
                    throw new IllegalStateException("candidate probe exceeded its world-work allowance");
                }
                worldWork += step.worldWork();
                if (step.outcome() == ProbeOutcome.INCOMPLETE) break;
                if (step.outcome() == ProbeOutcome.FITS) {
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

    interface SearchStage extends AutoCloseable {
        Tick tick(int candidateBudget, int worldWorkBudget);
        boolean complete();
        Selection selection();
        default int closeWork() { return 0; }
        @Override default void close() { }
    }

    static final class DirectSearchStage implements SearchStage {
        private final Search search;

        DirectSearchStage(Search search) { this.search = search; }
        @Override public Tick tick(int candidateBudget, int worldWorkBudget) {
            return search.tick(candidateBudget, worldWorkBudget);
        }
        @Override public boolean complete() { return search.complete(); }
        @Override public Selection selection() { return search.selection(); }
    }

    static final class PreparedSearchStage implements SearchStage {
        private final Iterator<Offset> candidates;
        private final boolean mounted;
        private final DestinationSafety.ChunkPreparation preparation;
        private final Function<DestinationSafety.ChunkResidency, CandidateProbe> probeFactory;
        private final BlockPos center;
        private final int rootWidth;
        private Search search;

        PreparedSearchStage(Iterator<Offset> candidates, boolean mounted,
                            DestinationSafety.ChunkPreparation preparation,
                            Function<DestinationSafety.ChunkResidency, CandidateProbe> probeFactory,
                            BlockPos center, int rootWidth) {
            this.candidates = candidates;
            this.mounted = mounted;
            this.preparation = preparation;
            this.probeFactory = probeFactory;
            this.center = center;
            this.rootWidth = rootWidth;
        }

        @Override
        public Tick tick(int candidateBudget, int worldWorkBudget) {
            if (!preparation.expandable() && !preparation.complete()) {
                return new Tick(0, preparation.prepare(PREPARATION_CHUNKS_PER_VISIT, worldWorkBudget));
            }
            if (search == null) {
                DestinationSafety.ChunkResidency residency = preparation.expandable()
                        ? preparation.liveResidency() : preparation.residency();
                CandidateProbe probe = probeFactory.apply(residency);
                if (preparation.expandable()) {
                    probe = new PreparingCandidateProbe(preparation, probe, center, rootWidth);
                }
                search = new Search(candidates, probe, mounted);
            }
            return search.tick(candidateBudget, worldWorkBudget);
        }

        @Override public boolean complete() { return search != null && search.complete(); }
        @Override public Selection selection() {
            if (search == null) throw new IllegalStateException("search has not started");
            return search.selection();
        }
        @Override public int closeWork() { return preparation.closeWork(); }
        @Override public void close() { preparation.close(); }
    }

    interface FinalStage extends AutoCloseable {
        void begin(DestinationSafety.Prepared destination);
        ProbeStep tick(int worldWorkBudget);
        default int closeWork() { return 0; }
        @Override default void close() { }
    }

    static final class DirectFinalStage implements FinalStage {
        private final Function<BlockPos, CandidateProbe> probeFactory;
        private CandidateProbe probe;

        DirectFinalStage(Function<BlockPos, CandidateProbe> probeFactory) {
            this.probeFactory = probeFactory;
        }
        @Override public void begin(DestinationSafety.Prepared destination) {
            probe = probeFactory.apply(BlockPos.containing(destination.position()));
            probe.begin(new Offset(0, 0, 0), ProbeKind.ROOT);
        }
        @Override public ProbeStep tick(int worldWorkBudget) { return probe.step(worldWorkBudget); }
    }

    static final class PreparedFinalStage implements FinalStage {
        private final Function<Vec3, DestinationSafety.ChunkPreparation> preparationFactory;
        private final BiFunction<BlockPos, DestinationSafety.ChunkResidency, CandidateProbe> probeFactory;
        private DestinationSafety.Prepared destination;
        private DestinationSafety.ChunkPreparation preparation;
        private CandidateProbe probe;

        PreparedFinalStage(Function<Vec3, DestinationSafety.ChunkPreparation> preparationFactory,
                           BiFunction<BlockPos, DestinationSafety.ChunkResidency, CandidateProbe> probeFactory) {
            this.preparationFactory = preparationFactory;
            this.probeFactory = probeFactory;
        }
        @Override public void begin(DestinationSafety.Prepared destination) {
            this.destination = destination;
            this.preparation = preparationFactory.apply(destination.position());
        }
        @Override public ProbeStep tick(int worldWorkBudget) {
            if (!preparation.complete()) {
                int prepared = preparation.prepare(PREPARATION_CHUNKS_PER_VISIT, worldWorkBudget);
                return new ProbeStep(ProbeOutcome.INCOMPLETE, prepared);
            }
            if (probe == null) {
                probe = probeFactory.apply(BlockPos.containing(destination.position()), preparation.residency());
                probe.begin(new Offset(0, 0, 0), ProbeKind.ROOT);
            }
            return probe.step(worldWorkBudget);
        }
        @Override public int closeWork() { return preparation == null ? 0 : preparation.closeWork(); }
        @Override public void close() { if (preparation != null) preparation.close(); }
    }

    static final class Pending implements AutoCloseable {
        private final SearchStage search;
        private final FinalStage finalStage;
        private final ServerLevel level;
        private final BlockPos center;
        private final BlockPos searchAnchor;
        private final int rootWidth;
        private final float yaw;
        private final float pitch;
        private DestinationSafety.Prepared destination;
        private Result result;
        private boolean finalStarted;

        Pending(SearchStage search, FinalStage finalStage, ServerLevel level,
                BlockPos center, BlockPos searchAnchor, int rootWidth, float yaw, float pitch) {
            this.search = search;
            this.finalStage = finalStage;
            this.level = level;
            this.center = center;
            this.searchAnchor = searchAnchor;
            this.rootWidth = rootWidth;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        Tick tick(int candidateBudget, int worldWorkBudget) {
            if (result != null) throw new IllegalStateException("pending spawn is already complete");
            if (!search.complete()) {
                Tick used = search.tick(candidateBudget, worldWorkBudget);
                if (!search.complete()) return used;
                Selection selection = search.selection();
                if (selection.outcome() != Outcome.ACCEPT) {
                    result = new Result(selection.outcome(), null);
                    return used;
                }
                BlockPos feet = feet(center, selection.offset());
                destination = DestinationSafety.Prepared.ordinary(level,
                        new Vec3(DestinationSafety.spawnCenter(feet.getX(), rootWidth), feet.getY(),
                                DestinationSafety.spawnCenter(feet.getZ(), rootWidth)), yaw, pitch);
                return used;
            }

            if (!finalStarted) {
                finalStage.begin(destination);
                finalStarted = true;
            }
            ProbeStep checked = finalStage.tick(worldWorkBudget);
            if (checked.outcome() == ProbeOutcome.REJECTED) {
                result = new Result(Outcome.UNSAFE, null);
            } else if (checked.outcome() == ProbeOutcome.FITS) {
                result = new Result(Outcome.ACCEPT, destination, true, searchAnchor);
            }
            return new Tick(0, checked.worldWork());
        }

        boolean complete() { return result != null; }

        Result result() {
            if (result == null) throw new IllegalStateException("pending spawn is not complete");
            return result;
        }

        int closeWork() {
            return search.closeWork() + finalStage.closeWork();
        }

        @Override
        public void close() {
            RuntimeException failure = null;
            try {
                finalStage.close();
            } catch (RuntimeException closeFailure) {
                failure = closeFailure;
            }
            try {
                search.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
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

    static Iterable<Offset> offsets(int bound) {
        if (bound < 0) throw new IllegalArgumentException("invalid search bound");
        return () -> new OffsetIterator(bound);
    }

    static int searchRootWidth(int rootWidth, int rootHeight) {
        return DestinationSafety.rootGeometrySupported(rootWidth, rootHeight) ? rootWidth : 1;
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

    static Outcome acceptEnd(boolean force, int rootWidth, int rootHeight,
                             BooleanSupplier rootFits, BooleanSupplier playerFits) {
        if (force) return Outcome.ACCEPT;
        if (!DestinationSafety.rootGeometrySupported(rootWidth, rootHeight)) return Outcome.VEHICLE_TOO_LARGE;
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

        DestinationSafety.RootGeometry geometry = DestinationSafety.rootGeometry(root, root == player);
        int rootWidth = geometry.width();
        int rootHeight = geometry.clearHeight();
        boolean rootGeometrySupported = DestinationSafety.rootGeometrySupported(rootWidth, rootHeight);
        int residencyWidth = searchRootWidth(rootWidth, rootHeight);
        BlockPos center = anchor;
        DestinationSafety.ChunkPreparation preparation =
                DestinationSafety.ChunkPreparation.expandableForLevel(level);
        SearchStage search = new PreparedSearchStage(offsets(SEARCH_BOUND).iterator(), root != player,
                preparation, residency -> DestinationSafety.SpawnProbe.forLevel(
                level, center, rootWidth, rootHeight, rootGeometrySupported, residency),
                center, residencyWidth);
        FinalStage finalStage = new PreparedFinalStage(
                position -> DestinationSafety.ChunkPreparation.forDestination(level, position),
                (feet, residency) -> DestinationSafety.SpawnProbe.forLevel(
                        level, feet, rootWidth, rootHeight, true, residency));
        return new Plan(null, new Pending(search, finalStage, level, center, anchor,
                residencyWidth, root.getYRot(), root.getXRot()));
    }

    private static Result findEnd(ServerPlayer player, ServerLevel endLevel, boolean force) {
        // Minecraft 26.2 coupling: getPortalDestination mutates/regenerates the obsidian platform
        // and supplies transition flags, orientation, sound, and portal ticket. Keep this one vanilla
        // call as the authority; do not recreate or "sanitize" its TeleportTransition locally.
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
        return center.offset(offset.x(), offset.y(), offset.z());
    }

    /** Expands by Chebyshev radius and emits x, then y, then z lexicographically within each shell. */
    private static final class OffsetIterator implements Iterator<Offset> {
        private final int bound;
        private int radius;
        private int x;
        private int y;
        private int z;
        private boolean available = true;

        private OffsetIterator(int bound) {
            this.bound = bound;
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
                if (bound == 0) {
                    available = false;
                    return;
                }
                radius = 1;
                initializeShell();
                return;
            }

            boolean fullZRange = Math.abs(x) == radius || Math.abs(y) == radius;
            if (fullZRange && z < radius) {
                z++;
                return;
            }
            if (!fullZRange && z == -radius) {
                z = radius;
                return;
            }

            if (y < radius) {
                y++;
                z = -radius;
                return;
            }

            if (x < radius) {
                x++;
                y = -radius;
                z = -radius;
                return;
            }

            radius++;
            if (radius > bound) {
                available = false;
                return;
            }
            initializeShell();
        }

        private void initializeShell() {
            x = -radius;
            y = -radius;
            z = -radius;
        }
    }
}
