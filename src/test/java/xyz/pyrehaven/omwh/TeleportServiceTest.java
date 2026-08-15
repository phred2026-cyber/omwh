package xyz.pyrehaven.omwh;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class TeleportServiceTest {
    public static void main(String[] args) {
        recursiveRootMutationPreservesAttachmentsAndClearsVelocity();
        nullExceptionAndChangedAttachmentsAreFailures();
        System.out.println("TeleportServiceTest PASS (2 behavior groups)");
    }

    private static void recursiveRootMutationPreservesAttachmentsAndClearsVelocity() {
        Node root = new Node("root");
        Node rider = root.add("rider");
        rider.add("passenger");
        AtomicInteger teleports = new AtomicInteger();
        AtomicInteger velocityClears = new AtomicInteger();

        TeleportService.Attempt<Node> attempt = TeleportService.attempt(root, node -> node.children,
                node -> node.parent, node -> {
                    teleports.incrementAndGet();
                    return node;
                }, node -> velocityClears.incrementAndGet());

        check(attempt.success(), "successful recursive teleport");
        check(attempt.entities().size() == 3, "complete recursive tree captured");
        check(teleports.get() == 1, "root teleported exactly once");
        check(velocityClears.get() == 1, "root velocity cleared");
    }

    private static void nullExceptionAndChangedAttachmentsAreFailures() {
        Node nullRoot = new Node("null");
        check(!TeleportService.attempt(nullRoot, node -> node.children, node -> node.parent,
                node -> null, node -> { }).success(), "null result");

        Node throwing = new Node("throwing");
        check(!TeleportService.attempt(throwing, node -> node.children, node -> node.parent,
                node -> { throw new IllegalStateException("mutation"); }, node -> { }).success(), "runtime exception");

        Node root = new Node("root");
        Node child = root.add("child");
        check(!TeleportService.attempt(root, node -> node.children, node -> node.parent,
                node -> { child.parent = null; return node; }, node -> { }).success(), "changed attachment");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }

    private static final class Node {
        private final String name;
        private final List<Node> children = new ArrayList<>();
        private Node parent;

        private Node(String name) {
            this.name = name;
        }

        private Node add(String childName) {
            Node child = new Node(childName);
            child.parent = this;
            children.add(child);
            return child;
        }

        @Override
        public String toString() {
            return name;
        }
    }
}
