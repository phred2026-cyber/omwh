package xyz.pyrehaven.omwh;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

public final class DestinationsTest {
    public static void main(String[] args) {
        homePolicyAllowsOnlyAcceptedRespawnsAndOneBedAlternate();
        unmountedHomeRejectsHazardsUnlessForceWasRequested();
        spawnRoutingHonorsThreePoliciesAndCrossingBoundary();
        forceUsesRawDestinationsWithoutChangingAdmission();
        mountedHomeClearanceUsesRootMarginsAndOnlyBedPolicyExemption();
        mountedHazardsDoNotMasqueradeAsVehicleClearanceFailures();
        endPolicyUsesVanillaPlatformPlacement();
        endPlatformMutationFollowsSideEffectFreeAcceptance();
        spawnSearchIsBoundedDeterministicAndComplete();
        spawnFailureDistinguishesOversizedVehicle();
        collisionOwnersIncludeShellAndEveryInvolvedChunk();
        safetyOwnsFootprintsHazardsAndFiveByFiveChunkPreparation();
        System.out.println("DestinationsTest PASS (12 behavior groups)");
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
        check(DestinationSafety.isUnsafeHomeCell(true, "block.minecraft.stone"), "home fluid hazard");
        check(DestinationSafety.isUnsafeHomeCell(false, "block.minecraft.lava"), "home lava hazard");
        check(DestinationSafety.isUnsafeHomeCell(false, "block.minecraft.cactus"), "home cactus hazard");
        check(!DestinationSafety.isUnsafeHomeCell(false, "block.minecraft.stone"), "ordinary home block");
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
        checkRoute(SpawnDestination.Dimension.OTHER, SpawnDestination.Target.DISABLED,
                true, true, true, true);
    }

    private static void checkRoute(SpawnDestination.Dimension current, SpawnDestination.Target expected,
                                   boolean cross, boolean overworld, boolean nether, boolean end) {
        check(SpawnDestination.route(current, cross, overworld, nether, end) == expected,
                "spawn route " + current + " cross=" + cross + " overworld=" + overworld
                        + " nether=" + nether + " end=" + end);
    }

    private static void forceUsesRawDestinationsWithoutChangingAdmission() {
        check(HomeDestination.decide(false, true, false, false) == HomeDestination.Decision.CROSS_DIMENSION,
                "force cannot bypass cross-dimension home admission");
        check(SpawnDestination.route(SpawnDestination.Dimension.NETHER,
                        false, true, false, true) == SpawnDestination.Target.DISABLED,
                "force cannot bypass cross-dimension spawn admission");
        check(SpawnDestination.route(SpawnDestination.Dimension.END,
                        false, false, true, true) == SpawnDestination.Target.CURRENT,
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

    private static void endPolicyUsesVanillaPlatformPlacement() {
        SpawnDestination.EndPlatform platform = SpawnDestination.endPlatform(new BlockPos(100, 50, 0), 90.0f);
        check(platform.platformAnchor().equals(new BlockPos(100, 49, 0)), "platform anchor below vanilla position");
        check(platform.feet().equals(new Vec3(100.5, 49.0, 0.5)), "exact vanilla End feet position");
        check(platform.yaw() == 90.0f && platform.pitch() == 0.0f, "vanilla End orientation");
    }

    private static void endPlatformMutationFollowsSideEffectFreeAcceptance() {
        List<String> operations = new ArrayList<>();
        SpawnDestination.Outcome denied = SpawnDestination.acceptEnd(
                () -> { operations.add("root-fit"); return false; },
                () -> { operations.add("player-fit"); return false; },
                () -> operations.add("create-platform"));
        check(denied == SpawnDestination.Outcome.UNSAFE, "unsafe End denial");
        check(operations.equals(List.of("root-fit", "player-fit")), "denial never mutates End platform");

        operations.clear();
        SpawnDestination.Outcome oversized = SpawnDestination.acceptEnd(
                () -> { operations.add("root-fit"); return false; },
                () -> { operations.add("player-fit"); return true; },
                () -> operations.add("create-platform"));
        check(oversized == SpawnDestination.Outcome.VEHICLE_TOO_LARGE, "End vehicle denial");
        check(operations.equals(List.of("root-fit", "player-fit")), "vehicle denial never mutates End platform");

        operations.clear();
        SpawnDestination.Outcome accepted = SpawnDestination.acceptEnd(
                () -> { operations.add("root-fit"); return true; }, null,
                () -> operations.add("create-platform"));
        check(accepted == SpawnDestination.Outcome.ACCEPT, "accepted End destination");
        check(operations.equals(List.of("root-fit", "create-platform")), "platform created only after acceptance");
    }

    private static void spawnSearchIsBoundedDeterministicAndComplete() {
        List<SpawnDestination.Offset> small = toList(SpawnDestination.offsets(1, -1, 1));
        check(small.size() == 27, "small search count");
        check(small.getFirst().equals(new SpawnDestination.Offset(0, 0, 0)), "origin first");
        check(small.equals(referenceOffsets(1, -1, 1)), "complete reference order");

        Set<SpawnDestination.Offset> production = new HashSet<>();
        long started = System.nanoTime();
        for (var offset : SpawnDestination.offsets(64, -2, 10)) {
            check(Math.abs(offset.x()) <= 64 && Math.abs(offset.z()) <= 64, "horizontal bound");
            check(offset.y() >= -2 && offset.y() <= 10, "vertical bound");
            check(production.add(offset), "unique candidate");
        }
        long elapsedNanos = System.nanoTime() - started;
        check(production.size() == 216_333, "complete production search");
        check(elapsedNanos < 5_000_000_000L, "production search work completes promptly");
        System.out.printf("Spawn offset traversal count=%d elapsedMs=%.3f%n",
                production.size(), elapsedNanos / 1_000_000.0);
        check(SpawnDestination.fallbackCenter().equals(new SpawnDestination.Center(0, 64, 0)), "spawn fallback center");
    }

    private static void spawnFailureDistinguishesOversizedVehicle() {
        AtomicInteger rootChecks = new AtomicInteger();
        AtomicInteger playerChecks = new AtomicInteger();
        SpawnDestination.Selection accepted = SpawnDestination.select(
                List.of(new SpawnDestination.Offset(0, 0, 0), new SpawnDestination.Offset(1, 0, 0)),
                offset -> rootChecks.incrementAndGet() == 1,
                offset -> { playerChecks.incrementAndGet(); return true; });
        check(accepted.outcome() == SpawnDestination.Outcome.ACCEPT, "root acceptance");
        check(rootChecks.get() == 1 && playerChecks.get() == 0, "no player probing before root exhaustion");

        rootChecks.set(0);
        playerChecks.set(0);
        SpawnDestination.Selection oversized = SpawnDestination.select(
                List.of(new SpawnDestination.Offset(0, 0, 0)),
                offset -> { rootChecks.incrementAndGet(); return false; },
                offset -> { playerChecks.incrementAndGet(); return true; });
        check(oversized.outcome() == SpawnDestination.Outcome.VEHICLE_TOO_LARGE, "vehicle-specific denial");
        check(rootChecks.get() == 1 && playerChecks.get() == 1, "player probing follows root exhaustion");
        SpawnDestination.Selection unsafe = SpawnDestination.select(
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
        for (String id : List.of("fire", "lava", "magma_block", "cactus", "sweet_berry_bush",
                "wither_rose", "powder_snow")) {
            check(DestinationSafety.isHazard(id), "hazard " + id);
        }
        check(!DestinationSafety.isHazard("stone"), "safe support");
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

    private static List<SpawnDestination.Offset> referenceOffsets(int radius, int minY, int maxY) {
        List<SpawnDestination.Offset> result = new ArrayList<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                for (int y = minY; y <= maxY; y++) result.add(new SpawnDestination.Offset(x, y, z));
            }
        }
        result.sort(Comparator.comparingLong(SpawnDestination.Offset::distanceSquared)
                .thenComparingInt(offset -> Math.abs(offset.y()))
                .thenComparingInt(SpawnDestination.Offset::y)
                .thenComparingInt(SpawnDestination.Offset::x)
                .thenComparingInt(SpawnDestination.Offset::z));
        return result;
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
