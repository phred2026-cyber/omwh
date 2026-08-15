package com.omwh.utils;

/** Builds the exact free-space volume required around a mounted vehicle at /home. */
public final class VehicleClearanceBox {
    public static final double HORIZONTAL_MARGIN = 0.5;
    public static final double UPPER_MARGIN = 1.5;

    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) { }

    private VehicleClearanceBox() { }

    public static Bounds around(Bounds vehicle) {
        return new Bounds(
                vehicle.minX() - HORIZONTAL_MARGIN,
                vehicle.minY(),
                vehicle.minZ() - HORIZONTAL_MARGIN,
                vehicle.maxX() + HORIZONTAL_MARGIN,
                vehicle.maxY() + UPPER_MARGIN,
                vehicle.maxZ() + HORIZONTAL_MARGIN);
    }

    public static boolean withinBuildHeight(Bounds box, int minY, int maxY) {
        return box.minY() >= minY && box.maxY() <= (double) maxY + 1.0;
    }

    public static boolean blocks(Bounds vehicle, Bounds policy, Bounds obstacle, boolean homeBedPart) {
        if (intersects(vehicle, obstacle)) return true;
        return !homeBedPart && intersects(policy, obstacle);
    }

    private static boolean intersects(Bounds first, Bounds second) {
        return second.maxX() > first.minX() && second.minX() < first.maxX()
                && second.maxY() > first.minY() && second.minY() < first.maxY()
                && second.maxZ() > first.minZ() && second.minZ() < first.maxZ();
    }
}
