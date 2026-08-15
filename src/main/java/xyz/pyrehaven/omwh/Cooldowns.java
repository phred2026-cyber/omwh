package xyz.pyrehaven.omwh;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

public final class Cooldowns {
    public enum Type { PVP, DAMAGE, JOIN, REGULAR, NONE }
    public record Blocking(Type type, int remainingSeconds) { }
    private record State(Type eventType, long eventExpiry, long regularExpiry) { }

    private final OmwhConfig config;
    private final LongSupplier clock;
    private final ConcurrentHashMap<UUID, State> players = new ConcurrentHashMap<>();

    Cooldowns(OmwhConfig config) {
        this(config, System::currentTimeMillis);
    }

    Cooldowns(OmwhConfig config, LongSupplier clock) {
        this.config = config;
        this.clock = clock;
    }

    void recordRegular(UUID player) {
        if (!config.enableRegularCooldown || config.regularCooldownSeconds == 0) return;
        long expiry = clock.getAsLong() + milliseconds(config.regularCooldownSeconds);
        players.compute(player, (id, old) -> new State(
                old == null ? Type.NONE : old.eventType,
                old == null ? 0 : old.eventExpiry,
                expiry));
    }

    void recordIncomingDamageAllowedByOmwh(UUID victim, UUID playerAttacker) {
        if (playerAttacker != null) {
            if (!config.enablePvpCooldown) return;
            recordEvent(victim, Type.PVP, config.pvpCooldownSeconds);
            recordEvent(playerAttacker, Type.PVP, config.pvpCooldownSeconds);
        } else if (config.enableDamageCooldown) {
            recordEvent(victim, Type.DAMAGE, config.damageCooldownSeconds);
        }
    }

    void recordJoin(UUID player) {
        recordEvent(player, Type.JOIN, config.joinCooldownSeconds);
    }

    Blocking blocking(UUID player) {
        long now = clock.getAsLong();
        State state = players.get(player);
        if (state == null) return new Blocking(Type.NONE, 0);
        if (state.eventExpiry > now) return remaining(state.eventType, state.eventExpiry, now);
        if (state.regularExpiry > now) return remaining(Type.REGULAR, state.regularExpiry, now);
        players.remove(player, state);
        return new Blocking(Type.NONE, 0);
    }

    private void recordEvent(UUID player, Type type, int durationSeconds) {
        if (durationSeconds == 0) return;
        long expiry = clock.getAsLong() + milliseconds(durationSeconds);
        players.compute(player, (id, old) -> {
            long regular = old == null ? 0 : old.regularExpiry;
            if (old != null && (old.eventExpiry > expiry
                    || old.eventExpiry == expiry && priority(old.eventType) >= priority(type))) {
                return old;
            }
            return new State(type, expiry, regular);
        });
    }

    private static Blocking remaining(Type type, long expiry, long now) {
        return new Blocking(type, (int) ((expiry - now + 999L) / 1000L));
    }

    private static long milliseconds(int seconds) {
        return seconds * 1000L;
    }

    private static int priority(Type type) {
        return switch (type) {
            case PVP -> 4;
            case DAMAGE -> 3;
            case JOIN -> 2;
            case REGULAR -> 1;
            case NONE -> 0;
        };
    }
}
