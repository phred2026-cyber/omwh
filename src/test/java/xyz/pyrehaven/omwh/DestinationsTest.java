package xyz.pyrehaven.omwh;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public final class DestinationsTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        homePolicyAllowsOnlyAcceptedRespawnsAndOneBedAlternate();
        unmountedHomeRejectsHazardsUnlessForceWasRequested();
        homeSafetyUsesStandingDimensionsAndExactHazards();
        spawnRoutingHonorsThreePoliciesAndCrossingBoundary();
        forceUsesRawDestinationsWithoutChangingAdmission();
        mountedHomeClearanceUsesRootMarginsAndOnlyBedPolicyExemption();
        mountedHazardsDoNotMasqueradeAsVehicleClearanceFailures();
        preparedDestinationPreservesAuthoritativeTransition();
        endPortalSafetyChecksOnlyTheRegeneratedPlatformDestination();
        immediateGeometryLimitsRejectBeforeHomeAndEndSafetyScans();
        immediateWorkBoundsAreDerivedFromEnforcedGeometry();
        spawnSearchIsLazyDeterministicAndComplete();
        zeroBoundsEmitOnlyTheOrigin();
        incrementalSearchResumesWithinFixedTickBudgets();
        wholeColdSearchRejectsBeforeCandidateOrBlockWork();
        residencySnapshotRetainsChunkValuesForLoadedOnlyReads();
        productionSpawnProbeUsesRealBlockStatesAndHonestCollisionWork();
        finalLiveRevalidationRejectsAChangedAcceptedFootprint();
        oversizedModdedRootsKeepTheSnapshotHardBounded();
        productionSearchHotPathHasBoundedAllocationAndNoCandidateChunkSets();
        spawnReadFailureNeverInventsCoordinates();
        spawnSearchUsesOneBoundedLoadedChunkPass();
        netherAnchorUsesVanillaScaleBlockCentersFloorAndBorderClamp();
        currentAnchorRevalidationIsPureAndFailClosed();
        spawnFailureDistinguishesOversizedVehicle();
        collisionOwnersIncludeShellAndEveryInvolvedChunk();
        safetyOwnsFootprintsHazardsAndFiveByFiveChunkPreparation();
        System.out.println("DestinationsTest PASS (27 behavior groups)");
    }

    private static void homePolicyAllowsOnlyAcceptedRespawnsAndOneBedAlternate() {
        check(HomeDestination.decide(true, true, true, true) == HomeDestination.Decision.NO_HOME,
                "missing home");
        check(HomeDestination.decide(false, false, true, true) == HomeDestination.Decision.NO_HOME,
                "unavailable saved-home dimension cannot become default spawn");
        check(HomeDestination.decide(false, true, false, false) == HomeDestination.Decision.CROSS_DIMENSION,
                "cross-dimension home denied when crossing is disabled");
        check(HomeDestination.decide(false, true, false, true) == HomeDestination.Decision.ACCEPT,
                "cross-dimension home accepted when crossing is enabled");
        check(HomeDestination.decide(false, true, true, false) == HomeDestination.Decision.ACCEPT,
                "same-dimension home does not require crossing");
        check(HomeDestination.mayTryAboveBed(true, true, false, false), "non-forced mounted bed");
        check(!HomeDestination.mayTryAboveBed(true, true, false, true), "covered bed");
        check(!HomeDestination.mayTryAboveBed(false, true, false, false), "unmounted player");
        check(!HomeDestination.mayTryAboveBed(true, false, false, false), "anchor");
        check(!HomeDestination.mayTryAboveBed(true, true, true, false), "forced home");
    }

    private static void unmountedHomeRejectsHazardsUnlessForceWasRequested() {
        check(DestinationSafety.isUnsafeCell(true, Blocks.STONE), "home fluid hazard");
        check(DestinationSafety.isUnsafeCell(false, Blocks.LAVA), "home lava hazard");
        check(DestinationSafety.isUnsafeCell(false, Blocks.CACTUS), "home cactus hazard");
        check(!DestinationSafety.isUnsafeCell(false, Blocks.STONE), "ordinary home block");
        AtomicInteger normalChecks = new AtomicInteger();
        check(!HomeDestination.acceptUnmounted(false, () -> {
            normalChecks.incrementAndGet();
            return false;
        }), "unsafe vanilla respawn denied");
        check(normalChecks.get() == 1, "normal home evaluates destination safety once");

        AtomicInteger forcedChecks = new AtomicInteger();
        check(HomeDestination.acceptUnmounted(true, () -> {
            forcedChecks.incrementAndGet();
            return false;
        }), "force bypasses destination safety");
        check(forcedChecks.get() == 0, "force never reads destination safety");
    }

    private static void homeSafetyUsesStandingDimensionsAndExactHazards() {
        var standing = DestinationSafety.standingPlayerBounds(
                new Vec3(10.0, 64.0, -3.0), EntityDimensions.fixed(0.6f, 1.8f));
        check(standing.minY() == 64.0 && standing.maxY() > 65.79,
                "unmounted home safety always checks standing height");
        check(DestinationSafety.isHazard(Blocks.FIRE), "fire is hazardous");
        check(DestinationSafety.isHazard(Blocks.SOUL_FIRE), "soul fire is hazardous");
        check(DestinationSafety.isHazard(Blocks.LAVA), "lava is hazardous");
        check(DestinationSafety.isHazard(Blocks.MAGMA_BLOCK), "magma is hazardous");
        check(!DestinationSafety.isHazard(Blocks.FIRE_CORAL), "fire coral is not fire");
        check(!DestinationSafety.isHazard(Blocks.DEAD_FIRE_CORAL_BLOCK), "dead fire coral is not fire");
    }

    private static void spawnRoutingHonorsThreePoliciesAndCrossingBoundary() {
        for (boolean cross : List.of(false, true)) {
            for (boolean overworld : List.of(false, true)) {
                for (boolean nether : List.of(false, true)) {
                    for (boolean end : List.of(false, true)) {
                        checkRoute(SpawnDestination.Dimension.OVERWORLD,
                                overworld ? SpawnDestination.Target.CURRENT : SpawnDestination.Target.DISABLED,
                                cross, overworld, nether, end);
                        checkRoute(SpawnDestination.Dimension.NETHER,
                                nether ? SpawnDestination.Target.CURRENT
                                        : cross && overworld ? SpawnDestination.Target.OVERWORLD
                                        : SpawnDestination.Target.DISABLED,
                                cross, overworld, nether, end);
                        checkRoute(SpawnDestination.Dimension.END,
                                end ? SpawnDestination.Target.CURRENT
                                        : cross && overworld ? SpawnDestination.Target.OVERWORLD
                                        : SpawnDestination.Target.DISABLED,
                                cross, overworld, nether, end);
                    }
                }
            }
        }

        checkRoute(SpawnDestination.Dimension.NETHER, SpawnDestination.Target.CURRENT,
                false, false, true, false);
        checkRoute(SpawnDestination.Dimension.END, SpawnDestination.Target.CURRENT,
                false, false, false, true);
        checkRoute(SpawnDestination.Dimension.NETHER, SpawnDestination.Target.DISABLED,
                true, false, false, true);
        checkRoute(SpawnDestination.Dimension.END, SpawnDestination.Target.OVERWORLD,
                true, true, true, false);
        checkRoute(SpawnDestination.Dimension.OTHER, SpawnDestination.Target.OVERWORLD,
                true, true, true, true);
        check(SpawnDestination.route(SpawnDestination.Dimension.OTHER,
                        false, true, true, true, true) == SpawnDestination.Target.CURRENT,
                "enabled modded-dimension spawn stays in the current dimension");
        check(SpawnDestination.route(SpawnDestination.Dimension.OTHER,
                        true, true, true, true, false) == SpawnDestination.Target.OVERWORLD,
                "disabled modded-dimension spawn falls back to Overworld when crossing is enabled");
        check(SpawnDestination.route(SpawnDestination.Dimension.OTHER,
                        false, true, true, true, false) == SpawnDestination.Target.DISABLED,
                "disabled modded-dimension spawn cannot cross when crossing is disabled");
        check(SpawnDestination.route(SpawnDestination.Dimension.OTHER,
                        true, false, true, true, false) == SpawnDestination.Target.DISABLED,
                "disabled modded-dimension spawn cannot route to disabled Overworld spawn");
    }

    private static void checkRoute(SpawnDestination.Dimension current, SpawnDestination.Target expected,
                                   boolean cross, boolean overworld, boolean nether, boolean end) {
        check(SpawnDestination.route(current, cross, overworld, nether, end, false) == expected,
                "spawn route " + current + " cross=" + cross + " overworld=" + overworld
                        + " nether=" + nether + " end=" + end);
    }

    private static void forceUsesRawDestinationsWithoutChangingAdmission() {
        check(HomeDestination.decide(false, true, false, false) == HomeDestination.Decision.CROSS_DIMENSION,
                "force cannot bypass cross-dimension home admission");
        check(SpawnDestination.route(SpawnDestination.Dimension.NETHER,
                        false, true, false, true, false) == SpawnDestination.Target.DISABLED,
                "force cannot bypass cross-dimension spawn admission");
        check(SpawnDestination.route(SpawnDestination.Dimension.END,
                        false, false, true, true, false) == SpawnDestination.Target.CURRENT,
                "enabled End spawn remains selected without cross-dimension admission");
        check(SpawnDestination.rawPosition(new BlockPos(12, 70, -4)).equals(new Vec3(12.5, 70, -3.5)),
                "raw spawn is the authoritative block-center feet position");
    }

    private static void mountedHomeClearanceUsesRootMarginsAndOnlyBedPolicyExemption() {
        var root = new DestinationSafety.Bounds(0, 64, 0, 1, 65, 1);
        var clearance = DestinationSafety.mountedHomeClearance(root);
        check(clearance.equals(new DestinationSafety.Bounds(-0.5, 64, -0.5, 1.5, 66.5, 1.5)),
                "mounted margin geometry");
        var marginObstacle = new DestinationSafety.Bounds(-0.4, 64, 0, -0.1, 65, 1);
        check(DestinationSafety.blocksMountedHome(root, clearance, marginObstacle, false), "unrelated margin block");
        check(!DestinationSafety.blocksMountedHome(root, clearance, marginObstacle, true), "bed margin exemption");
        var rootObstacle = new DestinationSafety.Bounds(0.2, 64, 0.2, 0.8, 65, 0.8);
        check(DestinationSafety.blocksMountedHome(root, clearance, rootObstacle, true), "bed never exempts root collision");
        check(DestinationSafety.homeHazardCells(root).equals(
                new DestinationSafety.CellRange(0, 0, 63, 64, 0, 0)),
                "mounted hazard checks cover the root and its support layer, not only collision shapes");
    }

    private static void mountedHazardsDoNotMasqueradeAsVehicleClearanceFailures() {
        check(HomeDestination.chooseMounted(DestinationSafety.HomeFit.UNSAFE, true,
                        DestinationSafety.HomeFit.FITS) == HomeDestination.MountedChoice.UNSAFE,
                "unsafe exact destination never falls through to the bed fallback");
        check(HomeDestination.chooseMounted(DestinationSafety.HomeFit.BLOCKED, true,
                        DestinationSafety.HomeFit.UNSAFE) == HomeDestination.MountedChoice.UNSAFE,
                "unsafe bed fallback reports unsafe");
        check(HomeDestination.chooseMounted(DestinationSafety.HomeFit.BLOCKED, true,
                        DestinationSafety.HomeFit.FITS) == HomeDestination.MountedChoice.ABOVE_BED,
                "clearance failure may use a safe bed fallback");
        check(HomeDestination.chooseMounted(DestinationSafety.HomeFit.BLOCKED, false,
                        DestinationSafety.HomeFit.BLOCKED) == HomeDestination.MountedChoice.VEHICLE_TOO_LARGE,
                "geometry-only denial retains the vehicle message");
    }

    private static void preparedDestinationPreservesAuthoritativeTransition() {
        var transition = new net.minecraft.world.level.portal.TeleportTransition(
                null, new Vec3(10.5, 49.0, -2.5), Vec3.ZERO, 90.0f, 0.0f,
                net.minecraft.world.level.portal.TeleportTransition.DO_NOTHING);
        var prepared = new DestinationSafety.Prepared(transition);
        check(prepared.transition() == transition, "Prepared preserves the authoritative transition object");
        check(prepared.level() == transition.newLevel() && prepared.position().equals(transition.position()),
                "Prepared convenience accessors read from the authoritative transition");
        check(!prepared.clearVelocity(), "vanilla portal transitions retain vanilla velocity handling");
        check(DestinationSafety.Prepared.ordinary(null, transition.position(), 0.0f, 0.0f).clearVelocity(),
                "ordinary OMWH destinations still clear carried root velocity");
    }

    private static void endPortalSafetyChecksOnlyTheRegeneratedPlatformDestination() {
        AtomicInteger rootChecks = new AtomicInteger();
        AtomicInteger playerChecks = new AtomicInteger();
        check(SpawnDestination.acceptEnd(false,
                        () -> { rootChecks.incrementAndGet(); return true; },
                        () -> { playerChecks.incrementAndGet(); return true; }) == SpawnDestination.Outcome.ACCEPT,
                "safe regenerated End platform destination is accepted");
        check(rootChecks.get() == 1 && playerChecks.get() == 0,
                "End accepts the root without a separate player diagnostic");

        rootChecks.set(0);
        playerChecks.set(0);
        check(SpawnDestination.acceptEnd(false,
                        () -> { rootChecks.incrementAndGet(); return false; },
                        () -> { playerChecks.incrementAndGet(); return true; })
                        == SpawnDestination.Outcome.VEHICLE_TOO_LARGE,
                "End reports a vehicle that cannot fit on the regenerated platform");
        check(rootChecks.get() == 1 && playerChecks.get() == 1,
                "End checks player fit only after root fit fails");

        rootChecks.set(0);
        playerChecks.set(0);
        check(SpawnDestination.acceptEnd(true,
                        () -> { rootChecks.incrementAndGet(); return false; },
                        () -> { playerChecks.incrementAndGet(); return false; }) == SpawnDestination.Outcome.ACCEPT,
                "force accepts the vanilla End transition without placement checks");
        check(rootChecks.get() == 0 && playerChecks.get() == 0,
                "forced End spawn performs no safety or size checks");
    }

    private static void immediateGeometryLimitsRejectBeforeHomeAndEndSafetyScans() {
        AtomicInteger homeScans = new AtomicInteger();
        check(HomeDestination.mountedGeometryOutcome(14, 16, () -> {
                    homeScans.incrementAndGet();
                    return DestinationSafety.HomeFit.FITS;
                }) == HomeDestination.Outcome.ACCEPT,
                "largest supported mounted home geometry reaches its safety scan");
        check(homeScans.get() == 1, "supported mounted home scans exactly once through the seam");
        check(HomeDestination.mountedGeometryOutcome(15, 16, () -> {
                    homeScans.incrementAndGet();
                    return DestinationSafety.HomeFit.FITS;
                }) == HomeDestination.Outcome.VEHICLE_TOO_LARGE,
                "too-wide mounted home is rejected");
        check(homeScans.get() == 1, "too-wide mounted home rejects before world safety work");

        AtomicInteger endScans = new AtomicInteger();
        check(SpawnDestination.acceptEnd(false, 14, 16,
                        () -> { endScans.incrementAndGet(); return true; }, null)
                        == SpawnDestination.Outcome.ACCEPT,
                "largest supported End root reaches exact-platform safety");
        check(SpawnDestination.acceptEnd(false, 14, 17,
                        () -> { endScans.incrementAndGet(); return true; }, () -> false)
                        == SpawnDestination.Outcome.VEHICLE_TOO_LARGE,
                "too-tall End root is rejected as an unsupported vehicle");
        check(endScans.get() == 1, "oversized End root rejects before root or player safety scans");
        check(SpawnDestination.acceptEnd(true, 100, 100,
                        () -> { throw new AssertionError("force must skip root safety"); }, null)
                        == SpawnDestination.Outcome.ACCEPT,
                "force still bypasses destination geometry and safety");
    }

    private static void immediateWorkBoundsAreDerivedFromEnforcedGeometry() {
        check(DestinationSafety.MAX_SUPPORTED_ROOT_WIDTH == 14
                        && DestinationSafety.MAX_SUPPORTED_CLEAR_HEIGHT == 16,
                "all normal routes share the accepted 14x16 root contract");
        check(DestinationSafety.MAX_SINGLE_MOUNTED_HOME_SAFETY_WORK == 59_015
                        && DestinationSafety.MAX_MOUNTED_HOME_SAFETY_WORK == 118_050,
                "mounted home bound independently includes cached bed reads, hazards, and collision work");
        check(DestinationSafety.MAX_SINGLE_END_SAFETY_WORK == 49_626
                        && DestinationSafety.MAX_PLAYER_END_SAFETY_WORK == 772
                        && DestinationSafety.MAX_END_SAFETY_WORK == 50_398,
                "End bound independently includes support collision-shape checks and player diagnostics");
        check(DestinationSafety.MAX_SPAWN_CANDIDATE_WORK == 46_372,
                "spawn candidate bound independently includes support collision-shape checks");
        check(Commands.MAX_IMMEDIATE_ROUTE_WORK == 120_096
                        && Commands.SEARCH_WORLD_WORK_PER_TICK == 120_671,
                "immediate and aggregate reservations match independently calculated maxima");
        check(DestinationSafety.destinationChunks(0, 0).size()
                        == DestinationSafety.DESTINATION_CHUNK_CAP,
                "immediate preparation is explicitly capped at 25 chunks");
    }

    private static void spawnSearchIsLazyDeterministicAndComplete() {
        List<SpawnDestination.Offset> small = toList(SpawnDestination.offsets(1, 1));
        check(small.size() == 27, "radius-one hollow-cube search count");
        check(small.getFirst().equals(new SpawnDestination.Offset(0, 0, 0)), "origin first");
        check(small.equals(referenceOffsets(1, 1)),
                "hollow 3D cube shells use deterministic x/y/z order");

        var iterator = SpawnDestination.offsets(48, 48).iterator();
        for (var field : iterator.getClass().getDeclaredFields()) {
            check(!java.util.Collection.class.isAssignableFrom(field.getType()) && !field.getType().isArray(),
                    "lazy iterator does not retain candidate collections or arrays");
        }
        long count = 0;
        SpawnDestination.Offset previous = null;
        long started = System.nanoTime();
        while (iterator.hasNext()) {
            SpawnDestination.Offset offset = iterator.next();
            check(Math.abs(offset.x()) <= 48 && Math.abs(offset.z()) <= 48, "horizontal bound");
            check(Math.abs(offset.y()) <= 48, "vertical bound");
            if (previous != null) check(compareOffsets(previous, offset) < 0,
                    "hollow cube shells are ordered and contain no repeated interior positions");
            previous = offset;
            count++;
        }
        long elapsedNanos = System.nanoTime() - started;
        check(count == 912_673L, "complete bounded production space");
        check(elapsedNanos < 10_000_000_000L, "lazy production traversal completes promptly");
        System.out.printf("Spawn offset traversal count=%d elapsedMs=%.3f%n", count, elapsedNanos / 1_000_000.0);
    }

    private static void zeroBoundsEmitOnlyTheOrigin() {
        check(toList(SpawnDestination.offsets(0, 0)).equals(
                        List.of(new SpawnDestination.Offset(0, 0, 0))),
                "zero bounds emit the origin exactly once");
    }

    private static void incrementalSearchResumesWithinFixedTickBudgets() {
        List<SpawnDestination.Offset> expected = referenceOffsets(2, 2);
        List<String> started = new ArrayList<>();
        SpawnDestination.CandidateProbe probe = new SpawnDestination.CandidateProbe() {
            private SpawnDestination.Offset active;
            private SpawnDestination.ProbeKind kind;
            private int remaining;

            @Override
            public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind probeKind) {
                check(active == null, "candidate probe is not restarted before completion");
                active = offset;
                kind = probeKind;
                remaining = probeKind == SpawnDestination.ProbeKind.ROOT ? 3 : 2;
                started.add(probeKind + ":" + offset.x() + ":" + offset.y() + ":" + offset.z());
            }

            @Override
            public SpawnDestination.ProbeStep step(int availableWorldWork) {
                int used = Math.min(remaining, availableWorldWork);
                remaining -= used;
                if (remaining != 0) return new SpawnDestination.ProbeStep(
                        SpawnDestination.ProbeOutcome.INCOMPLETE, used);
                boolean fits = kind == SpawnDestination.ProbeKind.PLAYER && active.equals(expected.getFirst());
                active = null;
                return new SpawnDestination.ProbeStep(
                        fits ? SpawnDestination.ProbeOutcome.FITS : SpawnDestination.ProbeOutcome.REJECTED, used);
            }
        };

        SpawnDestination.Search search = new SpawnDestination.Search(
                SpawnDestination.offsets(2, 2).iterator(), probe, true);
        int ticks = 0;
        while (!search.complete()) {
            SpawnDestination.Tick tick = search.tick(2, 5);
            check(tick.candidatesStarted() <= 2, "per-tick candidate ceiling");
            check(tick.worldWork() <= 5, "per-tick world-work ceiling");
            ticks++;
            check(ticks < 1_000, "incremental search makes deterministic progress");
        }
        SpawnDestination.Selection result = search.selection();
        check(result.outcome() == SpawnDestination.Outcome.VEHICLE_TOO_LARGE,
                "player-alone fit survives later root exhaustion");
        check(result.candidatesVisited() == expected.size() && result.rootChecks() == expected.size(),
                "incremental exhaustion visits every candidate exactly once");
        check(result.playerChecks() == 1,
                "mounted diagnostic stops after the first player-alone fit instead of doubling work");
        List<SpawnDestination.Offset> rootOrder = started.stream()
                .filter(value -> value.startsWith("ROOT:"))
                .map(DestinationsTest::parseStartedOffset).toList();
        check(rootOrder.equals(expected), "tick resume preserves exact shell/x/y/z order without repeats or skips");
    }

    private static void wholeColdSearchRejectsBeforeCandidateOrBlockWork() {
        AtomicInteger chunkProbes = new AtomicInteger();
        DestinationSafety.ChunkResidency cold = DestinationSafety.ChunkResidency.capture(
                -50, 50, -50, 50, chunk -> {
                    chunkProbes.incrementAndGet();
                    return false;
                });
        AtomicInteger candidateBegins = new AtomicInteger();
        AtomicInteger blockReads = new AtomicInteger();
        SpawnDestination.Start start = SpawnDestination.start(
                SpawnDestination.offsets(48, 48).iterator(), new SpawnDestination.CandidateProbe() {
                    @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) {
                        candidateBegins.incrementAndGet();
                    }
                    @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                        blockReads.incrementAndGet();
                        return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.REJECTED, 1);
                    }
                }, false, cold);
        check(start.complete() && start.selection().outcome() == SpawnDestination.Outcome.UNSAFE,
                "whole-cold search volume rejects immediately");
        check(chunkProbes.get() == cold.chunkProbes() && chunkProbes.get() <= 64,
                "whole-cold residency snapshot uses a bounded one-probe-per-chunk rectangle");
        check(candidateBegins.get() == 0 && blockReads.get() == 0,
                "whole-cold rejection performs zero candidate and block work");
    }

    private static void residencySnapshotRetainsChunkValuesForLoadedOnlyReads() {
        Object residentChunk = new Object();
        DestinationSafety.ChunkResidency snapshot = DestinationSafety.ChunkResidency.captureValues(
                0, 15, 0, 15, chunk -> residentChunk);
        check(snapshot.chunkAtBlock(4, 9) == residentChunk,
                "residency snapshot retains the exact loaded chunk for later block reads");
        check(snapshot.chunkAtBlock(16, 9) == null,
                "residency snapshot fails closed outside its captured rectangle");
    }

    private static void productionSpawnProbeUsesRealBlockStatesAndHonestCollisionWork() {
        Object controlledChunk = new Object();
        DestinationSafety.ChunkResidency resident = DestinationSafety.ChunkResidency.captureValues(
                -2, 2, -2, 2, chunk -> controlledChunk);
        DestinationSafety.SpawnProbe probe = DestinationSafety.SpawnProbe.controlled(
                BlockPos.ZERO, 1, 2, resident,
                position -> position.getY() == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        probe.begin(new SpawnDestination.Offset(0, 0, 0), SpawnDestination.ProbeKind.ROOT);
        SpawnDestination.ProbeStep result = probe.step(10_000);
        check(result.outcome() == SpawnDestination.ProbeOutcome.FITS,
                "production SpawnProbe accepts controlled real Minecraft stone/air states");
        check(result.worldWork() > 39,
                "collision intersections cost more than their underlying block reads");
        check(DestinationSafety.SpawnProbe.COLLISION_INTERSECTION_WORK
                        > DestinationSafety.SpawnProbe.BLOCK_READ_WORK,
                "collision work has a distinct conservative fixed charge");
    }

    private static void finalLiveRevalidationRejectsAChangedAcceptedFootprint() {
        Object controlledChunk = new Object();
        DestinationSafety.ChunkResidency resident = DestinationSafety.ChunkResidency.captureValues(
                -2, 2, -2, 2, chunk -> controlledChunk);
        java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> safeStates =
                position -> position.getY() == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        SpawnDestination.Search search = new SpawnDestination.Search(
                SpawnDestination.offsets(0, 0).iterator(),
                DestinationSafety.SpawnProbe.controlled(BlockPos.ZERO, 1, 2, resident, safeStates), false);
        SpawnDestination.Pending pending = SpawnDestination.Pending.controlled(
                search, BlockPos.ZERO, 1,
                feet -> DestinationSafety.SpawnProbe.controlled(feet, 1, 2, resident,
                        position -> position.getY() == -1
                                ? Blocks.LAVA.defaultBlockState() : Blocks.AIR.defaultBlockState()));

        int slices = 0;
        int totalWorldWork = 0;
        while (!pending.complete()) {
            SpawnDestination.Tick used = pending.tick(1, 64);
            check(used.candidatesStarted() <= 1 && used.worldWork() <= 64,
                    "changed-footprint revalidation stays inside each assigned slice");
            totalWorldWork += used.worldWork();
            check(++slices < 10, "changed support rejects promptly without restarting the candidate");
        }
        check(pending.result().outcome() == SpawnDestination.Outcome.UNSAFE
                        && pending.result().destination() == null,
                "fresh pending revalidation rejection finishes UNSAFE without a teleport destination");
        check(totalWorldWork > 0, "search and final rejection are charged to weighted world work");
    }

    private static void oversizedModdedRootsKeepTheSnapshotHardBounded() {
        check(SpawnDestination.rootGeometrySupported(14, 16), "documented maximum root geometry is supported");
        check(!SpawnDestination.rootGeometrySupported(15, 16), "width above policy is rejected before snapshot growth");
        check(!SpawnDestination.rootGeometrySupported(14, 17), "height above policy is rejected before search work");
        check(SpawnDestination.searchRootWidth(1_000, 1_000) == 1,
                "arbitrarily wide modded roots use only the player diagnostic snapshot");
        int halfWidth = (SpawnDestination.searchRootWidth(1_000, 1_000) + 1) / 2;
        DestinationSafety.ChunkResidency snapshot = DestinationSafety.ChunkResidency.capture(
                -SpawnDestination.HORIZONTAL_BOUND - halfWidth - 1,
                SpawnDestination.HORIZONTAL_BOUND + halfWidth + 1,
                -SpawnDestination.HORIZONTAL_BOUND - halfWidth - 1,
                SpawnDestination.HORIZONTAL_BOUND + halfWidth + 1, chunk -> false);
        check(snapshot.chunkProbes() <= 64, "arbitrary modded dimensions cannot exceed 64 captured chunks");
    }

    private static void productionSearchHotPathHasBoundedAllocationAndNoCandidateChunkSets() {
        DestinationSafety.ChunkResidency resident = DestinationSafety.ChunkResidency.capture(
                -50, 50, -50, 50, chunk -> true);
        check(SpawnDestination.CandidateProbe.class.isAssignableFrom(DestinationSafety.SpawnProbe.class),
                "production DestinationSafety probe is wired into the incremental search contract");
        check(Commands.SEARCH_CANDIDATES_PER_TICK == 4_096
                        && Commands.SEARCH_WORLD_WORK_PER_TICK
                        == TeleportService.LIFECYCLE_CAPTURE_WORK + Commands.MAX_IMMEDIATE_ROUTE_WORK,
                "candidate starts retain a fixed ceiling while world-work follows the enforced maximum route");
        AtomicInteger directRangeChecks = new AtomicInteger();
        SpawnDestination.CandidateProbe probe = new SpawnDestination.CandidateProbe() {
            @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) {
                check(resident.coversBlockRange(offset.x(), offset.x(), offset.z(), offset.z()),
                        "resident candidate center is covered without constructing an involved-chunk set");
                directRangeChecks.incrementAndGet();
            }
            @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.REJECTED, 0);
            }
        };

        Long before = allocatedBytes();
        SpawnDestination.Search search = SpawnDestination.start(
                SpawnDestination.offsets(48, 48).iterator(), probe, false, resident).search();
        while (!search.complete()) search.tick(4_096, 4_096);
        Long after = allocatedBytes();
        check(search.selection().candidatesVisited() == 912_673,
                "production-hot incremental path exhausts the exact accepted volume");
        check(directRangeChecks.get() == 912_673 && resident.chunkProbes() <= 64,
                "candidate residency uses primitive range checks over one fixed snapshot");
        if (before == null || after == null) {
            System.out.printf("Spawn incremental hot path candidates=%d chunkProbes=%d allocation=SKIP_UNSUPPORTED%n",
                    directRangeChecks.get(), resident.chunkProbes());
        } else {
            long allocated = after - before;
            check(allocated < 128L * 1024 * 1024,
                    "production-hot exhaustion allocation remains below a generous 128 MiB guard: " + allocated);
            System.out.printf("Spawn incremental hot path candidates=%d chunkProbes=%d allocatedMiB=%.3f%n",
                    directRangeChecks.get(), resident.chunkProbes(), allocated / 1024.0 / 1024.0);
        }
    }

    private static SpawnDestination.Offset parseStartedOffset(String value) {
        String[] parts = value.split(":");
        return new SpawnDestination.Offset(Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    private static Long allocatedBytes() {
        java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocationBean)
                || !allocationBean.isThreadAllocatedMemorySupported()) return null;
        if (!allocationBean.isThreadAllocatedMemoryEnabled()) allocationBean.setThreadAllocatedMemoryEnabled(true);
        return allocationBean.getThreadAllocatedBytes(Thread.currentThread().threadId());
    }

    private static void spawnReadFailureNeverInventsCoordinates() {
        List<RuntimeException> failures = new ArrayList<>();
        BlockPos center = SpawnDestination.readSpawnCenter(() -> {
            throw new IllegalStateException("spawn data unavailable");
        }, failures::add);
        check(center == null, "spawn read failure has no fabricated fallback center");
        check(failures.size() == 1 && failures.getFirst().getMessage().contains("unavailable"),
                "spawn read failure reaches the logger boundary");
        BlockPos expected = new BlockPos(4, 70, -9);
        check(SpawnDestination.readSpawnCenter(() -> expected, failures::add).equals(expected),
                "successful spawn read preserves the authoritative center");
    }

    private static void spawnSearchUsesOneBoundedLoadedChunkPass() {
        List<String> probes = new ArrayList<>();
        SpawnDestination.Selection selection = searchSelection(
                List.of(new SpawnDestination.Offset(0, 0, 0), new SpawnDestination.Offset(1, 0, 0)),
                offset -> { probes.add("root:" + offset.x()); return false; },
                offset -> { probes.add("player:" + offset.x()); return offset.x() == 0; });
        check(selection.outcome() == SpawnDestination.Outcome.VEHICLE_TOO_LARGE,
                "bounded pass retains mounted diagnostic");
        check(selection.candidatesVisited() == 2 && selection.rootChecks() == 2
                        && selection.playerChecks() == 1,
                "production Search retains a successful player diagnostic without repeating it");
        check(probes.equals(List.of("root:0", "player:0", "root:1")),
                "root and player diagnostics share the production nearest-first pass");

        long started = System.nanoTime();
        SpawnDestination.Selection exhausted = searchSelection(
                SpawnDestination.offsets(48, 48), offset -> false, offset -> false);
        long elapsedNanos = System.nanoTime() - started;
        check(exhausted.outcome() == SpawnDestination.Outcome.UNSAFE
                        && exhausted.candidatesVisited() == 912_673
                        && exhausted.rootChecks() == 912_673
                        && exhausted.playerChecks() == 912_673,
                "exhausted mounted search has exact structural bounds without rescanning interiors");
        check(elapsedNanos < 10_000_000_000L, "exhausted pure selection completes promptly");
        System.out.printf("Spawn exhausted selection checks=%d elapsedMs=%.3f%n",
                exhausted.rootChecks() + exhausted.playerChecks(), elapsedNanos / 1_000_000.0);

        var owners = new DestinationSafety.CellRange(0, 16, 0, 1, 0, 0);
        AtomicInteger loadedChecks = new AtomicInteger();
        check(!DestinationSafety.allChunksLoaded(owners, chunk ->
                loadedChecks.incrementAndGet() == 1), "candidate probing rejects an unloaded owner chunk");
        check(loadedChecks.get() == 2, "loaded-chunk probe stops at the first missing chunk");
    }

    private static void netherAnchorUsesVanillaScaleBlockCentersFloorAndBorderClamp() {
        List<Vec3> clampInputs = new ArrayList<>();
        BlockPos positive = SpawnDestination.scaledAnchor(new BlockPos(80, 70, 40), 0.125,
                (x, y, z) -> {
                    clampInputs.add(new Vec3(x, y, z));
                    return BlockPos.containing(x, y, z);
                });
        check(clampInputs.getFirst().equals(new Vec3(10.0625, 70.0, 5.0625)),
                "Nether scaling multiplies Overworld block-center X/Z and preserves spawn Y");
        check(positive.equals(new BlockPos(10, 70, 5)), "positive scaled anchor uses vanilla floor semantics");

        clampInputs.clear();
        BlockPos negative = SpawnDestination.scaledAnchor(new BlockPos(-81, 49, -1), 0.125,
                (x, y, z) -> {
                    clampInputs.add(new Vec3(x, y, z));
                    return BlockPos.containing(x, y, z);
                });
        check(clampInputs.getFirst().equals(new Vec3(-10.0625, 49.0, -0.0625)),
                "negative block centers are multiplied without custom division");
        check(negative.equals(new BlockPos(-11, 49, -1)), "negative scaled anchor floors toward negative infinity");

        BlockPos clamped = SpawnDestination.scaledAnchor(new BlockPos(240_000_000, 80, -240_000_000), 0.125,
                (x, y, z) -> new BlockPos(29_999_984, (int) y, -29_999_984));
        check(clamped.equals(new BlockPos(29_999_984, 80, -29_999_984)),
                "destination world-border clamp result is authoritative");
    }

    private static void currentAnchorRevalidationIsPureAndFailClosed() {
        BlockPos original = new BlockPos(80, 70, -40);
        check(SpawnDestination.currentAnchor(original, false, 1.0,
                        (x, y, z) -> { throw new AssertionError("Overworld anchor must not clamp"); }) == original,
                "current Overworld anchor preserves the fresh authoritative position");
        BlockPos nether = SpawnDestination.currentAnchor(original, true, 0.125,
                (x, y, z) -> BlockPos.containing(x, y, z));
        check(nether.equals(new BlockPos(10, 70, -5)),
                "current Nether anchor recomputes vanilla center scaling and clamp semantics");
        check(SpawnDestination.currentAnchor((BlockPos) null, true, 0.125,
                        (x, y, z) -> BlockPos.containing(x, y, z)) == null,
                "unreadable current spawn data fails closed without inventing an anchor");
        check(SpawnDestination.matchesSearchAnchor(original, original),
                "unchanged fresh anchor can accept the pending completion");
        check(!SpawnDestination.matchesSearchAnchor(original, new BlockPos(81, 70, -40))
                        && !SpawnDestination.matchesSearchAnchor(original, null),
                "changed or unreadable current anchors reject the pending completion");
        OmwhConfig config = new OmwhConfig();
        check(config.spawnAnchorChangedMessage.toLowerCase().contains("changed")
                        && config.spawnAnchorChangedMessage.toLowerCase().contains("again"),
                "anchor cancellation gives explicit safe retry feedback");
    }

    private static void spawnFailureDistinguishesOversizedVehicle() {
        AtomicInteger rootChecks = new AtomicInteger();
        AtomicInteger playerChecks = new AtomicInteger();
        SpawnDestination.Selection accepted = searchSelection(
                List.of(new SpawnDestination.Offset(0, 0, 0), new SpawnDestination.Offset(1, 0, 0)),
                offset -> rootChecks.incrementAndGet() == 1,
                offset -> { playerChecks.incrementAndGet(); return true; });
        check(accepted.outcome() == SpawnDestination.Outcome.ACCEPT, "root acceptance");
        check(rootChecks.get() == 1 && playerChecks.get() == 0, "no player probing before root exhaustion");

        rootChecks.set(0);
        playerChecks.set(0);
        SpawnDestination.Selection oversized = searchSelection(
                List.of(new SpawnDestination.Offset(0, 0, 0)),
                offset -> { rootChecks.incrementAndGet(); return false; },
                offset -> { playerChecks.incrementAndGet(); return true; });
        check(oversized.outcome() == SpawnDestination.Outcome.VEHICLE_TOO_LARGE, "vehicle-specific denial");
        check(rootChecks.get() == 1 && playerChecks.get() == 1, "player probing follows root exhaustion");
        SpawnDestination.Selection unsafe = searchSelection(
                List.of(new SpawnDestination.Offset(0, 0, 0)), offset -> false, offset -> false);
        check(unsafe.outcome() == SpawnDestination.Outcome.UNSAFE, "ordinary unsafe denial");
    }

    private static void collisionOwnersIncludeShellAndEveryInvolvedChunk() {
        var occupied = new DestinationSafety.Bounds(15, 64, 15, 16, 65, 16);
        var positive = DestinationSafety.collisionOwnerCells(occupied);
        check(positive.equals(new DestinationSafety.CellRange(14, 16, 63, 65, 14, 16)),
                "exclusive maximum gets one owner shell");
        Set<Long> positiveChunks = DestinationSafety.involvedChunks(positive);
        check(positiveChunks.equals(Set.of(
                DestinationSafety.chunkKey(0, 0), DestinationSafety.chunkKey(0, 1),
                DestinationSafety.chunkKey(1, 0), DestinationSafety.chunkKey(1, 1))),
                "positive chunk boundary");

        var negative = DestinationSafety.collisionOwnerCells(
                new DestinationSafety.Bounds(-16, 0, -16, -15, 1, -15));
        check(negative.equals(new DestinationSafety.CellRange(-17, -15, -1, 1, -17, -15)),
                "negative minimum and maximum shell");
        Set<Long> negativeChunks = DestinationSafety.involvedChunks(negative);
        check(negativeChunks.equals(Set.of(
                DestinationSafety.chunkKey(-2, -2), DestinationSafety.chunkKey(-2, -1),
                DestinationSafety.chunkKey(-1, -2), DestinationSafety.chunkKey(-1, -1))),
                "negative chunk boundary");

        Set<Long> loaded = new HashSet<>();
        List<Long> loadCalls = new ArrayList<>();
        DestinationSafety.Cell protrudingOwner = new DestinationSafety.Cell(14, 64, 15);
        DestinationSafety.Bounds protrudingShape = new DestinationSafety.Bounds(
                14.8, 64, 15, 15.2, 65, 16);
        check(!DestinationSafety.preloadAndCheckCollisions(occupied, loaded, loadCalls::add,
                        cell -> cell.equals(protrudingOwner) ? List.of(protrudingShape) : List.of()),
                "production-connected neighboring shape blocks spawn");
        check(DestinationSafety.preloadAndCheckCollisions(occupied, loaded, loadCalls::add,
                        cell -> cell.equals(protrudingOwner)
                                ? List.of(new DestinationSafety.Bounds(14, 64, 15, 15, 65, 16)) : List.of()),
                "touching exclusive boundary does not intersect");
        check(new HashSet<>(loadCalls).equals(positiveChunks), "production-connected owner chunks loaded");
        check(loadCalls.size() == positiveChunks.size(), "one search-local load per owner chunk");
    }

    private static void safetyOwnsFootprintsHazardsAndFiveByFiveChunkPreparation() {
        check(DestinationSafety.footprint(10.5, -2.5, 1.375).equals(
                new DestinationSafety.Footprint(9, 11, -4, -2)), "square root footprint");
        check(DestinationSafety.footprint(16.0, -16.0, 2.0).equals(
                new DestinationSafety.Footprint(15, 16, -17, -16)), "footprint exclusive maxima");
        for (var block : List.of(Blocks.FIRE, Blocks.LAVA, Blocks.MAGMA_BLOCK, Blocks.CACTUS,
                Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW)) {
            check(DestinationSafety.isHazard(block), "hazard " + block);
        }
        check(!DestinationSafety.isHazard(Blocks.STONE), "safe support");
        Set<Long> chunks = new HashSet<>(DestinationSafety.destinationChunks(31.5, -0.5));
        check(chunks.size() == 25, "five-by-five chunks");
        check(chunks.contains(DestinationSafety.chunkKey(1, -1)), "center chunk");
        check(chunks.contains(DestinationSafety.chunkKey(-1, -3)), "negative corner chunk");
    }

    private static List<SpawnDestination.Offset> toList(Iterable<SpawnDestination.Offset> offsets) {
        List<SpawnDestination.Offset> result = new ArrayList<>();
        offsets.forEach(result::add);
        return result;
    }

    private static SpawnDestination.Selection searchSelection(
            Iterable<SpawnDestination.Offset> candidates,
            Predicate<SpawnDestination.Offset> rootFits,
            Predicate<SpawnDestination.Offset> playerFits) {
        SpawnDestination.CandidateProbe probe = new SpawnDestination.CandidateProbe() {
            private SpawnDestination.Offset active;
            private SpawnDestination.ProbeKind kind;

            @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind probeKind) {
                active = offset;
                kind = probeKind;
            }

            @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                boolean fits = kind == SpawnDestination.ProbeKind.ROOT
                        ? rootFits.test(active) : playerFits.test(active);
                return new SpawnDestination.ProbeStep(fits
                        ? SpawnDestination.ProbeOutcome.FITS : SpawnDestination.ProbeOutcome.REJECTED, 0);
            }
        };
        SpawnDestination.Search search = new SpawnDestination.Search(
                candidates.iterator(), probe, playerFits != null);
        while (!search.complete()) search.tick(4_096, 4_096);
        return search.selection();
    }

    private static List<SpawnDestination.Offset> referenceOffsets(int horizontalBound, int verticalBound) {
        List<SpawnDestination.Offset> result = new ArrayList<>();
        for (int x = -horizontalBound; x <= horizontalBound; x++) {
            for (int y = -verticalBound; y <= verticalBound; y++) {
                for (int z = -horizontalBound; z <= horizontalBound; z++) {
                    result.add(new SpawnDestination.Offset(x, y, z));
                }
            }
        }
        result.sort(DestinationsTest::compareOffsets);
        return result;
    }

    private static int compareOffsets(SpawnDestination.Offset left, SpawnDestination.Offset right) {
        int compared = Integer.compare(shell(left), shell(right));
        if (compared != 0) return compared;
        compared = Integer.compare(left.x(), right.x());
        if (compared != 0) return compared;
        compared = Integer.compare(left.y(), right.y());
        return compared != 0 ? compared : Integer.compare(left.z(), right.z());
    }

    private static int shell(SpawnDestination.Offset offset) {
        return Math.max(Math.abs(offset.x()), Math.max(Math.abs(offset.y()), Math.abs(offset.z())));
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
