package xyz.pyrehaven.omwh;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public final class Commands {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    private static final String INTERNAL_ERROR = "§cInternal error executing /%s. Check server log.";
    private static final String VEHICLE_TOO_LARGE = "§cYour vehicle is too big. Please dismount and try again.";
    private static final String PARTIAL_TELEPORT = "§eTeleport may have partially completed, but OMWH could not verify every passenger attachment. Check your group before moving again.";
    static final String SPAWN_DISABLED = "§cSpawn teleporting is disabled for this dimension.";
    static final String SPAWN_PENDING = "§eA /spawn safety search is already in progress.";
    static final String SPAWN_ANCHOR_CHANGED = "§cWorld spawn changed while OMWH was checking safety. Please try /spawn again.";
    static final int SEARCH_CANDIDATES_PER_TICK = 4_096;
    static final int SEARCH_WORLD_WORK_PER_TICK = 4_096;
    private final OmwhConfig config;
    private final Cooldowns cooldowns;
    private final PendingSearches<UUID, SpawnCompletion> pendingSpawns = new PendingSearches<>();
    private long pendingTickEpoch;

    @FunctionalInterface
    interface PendingWork<V> { PendingStep<V> step(int candidateBudget, int worldWorkBudget); }

    record PendingStep<V>(boolean complete, V value, int candidatesUsed, int worldWorkUsed) {
        static <V> PendingStep<V> pending(int candidatesUsed, int worldWorkUsed) {
            return new PendingStep<>(false, null, candidatesUsed, worldWorkUsed);
        }
        static <V> PendingStep<V> complete(V value, int candidatesUsed, int worldWorkUsed) {
            return new PendingStep<>(true, value, candidatesUsed, worldWorkUsed);
        }
    }
    record PendingTick(int candidatesUsed, int worldWorkUsed, int itemsCompleted) { }
    enum PendingSpawnAction { CONTINUE, REFUSE, CANCEL_AND_CONTINUE }

    static final class PendingSearches<K, V> {
        private final Map<K, PendingWork<V>> searches = new HashMap<>();
        private final Deque<K> roundRobin = new ArrayDeque<>();

        boolean add(K key, PendingWork<V> work) {
            if (searches.putIfAbsent(key, work) != null) return false;
            roundRobin.addLast(key);
            return true;
        }
        boolean contains(K key) { return searches.containsKey(key); }
        void remove(K key) { searches.remove(key); roundRobin.remove(key); }
        void clear() { searches.clear(); roundRobin.clear(); }
        int size() { return searches.size(); }

        PendingTick tick(int candidateBudget, int worldWorkBudget, Consumer<V> completion) {
            int candidatesUsed = 0;
            int worldWorkUsed = 0;
            int itemsCompleted = 0;
            while (!roundRobin.isEmpty() && candidatesUsed < candidateBudget && worldWorkUsed < worldWorkBudget) {
                K key = roundRobin.removeFirst();
                PendingWork<V> work = searches.get(key);
                if (work == null) continue;
                int candidateSlice = Math.min(1, candidateBudget - candidatesUsed);
                int worldSlice = Math.min(64, worldWorkBudget - worldWorkUsed);
                PendingStep<V> step = work.step(candidateSlice, worldSlice);
                if (step.candidatesUsed < 0 || step.candidatesUsed > candidateSlice
                        || step.worldWorkUsed < 0 || step.worldWorkUsed > worldSlice) {
                    throw new IllegalStateException("pending search exceeded its shared allowance");
                }
                candidatesUsed += step.candidatesUsed;
                worldWorkUsed += step.worldWorkUsed;
                if (step.complete) {
                    searches.remove(key);
                    itemsCompleted++;
                    completion.accept(step.value);
                } else {
                    roundRobin.addLast(key);
                }
                if (!step.complete && step.candidatesUsed == 0 && step.worldWorkUsed == 0) break;
            }
            return new PendingTick(candidatesUsed, worldWorkUsed, itemsCompleted);
        }
    }

    private record SpawnCompletion(ServerPlayer player, TeleportService.LifecycleFence<Entity> lifecycle,
                                   SpawnDestination.Result result, boolean cancelled) { }

    Commands(OmwhConfig config, Cooldowns cooldowns) {
        this.config = config;
        this.cooldowns = cooldowns;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> home = net.minecraft.commands.Commands.literal(config.homeCommand)
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> executeHome(context.getSource().getPlayer(), false));
        LiteralArgumentBuilder<CommandSourceStack> spawn = net.minecraft.commands.Commands.literal(config.spawnCommand)
                .requires(source -> source.getEntity() instanceof ServerPlayer)
                .executes(context -> executeSpawn(context.getSource().getPlayer(), false));
        if (config.enableForceOverride) {
            home.then(net.minecraft.commands.Commands.literal("force")
                    .executes(context -> executeHome(context.getSource().getPlayer(), true)));
            spawn.then(net.minecraft.commands.Commands.literal("force")
                    .executes(context -> executeSpawn(context.getSource().getPlayer(), true)));
        }
        dispatcher.register(home);
        dispatcher.register(spawn);
    }

    static String cooldownMessage(OmwhConfig config, Cooldowns.Blocking blocking) {
        String message = switch (blocking.type()) {
            case PVP -> config.pvpCooldownMessage;
            case DAMAGE -> config.damageCooldownMessage;
            case JOIN -> config.joinCooldownMessage;
            case REGULAR -> config.regularCooldownMessage;
            case NONE -> throw new IllegalArgumentException("NONE has no cooldown message");
        };
        return message.replace("{time}", Integer.toString(blocking.remainingSeconds()));
    }

    static String passengerMessage(String playerName, boolean home) {
        return "§e" + playerName + " teleported you with their vehicle to "
                + (home ? "their home" : "spawn") + ".";
    }

    static String unsafeMessage(String configuredMessage, String commandName, boolean forceEnabled) {
        if (!forceEnabled) return configuredMessage;
        return configuredMessage + "\n§eUse /" + commandName + " force to teleport anyway.";
    }

    static String teleportFailureMessage(TeleportService.Result result, String commandName) {
        return result.partial() ? PARTIAL_TELEPORT : INTERNAL_ERROR.formatted(commandName);
    }

    static boolean continuesTeleportCompletion(TeleportService.Result result) {
        return result.success() || result.partial();
    }

    private int executeHome(ServerPlayer player, boolean force) {
        if (player == null) return 0;
        pendingSpawns.remove(player.getUUID());
        try {
            if (!admit(player)) return 0;
            HomeDestination.Result destination = HomeDestination.find(
                    player, force, config.enableCrossDimensionTeleport);
            switch (destination.outcome()) {
                case NO_HOME -> send(player, config.noHomepointMessage);
                case CROSS_DIMENSION -> send(player, config.crossDimensionMessage);
                case VEHICLE_TOO_LARGE -> send(player, VEHICLE_TOO_LARGE);
                case UNSAFE -> send(player, unsafeMessage(
                        config.unsafeHomeMessage, config.homeCommand, config.enableForceOverride));
                case ACCEPT -> { return teleport(player, destination.destination(), true, null, false); }
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected /home failure for {}", player.getGameProfile().name(), failure);
            sendSafely(player, INTERNAL_ERROR.formatted(config.homeCommand));
        }
        return 0;
    }

    private int executeSpawn(ServerPlayer player, boolean force) {
        if (player == null) return 0;
        PendingSpawnAction pendingAction = pendingSpawnAction(pendingSpawns.contains(player.getUUID()), force);
        if (pendingAction == PendingSpawnAction.REFUSE) {
            send(player, SPAWN_PENDING);
            return 0;
        }
        if (pendingAction == PendingSpawnAction.CANCEL_AND_CONTINUE) pendingSpawns.remove(player.getUUID());
        try {
            if (!admit(player)) return 0;
            if (!(player.level() instanceof ServerLevel level)) {
                send(player, "§cCannot determine your current world.");
                return 0;
            }
            SpawnDestination.Target target = SpawnDestination.route(SpawnDestination.dimension(level),
                    config.enableCrossDimensionTeleport, config.enableOverworldSpawn,
                    config.enableNetherSpawn, config.enableEndSpawn, config.enableModdedDimensionSpawn);
            if (target == SpawnDestination.Target.DISABLED) {
                send(player, SPAWN_DISABLED);
                return 0;
            }
            ServerLevel selectedLevel = target == SpawnDestination.Target.OVERWORLD
                    ? level.getServer().overworld() : level;
            SpawnDestination.Plan plan = SpawnDestination.plan(player, selectedLevel, force);
            if (plan.immediate() != null) return completeSpawn(player, plan.immediate(), null);

            SpawnDestination.Pending search = plan.pending();
            TeleportService.LifecycleFence<Entity> lifecycle = TeleportService.captureLifecycle(player);
            long[] lifecycleEpoch = {Long.MIN_VALUE};
            pendingSpawns.add(player.getUUID(), (candidateBudget, worldWorkBudget) -> {
                try {
                    if (shouldCheckLifecycle(lifecycleEpoch[0], pendingTickEpoch)) {
                        lifecycleEpoch[0] = pendingTickEpoch;
                        if (!TeleportService.isLifecycleCurrent(lifecycle)) {
                            return PendingStep.complete(
                                    new SpawnCompletion(player, lifecycle, null, true), 0, 0);
                        }
                    }
                    SpawnDestination.Tick used = search.tick(candidateBudget, worldWorkBudget);
                    if (!search.complete()) return PendingStep.pending(
                            used.candidatesStarted(), used.worldWork());
                    return PendingStep.complete(new SpawnCompletion(
                                    player, lifecycle, search.result(), false),
                            used.candidatesStarted(), used.worldWork());
                } catch (RuntimeException failure) {
                    LOGGER.error("Unexpected pending /spawn failure for {}", player.getGameProfile().name(), failure);
                    return failedPendingStep(new SpawnCompletion(player, lifecycle, null, false),
                            candidateBudget, worldWorkBudget);
                }
            });
            return 1;
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected /spawn failure for {}", player.getGameProfile().name(), failure);
            sendSafely(player, INTERNAL_ERROR.formatted(config.spawnCommand));
        }
        return 0;
    }

    static PendingSpawnAction pendingSpawnAction(boolean pending, boolean force) {
        if (!pending) return PendingSpawnAction.CONTINUE;
        return force ? PendingSpawnAction.CANCEL_AND_CONTINUE : PendingSpawnAction.REFUSE;
    }

    static boolean lifecycleCurrentAtCompletion(BooleanSupplier current) {
        return current.getAsBoolean();
    }

    static boolean shouldCheckLifecycle(long lastCheckedEpoch, long currentEpoch) {
        return lastCheckedEpoch != currentEpoch;
    }

    static <V> PendingStep<V> failedPendingStep(V value, int candidateBudget, int worldWorkBudget) {
        return PendingStep.complete(value, candidateBudget, worldWorkBudget);
    }

    static boolean shouldLoadDestinationChunks(boolean incrementalDestinationReady) {
        return !incrementalDestinationReady;
    }

    void tick() {
        try {
            pendingTickEpoch++;
            pendingSpawns.tick(SEARCH_CANDIDATES_PER_TICK, SEARCH_WORLD_WORK_PER_TICK, completion -> {
                try {
                    if (completion.cancelled) return;
                    if (completion.result == null) {
                        sendSafely(completion.player, INTERNAL_ERROR.formatted(config.spawnCommand));
                    } else completeSpawn(completion.player, completion.result, completion.lifecycle);
                } catch (RuntimeException failure) {
                    LOGGER.error("Unexpected pending /spawn completion failure", failure);
                    sendSafely(completion.player, INTERNAL_ERROR.formatted(config.spawnCommand));
                }
            });
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected OMWH END_SERVER_TICK failure", failure);
        }
    }

    void removePending(UUID playerId) { pendingSpawns.remove(playerId); }
    void clearPending() { pendingSpawns.clear(); }

    private int completeSpawn(ServerPlayer player, SpawnDestination.Result destination,
                              TeleportService.LifecycleFence<Entity> lifecycle) {
        return switch (destination.outcome()) {
            case NO_WORLD_SPAWN -> { send(player, "§cCannot determine world spawn."); yield 0; }
            case VEHICLE_TOO_LARGE -> { send(player, VEHICLE_TOO_LARGE); yield 0; }
            case UNSAFE -> {
                send(player, unsafeMessage(config.unsafeSpawnMessage,
                        config.spawnCommand, config.enableForceOverride));
                yield 0;
            }
            case ACCEPT -> {
                if (lifecycle != null && !lifecycleCurrentAtCompletion(
                        () -> TeleportService.isLifecycleCurrent(lifecycle))) yield 0;
                if (lifecycle != null && !admit(player)) yield 0;
                if (lifecycle != null && !SpawnDestination.matchesSearchAnchor(destination.searchAnchor(),
                        SpawnDestination.currentAnchor(destination.destination().level()))) {
                    sendSafely(player, SPAWN_ANCHOR_CHANGED);
                    yield 0;
                }
                yield teleport(player, destination.destination(), false, lifecycle,
                        destination.incrementalDestinationReady());
            }
        };
    }

    private boolean admit(ServerPlayer player) {
        Cooldowns.Blocking blocking = cooldowns.blocking(player.getUUID());
        if (blocking.type() == Cooldowns.Type.NONE) return true;
        send(player, cooldownMessage(config, blocking));
        return false;
    }

    private int teleport(ServerPlayer player, DestinationSafety.Prepared destination, boolean home,
                         TeleportService.LifecycleFence<Entity> lifecycle, boolean incrementalDestinationReady) {
        if (shouldLoadDestinationChunks(incrementalDestinationReady)) {
            DestinationSafety.loadDestinationChunks(destination.level(), destination.position());
        }
        playEffects(player);
        TeleportService.Result result = TeleportService.teleport(player, destination);
        if (!continuesTeleportCompletion(result)) {
            sendSafely(player, teleportFailureMessage(result, home ? config.homeCommand : config.spawnCommand));
            return 0;
        }

        try {
            cooldowns.recordRegular(player.getUUID());
            String passengerMessage = passengerMessage(player.getName().getString(), home);
            for (ServerPlayer passenger : result.passengerPlayers()) sendSafely(passenger, passengerMessage);
            if (result.partial()) {
                sendSafely(player, PARTIAL_TELEPORT);
                return 0;
            }
            sendSafely(player, home ? config.homeSuccessMessage : config.spawnSuccessMessage);
            return 1;
        } catch (RuntimeException failure) {
            LOGGER.error("OMWH completion failed after teleport mutation", failure);
            cooldowns.recordRegular(player.getUUID());
            sendSafely(player, PARTIAL_TELEPORT);
            return 0;
        }
    }

    private void playEffects(ServerPlayer player) {
        if (config.playTeleportSound) player.playSound(SoundEvents.ENDERMAN_TELEPORT, 0.5f, 1.0f);
        if (!config.spawnTeleportParticles || !(player.level() instanceof ServerLevel level)) return;
        for (int i = 0; i < 40; i++) {
            double angle = i * 2 * Math.PI / 40.0;
            level.sendParticles(ParticleTypes.PORTAL, player.getX() + Math.cos(angle),
                    player.getY() + 0.5, player.getZ() + Math.sin(angle), 1, 0, 0, 0, 0);
        }
    }

    private static void send(ServerPlayer player, String message) {
        player.sendSystemMessage(Component.literal(format(message)));
    }

    private static void sendSafely(ServerPlayer player, String message) {
        try {
            send(player, message);
        } catch (RuntimeException failure) {
            LOGGER.error("Could not send OMWH command feedback", failure);
        }
    }

    static String format(String message) {
        return message.replace('&', '§');
    }
}
