package com.omwh.utils;

import com.omwh.config.ConfigParserRegressionTest;
import com.omwh.config.OmwhConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class RegressionTestSuite {
    public static void main(String[] args) {
        mountedTreeSnapshotRequiresEveryOriginalParentToRemainAttached();
        homeRejectsAbsentOrInvalidRespawnConfiguration();
        vehicleClearanceAddsRequestedPolicyMargins();
        mountedBoundsUseExclusiveMaximumAndExactChunkCoverage();
        collisionOwnersIncludeNeighboringShapeCells();
        capturedTreeMembersAreNotExternalCollisions();
        homeBedCollisionIsExemptOnlyFromPolicyMargin();
        ConfigParserRegressionTest.run();
        configRejectsInvalidOperatorInputPrecisely();
        nearestSearchOrderIsLazyAndDeterministic();
        cooldownStateHasOneAuthoritativeLookupAndLongestRestriction();
        cooldownRoundingAndTiePrecedenceAreStable();
        System.out.println("OMWH regression tests passed");
    }

    private static void mountedTreeSnapshotRequiresEveryOriginalParentToRemainAttached() {
        Node root = new Node("root");
        Node saddle = new Node("saddle");
        Node player = new Node("player");
        Node wrongVehicle = new Node("wrong");
        Map<Node, Node> currentParents = new HashMap<>();
        currentParents.put(saddle, root);
        currentParents.put(player, saddle);
        MountTreeSnapshot<Node> snapshot = new MountTreeSnapshot<>(List.of(
                new MountTreeSnapshot.Edge<>(root, saddle),
                new MountTreeSnapshot.Edge<>(saddle, player)));

        assertTrue(snapshot.isIntact(currentParents::get), "captured tree should be intact");
        currentParents.put(player, wrongVehicle);
        assertTrue(!snapshot.isIntact(currentParents::get),
                "a passenger on the wrong parent must not count as preserved");
    }

    private static void homeRejectsAbsentOrInvalidRespawnConfiguration() {
        assertEquals(HomeRespawnDecision.Outcome.NO_HOME,
                HomeRespawnDecision.decide(true, true),
                "a missing respawn block transition must not be used by /home");
        assertEquals(HomeRespawnDecision.Outcome.CROSS_DIMENSION,
                HomeRespawnDecision.decide(false, false),
                "/home must not follow a vanilla transition into another dimension");
        assertEquals(HomeRespawnDecision.Outcome.ACCEPT,
                HomeRespawnDecision.decide(false, true),
                "a valid same-dimension vanilla transition must be accepted");
    }

    private static void vehicleClearanceAddsRequestedPolicyMargins() {
        VehicleClearanceBox.Bounds clearance = VehicleClearanceBox.around(
                new VehicleClearanceBox.Bounds(10.25, 64.0, -4.75, 11.75, 65.5, -3.25));
        assertEquals(new VehicleClearanceBox.Bounds(9.75, 64.0, -5.25, 12.25, 67.0, -2.75),
                clearance, "vehicle clearance must use the requested horizontal and upper margins");
    }

    private static void mountedBoundsUseExclusiveMaximumAndExactChunkCoverage() {
        VehicleClearanceBox.Bounds topInclusive =
                new VehicleClearanceBox.Bounds(0, -64, 0, 1, 321, 1);
        assertTrue(VehicleClearanceBox.withinBuildHeight(topInclusive, -64, 320),
                "an exclusive max at getMaxY + 1 must fit");
        assertTrue(!VehicleClearanceBox.withinBuildHeight(
                        new VehicleClearanceBox.Bounds(0, -64.001, 0, 1, 100, 1), -64, 320),
                "a box below minimum build height must fail");
        assertTrue(!VehicleClearanceBox.withinBuildHeight(
                        new VehicleClearanceBox.Bounds(0, 0, 0, 1, 321.001, 1), -64, 320),
                "a box above the exclusive build ceiling must fail");

        Set<Long> loaded = new HashSet<>();
        List<Long> callbacks = new ArrayList<>();
        ChunkCoverage.loadNew(loaded, new ChunkCoverage.Range(0, 1, 0, 0),
                (x, z) -> callbacks.add(ChunkCoverage.key(x, z)));
        ChunkCoverage.loadNew(loaded, new ChunkCoverage.Range(1, 2, 0, 0),
                (x, z) -> callbacks.add(ChunkCoverage.key(x, z)));
        assertEquals(3, loaded.size(), "overlapping candidate ranges must deduplicate chunk keys");
        assertEquals(3, callbacks.size(), "the production chunk loader must visit each new chunk once");
    }

    private static void collisionOwnersIncludeNeighboringShapeCells() {
        ChunkCoverage.BlockRange owners = ChunkCoverage.blockOwners(
                new VehicleClearanceBox.Bounds(0.25, 64.0, 0.25, 0.75, 66.0, 0.75));
        assertEquals(new ChunkCoverage.BlockRange(-1, 63, -1, 1, 66, 1), owners,
                "collision checks must include the one-block owner shell around an AABB");
        assertEquals(new ChunkCoverage.Range(-1, 0, -1, 0), ChunkCoverage.chunkRange(owners),
                "chunk loading must cover every neighboring collision-shape owner");
    }

    private static void capturedTreeMembersAreNotExternalCollisions() {
        Node root = new Node("root");
        Node passenger = new Node("passenger");
        Node external = new Node("external");
        List<Node> captured = List.of(root, passenger);
        assertTrue(!EntityOwnership.isExternal(captured, root),
                "the captured root must not collide with its own prepared tree");
        assertTrue(!EntityOwnership.isExternal(captured, passenger),
                "captured passengers must not reject their prepared destination");
        assertTrue(EntityOwnership.isExternal(captured, external),
                "an uncaptured entity must remain an external collision");
        assertTrue(EntityOwnership.isExternal(captured, new Node("passenger")),
                "tree ownership must use identity rather than value equality");
    }

    private static void homeBedCollisionIsExemptOnlyFromPolicyMargin() {
        VehicleClearanceBox.Bounds vehicle =
                new VehicleClearanceBox.Bounds(0.75, 64.0, 0.75, 2.25, 65.0, 2.25);
        VehicleClearanceBox.Bounds marginOnlyBed =
                new VehicleClearanceBox.Bounds(0.25, 64.0, 0.25, 0.75, 64.5625, 1.0);
        VehicleClearanceBox.Bounds intersectingBed =
                new VehicleClearanceBox.Bounds(0.5, 64.0, 0.5, 1.0, 64.5625, 1.0);

        assertTrue(!VehicleClearanceBox.blocks(vehicle, VehicleClearanceBox.around(vehicle), marginOnlyBed, true),
                "the connected home bed may occupy only the additional policy margin");
        assertTrue(VehicleClearanceBox.blocks(vehicle, VehicleClearanceBox.around(vehicle), intersectingBed, true),
                "the connected home bed must block the actual translated vehicle box");
    }

    private static void configRejectsInvalidOperatorInputPrecisely() {
        new OmwhConfig().validate();

        OmwhConfig duplicate = new OmwhConfig();
        duplicate.spawnCommand = duplicate.homeCommand;
        assertThrows(IllegalArgumentException.class, duplicate::validate, "command names must be distinct");

        OmwhConfig malformed = new OmwhConfig();
        malformed.homeCommand = "home now";
        assertThrows(IllegalArgumentException.class, malformed::validate, "command names must be one literal");

        OmwhConfig negative = new OmwhConfig();
        negative.damageCooldownSeconds = -1;
        assertThrows(IllegalArgumentException.class, negative::validate, "durations cannot be negative");

        OmwhConfig missingMessage = new OmwhConfig();
        missingMessage.homeSuccessMessage = null;
        Throwable error = assertThrows(IllegalArgumentException.class, missingMessage::validate,
                "required messages cannot be null");
        assertTrue(error.getMessage().contains("homeSuccessMessage"),
                "null message validation must name the invalid field");
    }

    private static void nearestSearchOrderIsLazyAndDeterministic() {
        var offsets = SafeLocationPlanner.nearestOffsets(64, -2, 10).iterator();
        List<SafeLocationPlanner.Pos> first = new ArrayList<>();
        for (int i = 0; i < 7; i++) first.add(offsets.next());
        assertEquals(List.of(
                new SafeLocationPlanner.Pos(0, 0, 0),
                new SafeLocationPlanner.Pos(-1, 0, 0),
                new SafeLocationPlanner.Pos(0, 0, -1),
                new SafeLocationPlanner.Pos(0, 0, 1),
                new SafeLocationPlanner.Pos(1, 0, 0),
                new SafeLocationPlanner.Pos(0, -1, 0),
                new SafeLocationPlanner.Pos(0, 1, 0)), first,
                "nearest offsets must preserve the specified prefix");

        List<SafeLocationPlanner.Pos> expected = new ArrayList<>();
        for (int y = -2; y <= 3; y++) {
            for (int x = -3; x <= 3; x++) {
                for (int z = -3; z <= 3; z++) expected.add(new SafeLocationPlanner.Pos(x, y, z));
            }
        }
        Comparator<SafeLocationPlanner.Pos> order = Comparator
                .comparingInt((SafeLocationPlanner.Pos pos) -> pos.x() * pos.x() + pos.y() * pos.y() + pos.z() * pos.z())
                .thenComparingInt(pos -> Math.abs(pos.y()))
                .thenComparingInt(SafeLocationPlanner.Pos::y)
                .thenComparingInt(SafeLocationPlanner.Pos::x)
                .thenComparingInt(SafeLocationPlanner.Pos::z);
        expected.sort(order);
        List<SafeLocationPlanner.Pos> actual = new ArrayList<>();
        SafeLocationPlanner.nearestOffsets(3, -2, 3).forEach(actual::add);
        assertEquals(expected, actual, "the complete small search must match an independently sorted reference");

        long started = System.nanoTime();
        Set<SafeLocationPlanner.Pos> unique = new HashSet<>();
        int count = 0;
        for (SafeLocationPlanner.Pos pos : SafeLocationPlanner.nearestOffsets(64, -2, 10)) {
            count++;
            assertTrue(unique.add(pos), "radius-64 search must not repeat " + pos);
            assertTrue(Math.abs(pos.x()) <= 64 && Math.abs(pos.z()) <= 64
                            && pos.y() >= -2 && pos.y() <= 10,
                    "radius-64 search must stay within bounds");
        }
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
        assertEquals(216_333, count, "radius-64 search must emit every actual coordinate exactly once");
        assertTrue(elapsedMillis < 1_000,
                "radius-64 enumeration took " + elapsedMillis + " ms; search enumeration regressed pathologically");
        System.out.println("SafeLocationPlanner radius-64 enumeration: " + elapsedMillis + " ms for " + count + " candidates");
    }

    private static void cooldownStateHasOneAuthoritativeLookupAndLongestRestriction() {
        OmwhConfig config = new OmwhConfig();
        config.regularCooldownSeconds = 60;
        AtomicLong now = new AtomicLong(1_000L);
        CooldownManager cooldowns = new CooldownManager(config, now::get);
        UUID player = UUID.randomUUID();

        cooldowns.recordDamage(player);
        cooldowns.recordJoin(player);
        assertEquals(CooldownManager.Type.JOIN, cooldowns.restriction(player).type(),
                "the longer join restriction must replace shorter damage");
        cooldowns.recordPvp(player);
        assertEquals(CooldownManager.Type.PVP, cooldowns.restriction(player).type(),
                "the longer PvP restriction must replace join");

        cooldowns.recordRegular(player);
        now.set(46_000L);
        assertEquals(CooldownManager.Type.REGULAR, cooldowns.restriction(player).type(),
                "regular cooldown remains independent after combat expires");
        cooldowns.clear(player);
        assertEquals(CooldownManager.Type.NONE, cooldowns.restriction(player).type(),
                "disconnect cleanup removes all state for the player");
    }

    private static void cooldownRoundingAndTiePrecedenceAreStable() {
        OmwhConfig config = new OmwhConfig();
        config.damageCooldownSeconds = 45;
        config.pvpCooldownSeconds = 45;
        AtomicLong now = new AtomicLong(1_000L);
        CooldownManager cooldowns = new CooldownManager(config, now::get);
        UUID player = UUID.randomUUID();

        cooldowns.recordDamage(player);
        cooldowns.recordPvp(player);
        assertEquals(CooldownManager.Type.PVP, cooldowns.restriction(player).type(),
                "equal expiry uses the command admission precedence");

        cooldowns.clear(player);
        cooldowns.recordJoin(player);
        now.incrementAndGet();
        assertEquals(30, cooldowns.restriction(player).remainingSeconds(),
                "a partial final second must be shown as remaining");
    }

    private static Throwable assertThrows(Class<? extends Throwable> type, Runnable action, String message) {
        try {
            action.run();
        } catch (Throwable thrown) {
            if (type.isInstance(thrown)) return thrown;
            throw new AssertionError(message + "; unexpected exception=" + thrown, thrown);
        }
        throw new AssertionError(message + "; expected exception=" + type.getSimpleName());
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + "; expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private record Node(String name) { }
}
