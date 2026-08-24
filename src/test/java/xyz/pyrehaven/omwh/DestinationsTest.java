package xyz.pyrehaven.omwh;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
import java.util.function.Function;
import java.util.function.BiFunction;

public final class DestinationsTest {
    public static void main(String[] args) {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        homePolicyAllowsOnlyAcceptedRespawnsAndOneBedAlternate();
        unmountedHomeRejectsHazardsUnlessForceWasRequested();
        homeSafetyUsesStandingDimensionsAndExactHazards();
        homePreparationFollowsExactVanillaResolutionReadsThroughSharedPendingWork();
        vanillaResolutionMaximumCoversFractionalFloorsAndChargesInspectionInPlace();
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
        safeOriginLazilyPreparesOnlyItsCandidateTerrain();
        laterCandidateLoadsOnlyItsNewBoundaryChunkWithoutRestart();
        mountedCandidateEnvelopeExpandsRetainedTerrainGeometry();
        coldSpawnPreparationIsExactBoundedAndPrecedesSearch();
        temporaryChunkTicketsOwnResidencyUntilExplicitClose();
        residencySnapshotRetainsChunkValuesForLoadedOnlyReads();
        productionSpawnProbeUsesRealBlockStatesAndHonestCollisionWork();
        finalLiveRevalidationRejectsAChangedAcceptedFootprint();
        edgeAcceptancePreparesExactFinalFiveByFiveBeforeFreshValidation();
        oversizedModdedRootsKeepTheSnapshotHardBounded();
        productionSearchHotPathHasBoundedAllocationAndNoCandidateChunkSets();
        spawnReadFailureNeverInventsCoordinates();
        spawnSearchUsesOneBoundedLoadedChunkPass();
        netherAnchorUsesVanillaScaleBlockCentersFloorAndBorderClamp();
        currentAnchorRevalidationIsPureAndFailClosed();
        spawnFailureDistinguishesOversizedVehicle();
        collisionOwnersIncludeShellAndEveryInvolvedChunk();
        safetyOwnsFootprintsHazardsAndFiveByFiveChunkPreparation();
        System.out.println("DestinationsTest PASS (34 behavior groups)");
    }

    private static void homePolicyAllowsOnlyAcceptedRespawnsAndOneBedAlternate() {
        check(HomeDestination.decide(true, true, true, true, true) == HomeDestination.Decision.NO_HOME,
                "missing home");
        check(HomeDestination.decide(true, false, false, true, true) == HomeDestination.Decision.NO_HOME,
                "unavailable saved-home dimension cannot become default spawn");
        check(HomeDestination.decide(true, false, true, false, false) == HomeDestination.Decision.CROSS_DIMENSION,
                "cross-dimension home denied when crossing is disabled");
        check(HomeDestination.decide(true, false, true, false, true) == HomeDestination.Decision.ACCEPT,
                "cross-dimension home accepted when crossing is enabled");
        check(HomeDestination.decide(true, false, true, true, false) == HomeDestination.Decision.ACCEPT,
                "same-dimension home does not require crossing");
        check(HomeDestination.decide(false, true, true, true, true)
                        == HomeDestination.Decision.CURRENT_WORLD_UNAVAILABLE,
                "current-world failure takes precedence over a missing respawn");
        check(HomeDestination.decide(true, true, true, true, true) == HomeDestination.Decision.NO_HOME,
                "missing respawn is reported after the current world is available");
        check(HomeDestination.decide(false, false, true, true, true)
                        == HomeDestination.Decision.CURRENT_WORLD_UNAVAILABLE,
                "an unavailable current world is distinct from an unsafe home");
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


    private static void homePreparationFollowsExactVanillaResolutionReadsThroughSharedPendingWork() {
        var standing = EntityDimensions.fixed(0.6f, 1.8f);
        var anchor = Blocks.RESPAWN_ANCHOR.defaultBlockState();
        List<HomeDestination.TerrainRead> interiorReads = HomeDestination.vanillaResolutionTerrain(
                new BlockPos(13, 70, 8), anchor, false, 0.0f, standing);
        List<HomeDestination.TerrainRead> edgeReads = HomeDestination.vanillaResolutionTerrain(
                new BlockPos(14, 70, 8), anchor, false, 0.0f, standing);
        check(interiorReads.size() == HomeDestination.ANCHOR_DISMOUNT_CANDIDATES
                        && edgeReads.size() == HomeDestination.ANCHOR_DISMOUNT_CANDIDATES,
                "mapped 26.2 anchor resolution retains all 25 vanilla dismount candidates");

        var northBed = Blocks.BED.red().defaultBlockState().setValue(
                net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.NORTH);
        check(HomeDestination.vanillaResolutionTerrain(
                        BlockPos.ZERO, northBed, false, 0.0f, standing).size()
                        == HomeDestination.ORDINARY_BED_DISMOUNT_CANDIDATES,
                "ordinary bed uses the exact ten surround and two above candidates");
        check(HomeDestination.vanillaResolutionTerrain(
                        BlockPos.ZERO, northBed, true, 0.0f, standing).size()
                        == HomeDestination.BUNK_BED_DISMOUNT_CANDIDATES,
                "bunk bed uses exact upper, lower, and above candidate groups");

        List<Long> generated = new ArrayList<>();
        DestinationSafety.ChunkPreparation preparation =
                expandablePreparation(key -> {
                    generated.add(key);
                    return new Object();
                });
        List<HomeDestination.TerrainRead> allReads = new ArrayList<>(interiorReads);
        allReads.addAll(edgeReads);
        OmwhCommands.PendingWork<Void> route = HomeDestination.candidateTerrain(
                preparation, allReads.iterator());
        OmwhCommands.PendingSearches<String, Void> pending = new OmwhCommands.PendingSearches<>();
        check(pending.add("home", route),
                "home resolution terrain uses the shared OmwhCommands pending owner");
        for (int candidate = 0; candidate < interiorReads.size(); candidate++) {
            int before = generated.size();
            pending.tick(1, 10, ignored -> { });
            check(generated.size() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "home resolution loads at most two chunks per request visit");
        }
        check(generated.equals(List.of(DestinationSafety.chunkKey(0, 0))) && pending.size() == 1,
                "interior home does not generate a neighboring chunk before vanilla resolution");

        while (pending.size() > 0) {
            int before = generated.size();
            pending.tick(1, 10, ignored -> { });
            check(generated.size() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "edge resolution remains inside the two-load request quantum");
        }
        check(generated.equals(List.of(
                        DestinationSafety.chunkKey(0, 0), DestinationSafety.chunkKey(1, 0))),
                "exact one-block collision-owner shell crossing x=16 generates the adjacent chunk");
    }


    private static void vanillaResolutionMaximumCoversFractionalFloorsAndChargesInspectionInPlace() {
        EntityDimensions standing = EntityDimensions.fixed(0.6f, 1.8f);
        DestinationSafety.CellRange halfSlabOwners = DestinationSafety.collisionOwnerCells(
                DestinationSafety.standingPlayerBounds(new Vec3(0.5, 64.5, 0.5), standing));
        check(halfSlabOwners.equals(new DestinationSafety.CellRange(-1, 1, 63, 67, -1, 1)),
                "fractional half-slab floor expands the vanilla player owner shell to 3x5x3");
        check(HomeDestination.VANILLA_DISMOUNT_DIRECT_BLOCK_READS == 6
                        && HomeDestination.VANILLA_PLAYER_COLLISION_OWNER_CELLS == 3 * 5 * 3
                        && HomeDestination.MAX_VANILLA_DISMOUNT_PASSES == 50
                        && HomeDestination.MAX_VANILLA_RESOLUTION_WORK == 2 + 50 * (6 + 45 * 9),
                "vanilla resolution maximum exactly covers inspection, six direct reads, and 45 owner cells");

        AtomicInteger inspections = new AtomicInteger();
        HomeDestination.SavedHome home = new HomeDestination.SavedHome(
                null, null, null, BlockPos.ZERO, false, null);
        HomeDestination.HomeAccess access = new HomeDestination.HomeAccess() {
            @Override public HomeDestination.Validation validate() { throw new AssertionError("already validated"); }
            @Override public HomeDestination.RespawnAuthority currentAuthority(HomeDestination.SavedHome saved) {
                return saved.authority();
            }
            @Override public HomeDestination.PreparedSavedHome prepare(HomeDestination.SavedHome saved) {
                return new HomeDestination.PreparedSavedHome(saved);
            }
            @Override public List<HomeDestination.TerrainRead> resolutionTerrain(HomeDestination.SavedHome saved) {
                inspections.incrementAndGet();
                return List.of();
            }
            @Override public HomeDestination.Resolution resolve(HomeDestination.PreparedSavedHome prepared) {
                throw new AssertionError("resolution must wait for its separately reserved maximum");
            }
        };
        DestinationSafety.ChunkPreparation preparation =
                expandablePreparation(key -> new Object());
        HomeDestination.Pending pending = new HomeDestination.Pending(false, access, home, preparation);

        OmwhCommands.PendingStep<HomeDestination.Result> prepared = pending.step(1, 1);
        check(!prepared.complete() && prepared.worldWorkUsed() == 1 && inspections.get() == 0,
                "home-state and bunk inspection never runs after exhausting the current visit allowance");
        OmwhCommands.PendingStep<HomeDestination.Result> blocked = pending.step(1, 1);
        check(!blocked.complete() && blocked.worldWorkUsed() == 0 && inspections.get() == 0,
                "inspection waits when its exact two-read reservation is unavailable");
        OmwhCommands.PendingStep<HomeDestination.Result> inspected = pending.step(1, 2);
        check(!inspected.complete() && inspected.worldWorkUsed() == 2 && inspections.get() == 1,
                "home-state and bunk inspection is charged in the same step in which both reads may occur");
        pending.close();
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
        check(HomeDestination.decide(true, false, true, false, false)
                        == HomeDestination.Decision.CROSS_DIMENSION,
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
        check(SpawnDestination.acceptEnd(false, 1, 2,
                        () -> { rootChecks.incrementAndGet(); return true; },
                        () -> { playerChecks.incrementAndGet(); return true; }) == SpawnDestination.Outcome.ACCEPT,
                "safe regenerated End platform destination is accepted");
        check(rootChecks.get() == 1 && playerChecks.get() == 0,
                "End accepts the root without a separate player diagnostic");

        rootChecks.set(0);
        playerChecks.set(0);
        check(SpawnDestination.acceptEnd(false, 1, 2,
                        () -> { rootChecks.incrementAndGet(); return false; },
                        () -> { playerChecks.incrementAndGet(); return true; })
                        == SpawnDestination.Outcome.VEHICLE_TOO_LARGE,
                "End reports a vehicle that cannot fit on the regenerated platform");
        check(rootChecks.get() == 1 && playerChecks.get() == 1,
                "End checks player fit only after root fit fails");

        rootChecks.set(0);
        playerChecks.set(0);
        check(SpawnDestination.acceptEnd(true, 1, 2,
                        () -> { rootChecks.incrementAndGet(); return false; },
                        () -> { playerChecks.incrementAndGet(); return false; }) == SpawnDestination.Outcome.ACCEPT,
                "force accepts the vanilla End transition without placement checks");
        check(rootChecks.get() == 0 && playerChecks.get() == 0,
                "forced End spawn performs no safety or size checks");
    }

    private static void immediateGeometryLimitsRejectBeforeHomeAndEndSafetyScans() {
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
        check(HomeDestination.MAX_VANILLA_RESOLUTION_WORK == 20_552
                        && HomeDestination.INITIAL_HOME_SAFETY_WORK == 59_035
                        && HomeDestination.FALLBACK_HOME_SAFETY_WORK == 59_015
                        && HomeDestination.RESOLUTION_AND_SAFETY_WORK == 138_602,
                "home accounting derives from mapped candidate passes, direct reads, owner cells, and safety phases");
        check(OmwhCommands.MAX_IMMEDIATE_ROUTE_WORK == 120_096
                        && OmwhCommands.MAX_PENDING_TICKET_RELEASE_WORK == 89
                        && OmwhCommands.SEARCH_WORLD_WORK_PER_TICK
                        == TeleportService.LIFECYCLE_CAPTURE_WORK
                        + OmwhCommands.MAX_PENDING_TICKET_RELEASE_WORK
                        + Math.max(OmwhCommands.MAX_IMMEDIATE_ROUTE_WORK,
                        HomeDestination.RESOLUTION_AND_SAFETY_WORK + OmwhCommands.MAX_EFFECT_DISPATCHES
                                + TeleportService.COMPLETION_WORK + TeleportService.LIFECYCLE_VALIDATION_WORK),
                "cleanup, immediate, lazy-home, and aggregate reservations are composed from named work");
        check(DestinationSafety.destinationChunks(0, 0).size()
                        == DestinationSafety.DESTINATION_CHUNK_CAP,
                "immediate preparation is explicitly capped at 25 chunks");
    }

    private static void spawnSearchIsLazyDeterministicAndComplete() {
        List<SpawnDestination.Offset> small = toList(SpawnDestination.offsets(1));
        check(small.size() == 27, "radius-one hollow-cube search count");
        check(small.getFirst().equals(new SpawnDestination.Offset(0, 0, 0)), "origin first");
        check(small.equals(referenceOffsets(1, 1)),
                "hollow 3D cube shells use deterministic x/y/z order");

        var iterator = SpawnDestination.offsets(48).iterator();
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
        check(toList(SpawnDestination.offsets(0)).equals(
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
                SpawnDestination.offsets(2).iterator(), probe, true);
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

    private static void safeOriginLazilyPreparesOnlyItsCandidateTerrain() {
        List<Long> generated = new ArrayList<>();
        DestinationSafety.ChunkPreparation preparation =
                expandablePreparation(key -> {
                    generated.add(key);
                    return new Object();
                });
        SpawnDestination.Pending pending = lazyPending(
                SpawnDestination.offsets(48).iterator(), false, preparation,
                BlockPos.ZERO.offset(8, 0, 8), 1, 2,
                position -> position.getY() == -1
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());

        int visits = 0;
        while (!pending.complete()) {
            int before = generated.size();
            SpawnDestination.Tick used = pending.tick(1, 10_000);
            check(generated.size() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "candidate terrain generation obeys the two-chunk visit quantum");
            check(used.candidatesStarted() <= 1, "candidate preparation does not restart the origin");
            check(++visits < 100, "safe origin completes without broad terrain preparation");
        }

        check(pending.result().outcome() == SpawnDestination.Outcome.ACCEPT,
                "safe origin is accepted after its complete terrain envelope is resident");
        check(generated.equals(List.of(DestinationSafety.chunkKey(0, 0))),
                "safe origin generates only its exact candidate-required chunk");
        check(generated.size() == 1,
                "safe origin never performs the former 64-chunk preload");
    }

    private static void laterCandidateLoadsOnlyItsNewBoundaryChunkWithoutRestart() {
        List<Long> generated = new ArrayList<>();
        DestinationSafety.ChunkPreparation preparation =
                expandablePreparation(key -> {
                    generated.add(key);
                    return new Object();
                });
        BlockPos center = new BlockPos(8, 0, 8);
        List<SpawnDestination.Offset> candidates = List.of(
                new SpawnDestination.Offset(0, 0, 0),
                new SpawnDestination.Offset(1, 0, 0),
                new SpawnDestination.Offset(7, 0, 0));
        SpawnDestination.Pending pending = lazyPending(
                candidates.iterator(), false, preparation, center, 1, 2,
                position -> position.getY() == -1 && (position.getX() == 8 || position.getX() == 9)
                        ? Blocks.LAVA.defaultBlockState()
                        : position.getY() == -1
                        ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());

        int candidateStarts = 0;
        int visits = 0;
        while (!pending.complete()) {
            int before = generated.size();
            SpawnDestination.Tick used = pending.tick(1, 10_000);
            candidateStarts += used.candidatesStarted();
            check(generated.size() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "boundary expansion loads at most two chunks in one request visit");
            check(++visits < 100, "later safe candidate completes deterministically");
        }

        check(candidateStarts == candidates.size(),
                "terrain preparation resumes the same candidate without skipping or restarting it");
        check(generated.equals(List.of(
                        DestinationSafety.chunkKey(0, 0), DestinationSafety.chunkKey(1, 0))),
                "unsafe candidates inside retained terrain load nothing and the boundary candidate adds one neighbor");
        check(pending.result().outcome() == SpawnDestination.Outcome.ACCEPT,
                "the boundary candidate is checked only after its complete envelope is resident");
    }

    private static void mountedCandidateEnvelopeExpandsRetainedTerrainGeometry() {
        List<Long> x14Loads = new ArrayList<>();
        DestinationSafety.ChunkPreparation x14 =
                expandablePreparation(key -> {
                    x14Loads.add(key);
                    return new Object();
                });
        SpawnDestination.Offset origin = new SpawnDestination.Offset(0, 0, 0);
        x14.requireCandidate(new BlockPos(14, 0, 8), origin, 1);
        while (!x14.complete()) x14.prepare(2, 2);
        check(x14Loads.equals(List.of(DestinationSafety.chunkKey(0, 0))),
                "x=14 exact footprint plus adjacent collision-owner shell ends at x=15 in chunk 0");

        List<Long> x15Loads = new ArrayList<>();
        DestinationSafety.ChunkPreparation x15 =
                expandablePreparation(key -> {
                    x15Loads.add(key);
                    return new Object();
                });
        x15.requireCandidate(new BlockPos(15, 0, 8), origin, 1);
        while (!x15.complete()) x15.prepare(2, 2);
        check(x15Loads.equals(List.of(
                        DestinationSafety.chunkKey(0, 0), DestinationSafety.chunkKey(1, 0))),
                "x=15 adjacent collision-owner shell reaches x=16 and requires chunk 1");

        List<Long> generated = new ArrayList<>();
        DestinationSafety.ChunkPreparation preparation =
                expandablePreparation(key -> {
                    generated.add(key);
                    return new Object();
                });
        BlockPos center = new BlockPos(9, 0, 9);
        preparation.requireCandidate(center, origin, 1);
        preparation.prepare(2, 2);
        check(generated.equals(List.of(DestinationSafety.chunkKey(0, 0))),
                "player footprint plus the horizontal owner boundary fits one interior chunk");

        preparation.requireCandidate(center, origin, 14);
        while (!preparation.complete()) preparation.prepare(2, 2);
        check(new HashSet<>(generated).equals(Set.of(
                        DestinationSafety.chunkKey(0, 0), DestinationSafety.chunkKey(0, 1),
                        DestinationSafety.chunkKey(1, 0), DestinationSafety.chunkKey(1, 1))),
                "maximum mounted footprint expands to every chunk crossed by its safety envelope");
        check(generated.size() == 4 && generated.size() <= 64,
                "mounted expansion is deduplicated under the overall retained-chunk cap");

        DestinationSafety.ChunkPreparation fullSearch =
                expandablePreparation(ignored -> new Object());
        for (int x = -48; x <= 48; x++) {
            for (int z = -48; z <= 48; z++) {
                fullSearch.requireCandidate(BlockPos.ZERO, new SpawnDestination.Offset(x, 0, z), 14);
            }
        }
        while (!fullSearch.complete()) {
            int before = fullSearch.liveResidency().chunkProbes();
            fullSearch.prepare(SpawnDestination.PREPARATION_CHUNKS_PER_VISIT, 10_000);
            check(fullSearch.liveResidency().chunkProbes() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "maximum search residency still loads no more than two chunks per request visit");
        }
        check(fullSearch.liveResidency().chunkProbes() == DestinationSafety.SPAWN_PREPARATION_CHUNK_CAP,
                "maximum mounted search geometry retains exactly the 64-chunk hard cap");
    }

    private static void coldSpawnPreparationIsExactBoundedAndPrecedesSearch() {
        List<Long> generated = new ArrayList<>();
        Object chunk = new Object();
        DestinationSafety.ChunkPreparation preparation = fixedPreparation(
                -56, 56, -56, 56, key -> {
                    generated.add(key);
                    return chunk;
                });
        AtomicInteger candidateBegins = new AtomicInteger();
        SpawnDestination.CandidateProbe probe = new SpawnDestination.CandidateProbe() {
            @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) {
                check(preparation.complete(), "search cannot begin before every destination chunk is prepared");
                candidateBegins.incrementAndGet();
            }
            @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.REJECTED, 0);
            }
        };
        SpawnDestination.Pending pending = preparedPending(
                SpawnDestination.offsets(0).iterator(), false, preparation,
                ignored -> probe, BlockPos.ZERO, 1,
                feet -> spawnProbe(feet, 1, 2,
                        preparation.residency(), position -> Blocks.AIR.defaultBlockState()));

        int preparationTicks = 0;
        while (!preparation.complete()) {
            int before = generated.size();
            SpawnDestination.Tick used = pending.tick(1, 10_000);
            check(used.candidatesStarted() == 0,
                    "cold normal spawn remains pending instead of returning UNSAFE during preparation");
            check(generated.size() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "each pending visit obeys the fixed preparation quantum");
            check(++preparationTicks < 100, "bounded preparation completes");
        }
        check(candidateBegins.get() == 0, "search starts on a later phase visit after preparation completes");
        pending.tick(1, 10_000);
        check(candidateBegins.get() == 1, "search begins after the complete prepared snapshot is captured");

        List<Long> expected = new ArrayList<>();
        for (int x = -4; x <= 3; x++) {
            for (int z = -4; z <= 3; z++) expected.add(DestinationSafety.chunkKey(x, z));
        }
        check(generated.equals(expected), "preparation emits the exact deterministic x/z chunk order");
        check(new HashSet<>(generated).size() == generated.size() && generated.size() == 64,
                "preparation deduplicates and never exceeds the 64-chunk hard bound");
        check(preparation.residency().chunkAtBlock(0, 0) == chunk,
                "prepared LevelChunk values are retained for loaded-only search reads");

        DestinationSafety.ChunkPreparation failing = fixedPreparation(
                0, 0, 0, 0, ignored -> { throw new IllegalStateException("generation failed"); });
        boolean failedLoudly = false;
        try {
            failing.prepare(SpawnDestination.PREPARATION_CHUNKS_PER_VISIT, 10);
        } catch (IllegalStateException generationFailure) {
            failedLoudly = generationFailure.getMessage().contains("generation failed");
        }
        check(failedLoudly && !failing.complete(),
                "terrain generation failures escape to the command's existing internal-error boundary");
    }

    private static void temporaryChunkTicketsOwnResidencyUntilExplicitClose() {
        List<String> events = new ArrayList<>();
        Set<Long> retained = new HashSet<>();
        DestinationSafety.TicketAccess access = new DestinationSafety.TicketAccess() {
            @Override public void retain(long chunk) {
                check(retained.add(chunk), "one preparation owns one ticket per exact chunk");
                events.add("retain:" + chunk);
            }
            @Override public Object load(long chunk) {
                check(retained.contains(chunk), "temporary ticket is installed before synchronous generation");
                events.add("load:" + chunk);
                return new Object();
            }
            @Override public void release(long chunk) {
                check(retained.remove(chunk), "release removes the exact ticket owned by this preparation");
                events.add("release:" + chunk);
            }
        };
        DestinationSafety.ChunkPreparation preparation = DestinationSafety.ChunkPreparation.controlled(
                0, 31, 0, 15, access);
        check(preparation.prepare(2, 2) == 2 && retained.size() == 2,
                "prepared chunks stay explicitly ticketed across later ticks");
        check(events.get(0).startsWith("retain:") && events.get(1).startsWith("load:"),
                "ticket installation precedes generation for each chunk");
        preparation.close();
        preparation.close();
        check(retained.isEmpty() && events.stream().filter(event -> event.startsWith("release:")).count() == 2,
                "terminal cleanup releases every ticket exactly once and close is idempotent");

        Set<Long> failingRetained = new HashSet<>();
        DestinationSafety.ChunkPreparation failing = DestinationSafety.ChunkPreparation.controlled(
                0, 0, 0, 0, new DestinationSafety.TicketAccess() {
                    @Override public void retain(long chunk) { failingRetained.add(chunk); }
                    @Override public Object load(long chunk) { throw new IllegalStateException("generation failed"); }
                    @Override public void release(long chunk) { failingRetained.remove(chunk); }
                });
        boolean failed = false;
        try {
            failing.prepare(1, 1);
        } catch (IllegalStateException expected) {
            failed = expected.getMessage().contains("generation failed");
        }
        check(failed && failingRetained.isEmpty(),
                "throwing generation immediately releases the ticket installed for the failed chunk");

        Set<Long> retainedBeforeTicketFailure = new HashSet<>();
        AtomicInteger retainCalls = new AtomicInteger();
        DestinationSafety.ChunkPreparation failingRetain = DestinationSafety.ChunkPreparation.controlled(
                0, 31, 0, 0, new DestinationSafety.TicketAccess() {
                    @Override public void retain(long chunk) {
                        if (retainCalls.incrementAndGet() == 2) throw new IllegalStateException("ticket failed");
                        retainedBeforeTicketFailure.add(chunk);
                    }
                    @Override public Object load(long chunk) { return new Object(); }
                    @Override public void release(long chunk) { retainedBeforeTicketFailure.remove(chunk); }
                });
        boolean retainFailed = false;
        try {
            failingRetain.prepare(2, 2);
        } catch (IllegalStateException expected) {
            retainFailed = expected.getMessage().contains("ticket failed");
        }
        check(retainFailed && retainedBeforeTicketFailure.isEmpty(),
                "ticket-install failure releases every earlier chunk owned by the preparation");

        Set<Long> retryableRetained = new HashSet<>();
        AtomicInteger releaseAttempts = new AtomicInteger();
        long transientFailure = DestinationSafety.chunkKey(0, 1);
        DestinationSafety.ChunkPreparation retryableClose = DestinationSafety.ChunkPreparation.controlled(
                0, 0, 0, 47, new DestinationSafety.TicketAccess() {
                    @Override public void retain(long chunk) { retryableRetained.add(chunk); }
                    @Override public Object load(long chunk) { return new Object(); }
                    @Override public void release(long chunk) {
                        releaseAttempts.incrementAndGet();
                        if (chunk == transientFailure && retryableRetained.size() == 2) {
                            throw new IllegalStateException("release failed once");
                        }
                        retryableRetained.remove(chunk);
                    }
                });
        check(retryableClose.prepare(3, 3) == 3, "retry fixture retains three exact chunks");
        boolean releaseFailed = false;
        try {
            retryableClose.close();
        } catch (IllegalStateException expected) {
            releaseFailed = expected.getMessage().contains("release failed once");
        }
        check(releaseFailed && retryableRetained.equals(Set.of(transientFailure))
                        && releaseAttempts.get() == 3,
                "failed release remains owned while every other ticket is still attempted");
        retryableClose.close();
        retryableClose.close();
        check(retryableRetained.isEmpty() && releaseAttempts.get() == 4,
                "later close retries only the failed ticket and successful releases remain exactly once");
    }

    private static void residencySnapshotRetainsChunkValuesForLoadedOnlyReads() {
        Object residentChunk = new Object();
        DestinationSafety.ChunkResidency snapshot = testResidencyValues(
                0, 15, 0, 15, chunk -> residentChunk);
        check(snapshot.chunkAtBlock(4, 9) == residentChunk,
                "residency snapshot retains the exact loaded chunk for later block reads");
        check(snapshot.chunkAtBlock(16, 9) == null,
                "residency snapshot fails closed outside its captured rectangle");
    }

    private static void productionSpawnProbeUsesRealBlockStatesAndHonestCollisionWork() {
        Object controlledChunk = new Object();
        DestinationSafety.ChunkResidency resident = testResidencyValues(
                -2, 2, -2, 2, chunk -> controlledChunk);
        DestinationSafety.SpawnProbe probe = spawnProbe(
                BlockPos.ZERO, 1, 2, resident,
                position -> position.getY() == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState());
        probe.begin(new SpawnDestination.Offset(0, 0, 0), SpawnDestination.ProbeKind.ROOT);
        SpawnDestination.ProbeStep result = probe.step(10_000);
        check(result.outcome() == SpawnDestination.ProbeOutcome.FITS,
                "production SpawnProbe accepts controlled real Minecraft stone/air states");
        check(result.worldWork() > 39,
                "collision intersections cost more than their underlying block reads");
        check(DestinationSafety.COLLISION_INTERSECTION_WORK
                        > DestinationSafety.BLOCK_READ_WORK,
                "collision work has a distinct conservative fixed charge");
    }

    private static void finalLiveRevalidationRejectsAChangedAcceptedFootprint() {
        Object controlledChunk = new Object();
        DestinationSafety.ChunkResidency resident = testResidencyValues(
                -2, 2, -2, 2, chunk -> controlledChunk);
        java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> safeStates =
                position -> position.getY() == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        SpawnDestination.Search search = new SpawnDestination.Search(
                SpawnDestination.offsets(0).iterator(),
                spawnProbe(BlockPos.ZERO, 1, 2, resident, safeStates), false);
        SpawnDestination.Pending pending = directPending(
                search, BlockPos.ZERO, 1,
                feet -> spawnProbe(feet, 1, 2, resident,
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

    private static void edgeAcceptancePreparesExactFinalFiveByFiveBeforeFreshValidation() {
        Object searchChunk = new Object();
        DestinationSafety.ChunkPreparation searchPreparation = fixedPreparation(
                -56, 56, -56, 56, ignored -> searchChunk);
        check(searchPreparation.prepare(64, 64) == 64, "edge fixture prepares the bounded search rectangle");

        SpawnDestination.CandidateProbe acceptedEdge = new SpawnDestination.CandidateProbe() {
            @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) {
                check(offset.equals(new SpawnDestination.Offset(48, 0, 0)),
                        "fixture accepts the positive search boundary");
            }
            @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.FITS, 0);
            }
        };
        List<Long> finalLoads = new ArrayList<>();
        Set<Long> finalTickets = new HashSet<>();
        List<String> order = new ArrayList<>();
        final DestinationSafety.ChunkPreparation[] finalPreparation = new DestinationSafety.ChunkPreparation[1];
        SpawnDestination.Pending pending = preparedPending(
                List.of(new SpawnDestination.Offset(48, 0, 0)).iterator(), false, searchPreparation,
                ignored -> acceptedEdge, BlockPos.ZERO, 1,
                position -> {
                    List<Long> exact = DestinationSafety.destinationChunks(position.x, position.z);
                    DestinationSafety.ChunkPreparation preparation = DestinationSafety.ChunkPreparation.controlled(
                            (int) Math.floor(position.x) - 32, (int) Math.floor(position.x) + 32,
                            (int) Math.floor(position.z) - 32, (int) Math.floor(position.z) + 32,
                            new DestinationSafety.TicketAccess() {
                                @Override public void retain(long chunk) { finalTickets.add(chunk); }
                                @Override public Object load(long chunk) {
                                    check(exact.contains(chunk), "final phase loads only the exact destination 5x5");
                                    finalLoads.add(chunk);
                                    order.add("load");
                                    return new Object();
                                }
                                @Override public void release(long chunk) { finalTickets.remove(chunk); }
                            });
                    finalPreparation[0] = preparation;
                    return preparation;
                },
                (feet, residency) -> new SpawnDestination.CandidateProbe() {
                    @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) {
                        check(finalPreparation[0].complete(),
                                "fresh live validation begins only after exact final preparation completes");
                        check(residency.coversBlockRange(feet.getX() - 1, feet.getX() + 1,
                                        feet.getZ() - 1, feet.getZ() + 1),
                                "fresh validation reads the exact currently ticketed final set");
                        order.add("validate");
                    }
                    @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                        return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.FITS, 1);
                    }
                });

        int visits = 0;
        while (!pending.complete()) {
            int before = finalLoads.size();
            SpawnDestination.Tick used = pending.tick(1, 1_000);
            check(finalLoads.size() - before <= SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                    "final destination loading remains resumable at two chunk requests per visit");
            check(used.candidatesStarted() <= 1 && used.worldWork() <= 1_000,
                    "edge completion remains inside its assigned scheduler slice");
            check(++visits < 100, "edge completion makes bounded structural progress");
        }
        List<Long> expected = DestinationSafety.destinationChunks(48.5, 0.5);
        check(finalLoads.equals(expected) && finalLoads.size() == 25
                        && new HashSet<>(finalLoads).size() == 25,
                "accepted +48 edge loads every exact final 5x5 chunk once");
        check(finalLoads.stream().anyMatch(key -> (int) (key >> 32) >= 4),
                "exact final loading extends beyond the initial search rectangle at the +48 edge");
        check(order.getLast().equals("validate")
                        && pending.result().outcome() == SpawnDestination.Outcome.ACCEPT,
                "fresh validation follows final loading and is the last gate before accepted completion");
        pending.close();
        check(finalTickets.isEmpty(), "closing accepted pending work releases final destination tickets");
    }

    private static void oversizedModdedRootsKeepTheSnapshotHardBounded() {
        check(DestinationSafety.rootGeometrySupported(14, 16), "documented maximum root geometry is supported");
        check(!DestinationSafety.rootGeometrySupported(15, 16), "width above policy is rejected before snapshot growth");
        check(!DestinationSafety.rootGeometrySupported(14, 17), "height above policy is rejected before search work");
        check(SpawnDestination.searchRootWidth(1_000, 1_000) == 1,
                "arbitrarily wide modded roots use only the player diagnostic snapshot");
        int halfWidth = (SpawnDestination.searchRootWidth(1_000, 1_000) + 1) / 2;
        DestinationSafety.ChunkResidency snapshot = testResidency(
                -SpawnDestination.SEARCH_BOUND - halfWidth - 1,
                SpawnDestination.SEARCH_BOUND + halfWidth + 1,
                -SpawnDestination.SEARCH_BOUND - halfWidth - 1,
                SpawnDestination.SEARCH_BOUND + halfWidth + 1, chunk -> false);
        check(snapshot.chunkProbes() <= 64, "arbitrary modded dimensions cannot exceed 64 captured chunks");
    }

    private static void productionSearchHotPathHasBoundedAllocationAndNoCandidateChunkSets() {
        DestinationSafety.ChunkResidency resident = testResidency(
                -50, 50, -50, 50, chunk -> true);
        check(SpawnDestination.CandidateProbe.class.isAssignableFrom(DestinationSafety.SpawnProbe.class),
                "production DestinationSafety probe is wired into the incremental search contract");
        check(OmwhCommands.SEARCH_CANDIDATES_PER_TICK == 4_096
                        && OmwhCommands.SEARCH_WORLD_WORK_PER_TICK == 142_062,
                "candidate starts stay fixed while aggregate world work covers staged lazy home and immediate routes");
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
        SpawnDestination.Search search = new SpawnDestination.Search(
                SpawnDestination.offsets(48).iterator(), probe, false);
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
        BlockPos center = SpawnDestination.readSpawnData(() -> {
            throw new IllegalStateException("spawn data unavailable");
        }, failures::add);
        check(center == null, "spawn read failure has no fabricated fallback center");
        check(failures.size() == 1 && failures.getFirst().getMessage().contains("unavailable"),
                "spawn read failure reaches the logger boundary");
        BlockPos expected = new BlockPos(4, 70, -9);
        check(SpawnDestination.readSpawnData(() -> expected, failures::add).equals(expected),
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
                SpawnDestination.offsets(48), offset -> false, offset -> false);
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

    private static DestinationSafety.ChunkPreparation expandablePreparation(LongFunction<?> loader) {
        return DestinationSafety.ChunkPreparation.expandableControlled(ticketAccess(loader));
    }

    private static DestinationSafety.ChunkPreparation fixedPreparation(
            int minX, int maxX, int minZ, int maxZ, LongFunction<?> loader) {
        return DestinationSafety.ChunkPreparation.controlled(
                minX, maxX, minZ, maxZ, ticketAccess(loader));
    }

    private static DestinationSafety.TicketAccess ticketAccess(LongFunction<?> loader) {
        return new DestinationSafety.TicketAccess() {
            @Override public void retain(long chunk) { }
            @Override public Object load(long chunk) { return loader.apply(chunk); }
            @Override public void release(long chunk) { }
        };
    }

    static DestinationSafety.SpawnProbe spawnProbe(
            BlockPos center, int rootWidth, int rootHeight,
            DestinationSafety.ChunkResidency residency, Function<BlockPos, BlockState> states) {
        return new DestinationSafety.SpawnProbe(new DestinationSafety.ProbeAccess() {
            @Override public int minY() { return -1_000_000; }
            @Override public int maxY() { return 1_000_000; }
            @Override public boolean withinBorder(double minX, double maxX, double minZ, double maxZ) {
                return true;
            }
            @Override public BlockState blockState(Object capturedChunk, BlockPos position) {
                return states.apply(position);
            }
            @Override public boolean fullSupport(BlockState state, BlockPos position) {
                return state.isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, position);
            }
            @Override public boolean collides(BlockState state, BlockPos position,
                                              double minX, double minY, double minZ,
                                              double maxX, double maxY, double maxZ) {
                return Shapes.joinIsNotEmpty(
                        state.getCollisionShape(EmptyBlockGetter.INSTANCE, position, CollisionContext.empty()),
                        Shapes.box(minX - position.getX(), minY - position.getY(), minZ - position.getZ(),
                                maxX - position.getX(), maxY - position.getY(), maxZ - position.getZ()),
                        BooleanOp.AND);
            }
        }, center, rootWidth, rootHeight, true, residency);
    }

    private static SpawnDestination.Pending lazyPending(
            java.util.Iterator<SpawnDestination.Offset> candidates, boolean mounted,
            DestinationSafety.ChunkPreparation preparation, BlockPos center,
            int rootWidth, int rootHeight, Function<BlockPos, BlockState> states) {
        SpawnDestination.SearchStage search = new SpawnDestination.PreparedSearchStage(
                candidates, mounted, preparation,
                residency -> spawnProbe(center, rootWidth, rootHeight, residency, states),
                center, rootWidth);
        SpawnDestination.FinalStage finalStage = new SpawnDestination.DirectFinalStage(
                feet -> spawnProbe(feet, rootWidth, rootHeight, preparation.liveResidency(), states));
        return new SpawnDestination.Pending(search, finalStage, null,
                center, center, rootWidth, 0, 0);
    }

    private static SpawnDestination.Pending directPending(
            SpawnDestination.Search search, BlockPos center, int rootWidth,
            Function<BlockPos, ? extends SpawnDestination.CandidateProbe> freshProbe) {
        return new SpawnDestination.Pending(new SpawnDestination.DirectSearchStage(search),
                new SpawnDestination.DirectFinalStage(freshProbe::apply), null,
                center, center, rootWidth, 0, 0);
    }

    private static SpawnDestination.Pending preparedPending(
            java.util.Iterator<SpawnDestination.Offset> candidates, boolean mounted,
            DestinationSafety.ChunkPreparation preparation,
            Function<DestinationSafety.ChunkResidency, SpawnDestination.CandidateProbe> probeFactory,
            BlockPos center, int rootWidth,
            Function<BlockPos, ? extends SpawnDestination.CandidateProbe> finalProbeFactory) {
        SpawnDestination.SearchStage search = new SpawnDestination.PreparedSearchStage(
                candidates, mounted, preparation, probeFactory, center, rootWidth);
        return new SpawnDestination.Pending(search,
                new SpawnDestination.DirectFinalStage(finalProbeFactory::apply), null,
                center, center, rootWidth, 0, 0);
    }

    private static SpawnDestination.Pending preparedPending(
            java.util.Iterator<SpawnDestination.Offset> candidates, boolean mounted,
            DestinationSafety.ChunkPreparation preparation,
            Function<DestinationSafety.ChunkResidency, SpawnDestination.CandidateProbe> probeFactory,
            BlockPos center, int rootWidth,
            Function<Vec3, DestinationSafety.ChunkPreparation> finalPreparationFactory,
            BiFunction<BlockPos, DestinationSafety.ChunkResidency, SpawnDestination.CandidateProbe> finalProbeFactory) {
        SpawnDestination.SearchStage search = new SpawnDestination.PreparedSearchStage(
                candidates, mounted, preparation, probeFactory, center, rootWidth);
        return new SpawnDestination.Pending(search,
                new SpawnDestination.PreparedFinalStage(finalPreparationFactory, finalProbeFactory), null,
                center, center, rootWidth, 0, 0);
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

    private static DestinationSafety.ChunkResidency testResidency(
            int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, LongPredicate loaded) {
        return testResidencyValues(minBlockX, maxBlockX, minBlockZ, maxBlockZ,
                chunk -> loaded.test(chunk) ? Boolean.TRUE : null);
    }

    private static DestinationSafety.ChunkResidency testResidencyValues(
            int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, LongFunction<?> values) {
        int minChunkX = Math.floorDiv(minBlockX, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int minChunkZ = Math.floorDiv(minBlockZ, 16);
        int maxChunkZ = Math.floorDiv(maxBlockZ, 16);
        int capacity = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        long[] keys = new long[capacity];
        Object[] chunks = new Object[capacity];
        int count = 0;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                long key = ((long) x << 32) | (z & 0xffffffffL);
                Object chunk = values.apply(key);
                if (chunk == null) continue;
                keys[count] = key;
                chunks[count] = chunk;
                count++;
            }
        }
        int captured = count;
        return new DestinationSafety.ChunkResidency(keys, chunks, () -> captured);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
