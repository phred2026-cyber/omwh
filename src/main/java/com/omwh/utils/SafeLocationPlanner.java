package com.omwh.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public final class SafeLocationPlanner {
    private SafeLocationPlanner() { }

    public record Pos(int x, int y, int z) { }
    private record Horizontal(int x, int z, long squaredDistance) { }

    static Iterable<Pos> nearestOffsets(int radius, int minY, int maxY) {
        if (radius < 0 || minY > maxY) throw new IllegalArgumentException("invalid search bounds");

        List<Horizontal> horizontal = new ArrayList<>((2 * radius + 1) * (2 * radius + 1));
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                horizontal.add(new Horizontal(x, z, (long) x * x + (long) z * z));
            }
        }
        horizontal.sort(Comparator.comparingLong(Horizontal::squaredDistance)
                .thenComparingInt(Horizontal::x)
                .thenComparingInt(Horizontal::z));

        return () -> new Iterator<>() {
            private final PriorityQueue<Cursor> cursors = createCursors();

            private PriorityQueue<Cursor> createCursors() {
                PriorityQueue<Cursor> queue = new PriorityQueue<>(Comparator
                        .comparingLong(Cursor::squaredDistance)
                        .thenComparingInt(cursor -> Math.abs(cursor.y))
                        .thenComparingInt(cursor -> cursor.y)
                        .thenComparingInt(cursor -> cursor.current().x())
                        .thenComparingInt(cursor -> cursor.current().z()));
                for (int y = minY; y <= maxY; y++) queue.add(new Cursor(y));
                return queue;
            }

            @Override
            public boolean hasNext() {
                return !cursors.isEmpty();
            }

            @Override
            public Pos next() {
                Cursor cursor = cursors.poll();
                if (cursor == null) throw new NoSuchElementException();
                Horizontal current = cursor.current();
                Pos result = new Pos(current.x(), cursor.y, current.z());
                cursor.index++;
                if (cursor.index < horizontal.size()) cursors.add(cursor);
                return result;
            }

            final class Cursor {
                private final int y;
                private int index;

                private Cursor(int y) {
                    this.y = y;
                }

                private Horizontal current() {
                    return horizontal.get(index);
                }

                private long squaredDistance() {
                    return current().squaredDistance() + (long) y * y;
                }
            }
        };
    }
}
