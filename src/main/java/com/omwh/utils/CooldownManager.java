package com.omwh.utils;

import com.omwh.config.OmwhConfig;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class CooldownManager {
    public enum Type { PVP, DAMAGE, JOIN, REGULAR, NONE }
    public record Restriction(Type type, int remainingSeconds) { }
    private record State(Type timedType, long timedExpiry, long regularExpiry) { }

    private final OmwhConfig config;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();

    public CooldownManager(OmwhConfig config) {
        this(config, System::currentTimeMillis);
    }

    CooldownManager(OmwhConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    public void recordRegular(UUID player) {
        if (!config.enableRegularCooldown || config.regularCooldownSeconds == 0) return;
        long expiry = clock.getAsLong() + seconds(config.regularCooldownSeconds);
        players.compute(player, (id, old) -> new State(
                old == null ? Type.NONE : old.timedType,
                old == null ? 0 : old.timedExpiry,
                expiry));
    }

    public void recordPvp(UUID player) {
        if (config.enablePvpCooldown) recordTimed(player, Type.PVP, config.pvpCooldownSeconds);
    }

    public void recordDamage(UUID player) {
        if (config.enableDamageCooldown) recordTimed(player, Type.DAMAGE, config.damageCooldownSeconds);
    }

    public void recordJoin(UUID player) {
        recordTimed(player, Type.JOIN, config.joinCooldownSeconds);
    }

    private void recordTimed(UUID player, Type type, int duration) {
        if (duration == 0) return;
        long expiry = clock.getAsLong() + seconds(duration);
        players.compute(player, (id, old) -> {
            long regular = old == null ? 0 : old.regularExpiry;
            if (old != null && (old.timedExpiry > expiry
                    || old.timedExpiry == expiry && priority(old.timedType) >= priority(type))) {
                return old;
            }
            return new State(type, expiry, regular);
        });
    }

    public Restriction restriction(UUID player) {
        long now = clock.getAsLong();
        State state = players.get(player);
        if (state == null) return new Restriction(Type.NONE, 0);
        if (state.timedExpiry > now) return remaining(state.timedType, state.timedExpiry, now);
        if (state.regularExpiry > now) return remaining(Type.REGULAR, state.regularExpiry, now);
        players.remove(player, state);
        return new Restriction(Type.NONE, 0);
    }

    private static Restriction remaining(Type type, long expiry, long now) {
        long milliseconds = Math.max(0, expiry - now);
        return new Restriction(type, (int) ((milliseconds + 999L) / 1000L));
    }

    public void clear(UUID player) {
        players.remove(player);
    }

    private static long seconds(int value) {
        return value * 1000L;
    }

    private static int priority(Type type) {
        return switch (type) {
            case PVP -> 3;
            case DAMAGE -> 2;
            case JOIN -> 1;
            case REGULAR, NONE -> 0;
        };
    }
}
