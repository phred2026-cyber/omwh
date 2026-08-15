package com.omwh.utils;

/** Pure policy for accepting the exact destination selected by vanilla respawn logic. */
public final class HomeRespawnDecision {
    public enum Outcome { ACCEPT, NO_HOME, CROSS_DIMENSION }

    private HomeRespawnDecision() { }

    public static Outcome decide(boolean missingRespawnBlock, boolean sameDimension) {
        if (missingRespawnBlock) return Outcome.NO_HOME;
        if (!sameDimension) return Outcome.CROSS_DIMENSION;
        return Outcome.ACCEPT;
    }
}
