package xyz.pyrehaven.omwh;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

public final class TeleportServiceTest {
    private static final TeleportService.EntityTree<Node> NODE_TREE = new NodeTree();

    public static void main(String[] args) {
        sameDimensionUsesOneRootMutationAndPreservesEveryIdentity();
        crossDimensionReconcilesReplacementEntitiesAndOriginalPlayers();
        invalidSourceTreesAreRejectedBeforeMutation();
        incompleteOrInvalidMovedTreesFailAfterOneMutation();
        nullAndExceptionFailuresNeverRetry();
        trackingRefreshRunsOncePerReconciledEntityAndNeverOnFailure();
        reconciledPlayerNotificationsExcludeTheCommandPlayer();
        System.out.println("TeleportServiceTest PASS (7 behavior groups)");
    }

    private static void sameDimensionUsesOneRootMutationAndPreservesEveryIdentity() {
        Object level = new Object();
        Node root = Node.entity("root", level);
        Node rider = root.add(Node.player("rider", level));
        rider.player = false;
        rider.add(Node.player("passenger", level));
        AtomicInteger teleports = new AtomicInteger();
        AtomicInteger velocityClears = new AtomicInteger();

        TeleportService.Attempt<Node> attempt = attempt(root, level, level, true, node -> {
            teleports.incrementAndGet();
            return node;
        }, velocityClears);

        check(attempt.success(), "same-dimension tree accepted");
        check(attempt.entities().equals(List.of(root, rider, rider.children.getFirst())),
                "same-dimension identities preserved");
        check(teleports.get() == 1, "same-dimension root teleported exactly once");
        check(velocityClears.get() == 1, "moved root velocity cleared once");
    }

    private static void crossDimensionReconcilesReplacementEntitiesAndOriginalPlayers() {
        Object source = new Object();
        Object destination = new Object();
        Node root = Node.entity("root", source);
        Node branch = root.add(Node.entity("branch", source));
        Node player = branch.add(Node.player("player", source));
        AtomicInteger teleports = new AtomicInteger();
        AtomicInteger velocityClears = new AtomicInteger();

        TeleportService.Attempt<Node> attempt = attempt(root, source, destination, false, ignored -> {
            teleports.incrementAndGet();
            Node movedRoot = root.replacement(destination);
            Node movedBranch = movedRoot.add(branch.replacement(destination));
            player.level = destination;
            movedBranch.add(player);
            return movedRoot;
        }, velocityClears);

        check(attempt.success(), "cross-dimension replacement tree accepted");
        check(attempt.entities().getFirst() != root && attempt.entities().get(1) != branch,
                "non-player identities may change across dimensions");
        check(attempt.entities().get(2) == player, "original player identity preserved");
        check(teleports.get() == 1, "cross-dimension root teleported exactly once");
        check(velocityClears.get() == 1, "replacement root velocity cleared once");
    }

    private static void invalidSourceTreesAreRejectedBeforeMutation() {
        Object source = new Object();
        Object destination = new Object();

        Node cycle = Node.entity("cycle", source);
        cycle.children.add(cycle);
        rejectBeforeMutation(cycle, source, destination, "cycle");

        Node duplicateRoot = Node.entity("duplicate-root", source);
        Node duplicate = Node.entity("duplicate", source);
        duplicate.id = duplicateRoot.id;
        duplicateRoot.add(duplicate);
        rejectBeforeMutation(duplicateRoot, source, destination, "duplicate UUID");

        Node nullUuid = Node.entity("null-uuid", source);
        nullUuid.id = null;
        rejectBeforeMutation(nullUuid, source, destination, "null UUID");

        Node removed = Node.entity("removed", source);
        removed.removed = true;
        rejectBeforeMutation(removed, source, destination, "removed source");

        Node nonServer = Node.entity("non-server", source);
        nonServer.server = false;
        rejectBeforeMutation(nonServer, source, destination, "non-server source");

        Node wrongLevel = Node.entity("root", source);
        wrongLevel.add(Node.entity("wrong-level", new Object()));
        rejectBeforeMutation(wrongLevel, source, destination, "wrong source level");

        Node playerParent = Node.player("player-parent", source);
        playerParent.add(Node.entity("unsupported-child", source));
        rejectBeforeMutation(playerParent, source, destination, "player with passenger");
    }

    private static void incompleteOrInvalidMovedTreesFailAfterOneMutation() {
        Object source = new Object();
        Object destination = new Object();

        assertPostconditionFailure(source, destination, "missing UUID", original ->
                original.replacement(destination));
        assertPostconditionFailure(source, destination, "extra UUID", original -> {
            Node moved = movedCopy(original, destination, true);
            moved.add(Node.entity("extra", destination));
            return moved;
        });
        assertPostconditionFailure(source, destination, "duplicate moved UUID", original -> {
            Node moved = movedCopy(original, destination, true);
            Node duplicate = Node.entity("duplicate", destination);
            duplicate.id = moved.children.getFirst().id;
            moved.add(duplicate);
            return moved;
        });
        assertPostconditionFailure(source, destination, "wrong root UUID", original -> {
            Node moved = movedCopy(original, destination, true);
            moved.id = UUID.randomUUID();
            return moved;
        });
        assertPostconditionFailure(source, destination, "wrong parent", original -> {
            Node moved = movedCopy(original, destination, true);
            Node child = moved.children.getFirst();
            moved.children.clear();
            child.parent = null;
            moved.children.add(child);
            return moved;
        });
        assertChangedUuidEdgesFail(source, destination);
        assertPostconditionFailure(source, destination, "wrong destination level", original -> {
            Node moved = movedCopy(original, destination, true);
            moved.children.getFirst().level = source;
            return moved;
        });
        assertPostconditionFailure(source, destination, "removed moved member", original -> {
            Node moved = movedCopy(original, destination, true);
            moved.children.getFirst().removed = true;
            return moved;
        });
        assertPostconditionFailure(source, destination, "changed player identity", original ->
                movedCopy(original, destination, false));

        Node sameRoot = validSource(source);
        AtomicInteger sameTeleports = new AtomicInteger();
        AtomicInteger sameClears = new AtomicInteger();
        TeleportService.Attempt<Node> changedIdentity = attempt(sameRoot, source, source, true, ignored -> {
            sameTeleports.incrementAndGet();
            return movedCopy(sameRoot, source, true);
        }, sameClears);
        check(!changedIdentity.success(), "same-dimension replacement identity rejected");
        check(sameTeleports.get() == 1 && sameClears.get() == 0,
                "same-dimension identity failure has one mutation and no completion");
    }

    private static void nullAndExceptionFailuresNeverRetry() {
        Object source = new Object();
        Object destination = new Object();
        Node root = validSource(source);
        AtomicInteger nullCalls = new AtomicInteger();
        TeleportService.Attempt<Node> nullAttempt = attempt(root, source, destination, false, ignored -> {
            nullCalls.incrementAndGet();
            return null;
        }, new AtomicInteger());
        check(nullAttempt.outcome() == TeleportService.Outcome.FAILED,
                "null result without a moved root is a failed invariant");
        check(nullAttempt.entities().isEmpty(), "null result exposes no pre-mutation passenger entities");
        check(nullCalls.get() == 1, "null result never retried");

        AtomicInteger exceptionCalls = new AtomicInteger();
        TeleportService.Attempt<Node> exceptionAttempt = attempt(root, source, destination, false, ignored -> {
            exceptionCalls.incrementAndGet();
            throw new IllegalStateException("mutation");
        }, new AtomicInteger());
        check(exceptionAttempt.outcome() == TeleportService.Outcome.FAILED,
                "mutation exception without a moved root is a failed invariant");
        check(exceptionAttempt.entities().isEmpty(),
                "mutation exception exposes no pre-mutation passenger entities");
        check(exceptionCalls.get() == 1, "exception never retried");
    }

    private static void trackingRefreshRunsOncePerReconciledEntityAndNeverOnFailure() {
        Object destination = new Object();
        Node vehicle = Node.entity("vehicle", destination);
        Node branch = Node.entity("branch", destination);
        Node driver = Node.player("driver", destination);
        Node rider = Node.player("rider", destination);
        List<Node> moved = List.of(vehicle, branch, driver, rider);
        List<String> refreshes = new ArrayList<>();

        TeleportService.refreshTracking(
                new TeleportService.Attempt<>(TeleportService.Outcome.SUCCESS, moved), NODE_TREE,
                node -> refreshes.add("player:" + node.name),
                node -> refreshes.add("entity:" + node.name));
        check(refreshes.equals(List.of("player:driver", "player:rider", "entity:vehicle", "entity:branch")),
                "player chunk views refresh before the reconciled parent-first entity tree");

        refreshes.clear();
        TeleportService.refreshTracking(
                new TeleportService.Attempt<>(TeleportService.Outcome.FAILED, moved), NODE_TREE,
                node -> refreshes.add("player"), node -> refreshes.add("entity"));
        TeleportService.refreshTracking(
                new TeleportService.Attempt<>(TeleportService.Outcome.PARTIAL, moved), NODE_TREE,
                node -> refreshes.add("player"), node -> refreshes.add("entity"));
        check(refreshes.isEmpty(), "failed and partial teleports never refresh entity tracking");
    }

    private static void reconciledPlayerNotificationsExcludeTheCommandPlayer() {
        Object destination = new Object();
        Node commandPlayer = Node.player("command", destination);
        Node passenger = Node.player("passenger", destination);
        Node vehicle = Node.entity("vehicle", destination);
        check(TeleportService.passengerPlayers(List.of(vehicle, commandPlayer, passenger),
                        NODE_TREE, commandPlayer).equals(List.of(passenger)),
                "notifications use reconciled moved players except the command player");
    }

    private static void rejectBeforeMutation(Node root, Object source, Object destination, String behavior) {
        AtomicInteger teleports = new AtomicInteger();
        TeleportService.Attempt<Node> attempt = attempt(root, source, destination, false, node -> {
            teleports.incrementAndGet();
            return node;
        }, new AtomicInteger());
        check(attempt.outcome() == TeleportService.Outcome.FAILED, behavior + " rejected as failed");
        check(attempt.entities().isEmpty(), behavior + " exposes no passenger entities");
        check(teleports.get() == 0, behavior + " rejected before mutation");
    }

    private static void assertPostconditionFailure(Object source, Object destination, String behavior,
                                                   Function<Node, Node> teleporter) {
        Node root = validSource(source);
        AtomicInteger teleports = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();
        TeleportService.Attempt<Node> attempt = attempt(root, source, destination, false, node -> {
            teleports.incrementAndGet();
            return teleporter.apply(node);
        }, clears);
        check(attempt.outcome() == TeleportService.Outcome.PARTIAL,
                behavior + " reported as a partial post-mutation failure");
        check(teleports.get() == 1, behavior + " follows exactly one mutation");
        check(clears.get() == 0, behavior + " suppresses completion mutation");
    }

    private static void assertChangedUuidEdgesFail(Object source, Object destination) {
        Node root = Node.entity("root", source);
        Node left = root.add(Node.entity("left", source));
        Node right = root.add(Node.entity("right", source));
        Node player = left.add(Node.player("player", source));
        AtomicInteger teleports = new AtomicInteger();
        AtomicInteger clears = new AtomicInteger();

        TeleportService.Attempt<Node> attempt = attempt(root, source, destination, false, ignored -> {
            teleports.incrementAndGet();
            Node movedRoot = root.replacement(destination);
            movedRoot.add(left.replacement(destination));
            Node movedRight = movedRoot.add(right.replacement(destination));
            player.level = destination;
            movedRight.add(player);
            return movedRoot;
        }, clears);

        check(!attempt.success(), "changed UUID edge set rejected");
        check(teleports.get() == 1 && clears.get() == 0,
                "changed UUID edge set has one mutation and no completion");
    }

    private static TeleportService.Attempt<Node> attempt(Node root, Object source, Object destination,
                                                         boolean sameDimension, Function<Node, Node> teleporter,
                                                         AtomicInteger velocityClears) {
        return TeleportService.attempt(root, source, destination, sameDimension, NODE_TREE,
                teleporter, node -> velocityClears.incrementAndGet());
    }

    private static Node validSource(Object source) {
        Node root = Node.entity("root", source);
        root.add(Node.player("player", source));
        return root;
    }

    private static Node movedCopy(Node original, Object destination, boolean preservePlayerIdentity) {
        Node moved = original.replacement(destination);
        for (Node child : original.children) {
            Node movedChild;
            if (child.player && preservePlayerIdentity) {
                child.level = destination;
                movedChild = child;
            } else {
                movedChild = child.replacement(destination);
            }
            moved.add(movedChild);
        }
        return moved;
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }

    private static final class Node {
        private UUID id = UUID.randomUUID();
        private final String name;
        private final List<Node> children = new ArrayList<>();
        private Node parent;
        private boolean player;
        private boolean server = true;
        private boolean removed;
        private Object level;

        private Node(String name, Object level, boolean player) {
            this.name = name;
            this.level = level;
            this.player = player;
        }

        private static Node entity(String name, Object level) {
            return new Node(name, level, false);
        }

        private static Node player(String name, Object level) {
            return new Node(name, level, true);
        }

        private Node add(Node child) {
            child.parent = this;
            children.add(child);
            return child;
        }

        private Node replacement(Object destination) {
            Node replacement = new Node(name, destination, player);
            replacement.id = id;
            return replacement;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    private static final class NodeTree implements TeleportService.EntityTree<Node> {
        @Override
        public UUID uuid(Node node) {
            return node.id;
        }

        @Override
        public List<Node> children(Node node) {
            return node.children;
        }

        @Override
        public Node parent(Node node) {
            return node.parent;
        }

        @Override
        public boolean isPlayer(Node node) {
            return node.player;
        }

        @Override
        public boolean isServerSide(Node node) {
            return node.server;
        }

        @Override
        public boolean isRemoved(Node node) {
            return node.removed;
        }

        @Override
        public Object level(Node node) {
            return node.level;
        }
    }
}
