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
import java.util.function.Predicate;
import java.util.function.Supplier;


public final class Commands {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    static final int SEARCH_CANDIDATES_PER_TICK = 4_096;
    static final int MAX_EFFECT_DISPATCHES = 1 + 40;
    static final int MAX_IMMEDIATE_ROUTE_WORK = Math.max(
            DestinationSafety.MAX_MOUNTED_HOME_SAFETY_WORK,
            DestinationSafety.MAX_END_SAFETY_WORK)
            + DestinationSafety.DESTINATION_CHUNK_CAP
            + MAX_EFFECT_DISPATCHES
            + TeleportService.COMPLETION_WORK;
    static final int SEARCH_WORLD_WORK_PER_TICK = TeleportService.LIFECYCLE_CAPTURE_WORK
            + MAX_IMMEDIATE_ROUTE_WORK;
    static final int PENDING_ROUTE_MINIMUM_PROGRESS_WORK = DestinationSafety.SpawnProbe.MAX_CELL_WORK;
    static final int PENDING_ROUTE_WORK_SLICE = SEARCH_CANDIDATES_PER_TICK
            * PENDING_ROUTE_MINIMUM_PROGRESS_WORK;
    static final int PENDING_ADVANCEMENT_WORK_PER_TICK = TeleportService.LIFECYCLE_VALIDATION_WORK
            + PENDING_ROUTE_WORK_SLICE
            + TeleportService.COMPLETION_WORK;
    static final int MAX_PENDING_VISIT_WORK = PENDING_ADVANCEMENT_WORK_PER_TICK;
    private final OmwhConfig config;
    private final Cooldowns cooldowns;
    private final PendingSearches<UUID, Void> pendingSpawns = new PendingSearches<>();
    private final TickWorkAllowance serverWork = new TickWorkAllowance(SEARCH_WORLD_WORK_PER_TICK);
    private long pendingTickEpoch;

    @FunctionalInterface
    interface PendingWork<V> { PendingStep<V> step(int candidateBudget, int worldWorkBudget); }

    interface CoordinatorHooks<V> {
        TeleportService.LifecycleStatus lifecycleStatus();
        void lifecycleRejected(TeleportService.LifecycleStatus status);
        boolean finalAdmission(V value);
        boolean anchorCurrent(V value);
        void complete(V value);
    }

    static final class CoordinatedPending<V> {
        private final PendingWork<V> route;
        private final CoordinatorHooks<V> hooks;
        private final int lifecycleWork;
        private final int completionWork;
        private long lifecycleEpoch = Long.MIN_VALUE;
        private boolean routeComplete;
        private V value;

        CoordinatedPending(PendingWork<V> route, CoordinatorHooks<V> hooks,
                           int lifecycleWork, int completionWork) {
            this.route = route;
            this.hooks = hooks;
            this.lifecycleWork = lifecycleWork;
            this.completionWork = completionWork;
        }

        PendingStep<Void> step(long epoch, int candidateBudget, int worldWorkBudget) {
            int candidatesUsed = 0;
            int worldWorkUsed = 0;
            int minimumProgressWork = lifecycleWork + completionWork
                    + (routeComplete ? 0 : PENDING_ROUTE_MINIMUM_PROGRESS_WORK);
            if (lifecycleEpoch != epoch && worldWorkBudget < minimumProgressWork) {
                return PendingStep.pending(0, 0);
            }
            if (lifecycleEpoch != epoch) {
                lifecycleEpoch = epoch;
                worldWorkUsed += lifecycleWork;
                TeleportService.LifecycleStatus lifecycle = hooks.lifecycleStatus();
                if (lifecycle != TeleportService.LifecycleStatus.CURRENT) {
                    hooks.lifecycleRejected(lifecycle);
                    return PendingStep.complete(null, 0, worldWorkUsed);
                }
            }
            if (!routeComplete) {
                int reservedRouteWork = worldWorkBudget - worldWorkUsed - completionWork;
                if (reservedRouteWork <= 0) return PendingStep.pending(0, worldWorkUsed);
                int routeWorkBudget = Math.min(PENDING_ROUTE_WORK_SLICE, reservedRouteWork);
                PendingStep<V> advanced = route.step(candidateBudget, routeWorkBudget);
                candidatesUsed = advanced.candidatesUsed();
                worldWorkUsed += advanced.worldWorkUsed();
                if (!advanced.complete()) return PendingStep.pending(candidatesUsed, worldWorkUsed);
                routeComplete = true;
                value = advanced.value();
            }
            if (worldWorkBudget - worldWorkUsed < completionWork) {
                return PendingStep.pending(candidatesUsed, worldWorkUsed);
            }
            worldWorkUsed += completionWork;
            if (hooks.finalAdmission(value) && hooks.anchorCurrent(value)) hooks.complete(value);
            return PendingStep.complete(null, candidatesUsed, worldWorkUsed);
        }
    }

    static final class TickWorkAllowance {
        private final int limit;
        private int remaining;
        TickWorkAllowance(int limit) { this.limit = limit; this.remaining = limit; }
        boolean claim(int work) {
            if (work < 0 || work > remaining) return false;
            remaining -= work;
            return true;
        }
        int remaining() { return remaining; }
        void reset() { remaining = limit; }
    }

    record PendingStep<V>(boolean complete, V value, int candidatesUsed, int worldWorkUsed) {
        static <V> PendingStep<V> pending(int candidatesUsed, int worldWorkUsed) {
            return new PendingStep<>(false, null, candidatesUsed, worldWorkUsed);
        }
        static <V> PendingStep<V> complete(V value, int candidatesUsed, int worldWorkUsed) {
            return new PendingStep<>(true, value, candidatesUsed, worldWorkUsed);
        }
    }
    record PendingTick(int candidatesUsed, int worldWorkUsed, int itemsCompleted) { }
    record MessageValues(String command, String player, String destination, Integer time,
                         boolean forceEnabled) { }
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
                int worldSlice = Math.min(MAX_PENDING_VISIT_WORK,
                        worldWorkBudget - worldWorkUsed);
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
                } else if (step.candidatesUsed == 0 && step.worldWorkUsed == 0) {
                    roundRobin.addFirst(key);
                    break;
                } else {
                    roundRobin.addLast(key);
                }
            }
            return new PendingTick(candidatesUsed, worldWorkUsed, itemsCompleted);
        }
    }

    Commands(OmwhConfig config, Cooldowns cooldowns) {
        this.config = config;
        this.cooldowns = cooldowns;
    }

    <V> PendingWork<Void> createSpawnCoordinator(
            PendingWork<V> route,
            Supplier<TeleportService.LifecycleStatus> lifecycle,
            Predicate<V> finalAdmission,
            Predicate<V> anchorCurrent,
            Consumer<V> completion,
            Consumer<TeleportService.LifecycleStatus> lifecycleRejection) {
        CoordinatedPending<V> coordinated = new CoordinatedPending<>(route, new CoordinatorHooks<>() {
            @Override public TeleportService.LifecycleStatus lifecycleStatus() { return lifecycle.get(); }
            @Override public void lifecycleRejected(TeleportService.LifecycleStatus status) {
                lifecycleRejection.accept(status);
            }
            @Override public boolean finalAdmission(V value) { return finalAdmission.test(value); }
            @Override public boolean anchorCurrent(V value) { return anchorCurrent.test(value); }
            @Override public void complete(V value) { completion.accept(value); }
        }, TeleportService.LIFECYCLE_VALIDATION_WORK, TeleportService.COMPLETION_WORK);
        return (candidateBudget, worldWorkBudget) ->
                coordinated.step(pendingTickEpoch, candidateBudget, worldWorkBudget);
    }

    static boolean finalCooldownAdmission(Cooldowns cooldowns, UUID playerId, OmwhConfig config,
                                          Consumer<String> feedback) {
        Cooldowns.Blocking blocking = cooldowns.blocking(playerId);
        if (blocking.type() == Cooldowns.Type.NONE) return true;
        feedback.accept(cooldownMessage(config, blocking));
        return false;
    }

    boolean enqueuePending(UUID playerId, PendingWork<Void> pending) {
        return pendingSpawns.add(playerId, pending);
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
        String template = switch (blocking.type()) {
            case PVP -> config.pvpCooldownMessage;
            case DAMAGE -> config.damageCooldownMessage;
            case JOIN -> config.joinCooldownMessage;
            case REGULAR -> config.regularCooldownMessage;
            case NONE -> throw new IllegalArgumentException("NONE has no cooldown message");
        };
        return renderMessage(config, template,
                new MessageValues(null, null, null, blocking.remainingSeconds(), false));
    }

    static String renderMessage(OmwhConfig config, String template, MessageValues values) {
        String message = template;
        if (values.command() != null) message = message.replace("{command}", values.command());
        if (values.player() != null) message = message.replace("{player}", values.player());
        if (values.destination() != null) message = message.replace("{destination}", values.destination());
        if (values.time() != null) message = message.replace("{time}", values.time().toString());
        String forceGuidance = values.forceEnabled()
                ? renderMessage(config, config.forceGuidanceMessage,
                new MessageValues(values.command(), values.player(), values.destination(), values.time(), false))
                : "";
        return message.replace("{forceGuidance}", forceGuidance);
    }

    static String teleportFailureMessage(OmwhConfig config, TeleportService.Result result,
                                         String commandName) {
        String template = result.partial() ? config.partialTeleportMessage : config.internalErrorMessage;
        return renderMessage(config, template,
                new MessageValues(commandName, null, null, null, false));
    }

    static String renderMessageWithForceGuidance(OmwhConfig config, String template, String command) {
        boolean placesGuidance = template.contains("{forceGuidance}");
        String rendered = renderMessage(config, template,
                new MessageValues(command, null, null, null, config.enableForceOverride));
        if (!config.enableForceOverride || placesGuidance) return rendered;
        return rendered + renderMessage(config, config.forceGuidanceMessage,
                new MessageValues(command, null, null, null, false));
    }

    static boolean continuesTeleportCompletion(TeleportService.Result result) {
        return result.success() || result.partial();
    }

    private int executeHome(ServerPlayer player, boolean force) {
        if (player == null) return 0;
        pendingSpawns.remove(player.getUUID());
        try {
            if (!admit(player)) return 0;
            if (!serverWork.claim(TeleportService.LIFECYCLE_CAPTURE_WORK + MAX_IMMEDIATE_ROUTE_WORK)) {
                send(player, commandMessage(config.busyMessage, config.homeCommand));
                return 0;
            }
            TeleportService.captureLifecycle(player);
            HomeDestination.Result destination = HomeDestination.find(
                    player, force, config.enableCrossDimensionTeleport);
            switch (destination.outcome()) {
                case NO_HOME -> send(player, commandMessage(config.noHomepointMessage, config.homeCommand));
                case CROSS_DIMENSION -> send(player, commandMessage(config.crossDimensionMessage, config.homeCommand));
                case VEHICLE_TOO_LARGE -> send(player,
                        commandMessageWithForceGuidance(config.vehicleTooLargeMessage, config.homeCommand));
                case UNSAFE -> send(player,
                        commandMessageWithForceGuidance(config.unsafeHomeMessage, config.homeCommand));
                case ACCEPT -> { return teleport(player, destination.destination(), true, false); }
            }
        } catch (TeleportService.PassengerTreeTooLarge tooLarge) {
            sendSafely(player, commandMessage(config.passengerTreeTooLargeMessage, config.homeCommand));
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected /home failure for {}", player.getGameProfile().name(), failure);
            sendSafely(player, commandMessage(config.internalErrorMessage, config.homeCommand));
        }
        return 0;
    }

    private int executeSpawn(ServerPlayer player, boolean force) {
        if (player == null) return 0;
        PendingSpawnAction pendingAction = pendingSpawnAction(pendingSpawns.contains(player.getUUID()), force);
        if (pendingAction == PendingSpawnAction.REFUSE) {
            send(player, commandMessage(config.spawnPendingMessage, config.spawnCommand));
            return 0;
        }
        if (pendingAction == PendingSpawnAction.CANCEL_AND_CONTINUE) pendingSpawns.remove(player.getUUID());
        try {
            if (!admit(player)) return 0;
            if (!(player.level() instanceof ServerLevel level)) {
                send(player, commandMessage(config.currentWorldUnavailableMessage, config.spawnCommand));
                return 0;
            }
            SpawnDestination.Target target = SpawnDestination.route(SpawnDestination.dimension(level),
                    config.enableCrossDimensionTeleport, config.enableOverworldSpawn,
                    config.enableNetherSpawn, config.enableEndSpawn, config.enableModdedDimensionSpawn);
            if (target == SpawnDestination.Target.DISABLED) {
                send(player, commandMessage(config.spawnDisabledMessage, config.spawnCommand));
                return 0;
            }
            ServerLevel selectedLevel = target == SpawnDestination.Target.OVERWORLD
                    ? level.getServer().overworld() : level;
            boolean immediate = force || selectedLevel.dimension().equals(net.minecraft.world.level.Level.END);
            if (immediate) {
                if (!serverWork.claim(TeleportService.LIFECYCLE_CAPTURE_WORK + MAX_IMMEDIATE_ROUTE_WORK)) {
                    send(player, commandMessage(config.busyMessage, config.spawnCommand));
                    return 0;
                }
                TeleportService.captureLifecycle(player);
            } else if (!serverWork.claim(SpawnDestination.ADMISSION_SNAPSHOT_PROBE_WORK)) {
                send(player, commandMessage(config.busyMessage, config.spawnCommand));
                return 0;
            }
            SpawnDestination.Plan plan = SpawnDestination.plan(player, selectedLevel, force);
            if (plan.immediate() != null) return completeSpawn(player, plan.immediate());

            SpawnDestination.Pending search = plan.pending();
            if (!serverWork.claim(TeleportService.LIFECYCLE_CAPTURE_WORK)) {
                send(player, commandMessage(config.busyMessage, config.spawnCommand));
                return 0;
            }
            TeleportService.LifecycleFence<Entity> lifecycle = TeleportService.captureLifecycle(player);
            PendingWork<Void> coordinated = createSpawnCoordinator(
                    (candidateBudget, worldWorkBudget) -> {
                        SpawnDestination.Tick used = search.tick(candidateBudget, worldWorkBudget);
                        if (!search.complete()) return PendingStep.pending(
                                used.candidatesStarted(), used.worldWork());
                        return PendingStep.complete(search.result(), used.candidatesStarted(), used.worldWork());
                    },
                    () -> TeleportService.lifecycleStatus(lifecycle),
                    result -> result.outcome() != SpawnDestination.Outcome.ACCEPT || admit(player),
                    result -> {
                        if (result.outcome() != SpawnDestination.Outcome.ACCEPT) return true;
                        boolean current = SpawnDestination.matchesSearchAnchor(result.searchAnchor(),
                                SpawnDestination.currentAnchor(result.destination().level()));
                        if (!current) sendSafely(player,
                                commandMessage(config.spawnAnchorChangedMessage, config.spawnCommand));
                        return current;
                    },
                    result -> completeSpawn(player, result),
                    status -> {
                        if (status == TeleportService.LifecycleStatus.TOO_LARGE) {
                            sendSafely(player,
                                    commandMessage(config.passengerTreeTooLargeMessage, config.spawnCommand));
                        }
                    });
            pendingSpawns.add(player.getUUID(), (candidateBudget, worldWorkBudget) -> {
                try {
                    return coordinated.step(candidateBudget, worldWorkBudget);
                } catch (RuntimeException failure) {
                    LOGGER.error("Unexpected pending /spawn failure for {}", player.getGameProfile().name(), failure);
                    sendSafely(player, commandMessage(config.internalErrorMessage, config.spawnCommand));
                    return failedPendingStep(null, candidateBudget, worldWorkBudget);
                }
            });
            return 1;
        } catch (TeleportService.PassengerTreeTooLarge tooLarge) {
            sendSafely(player, commandMessage(config.passengerTreeTooLargeMessage, config.spawnCommand));
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected /spawn failure for {}", player.getGameProfile().name(), failure);
            sendSafely(player, commandMessage(config.internalErrorMessage, config.spawnCommand));
        }
        return 0;
    }

    static PendingSpawnAction pendingSpawnAction(boolean pending, boolean force) {
        if (!pending) return PendingSpawnAction.CONTINUE;
        return force ? PendingSpawnAction.CANCEL_AND_CONTINUE : PendingSpawnAction.REFUSE;
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
            PendingTick used = pendingSpawns.tick(SEARCH_CANDIDATES_PER_TICK,
                    Math.min(serverWork.remaining(), PENDING_ADVANCEMENT_WORK_PER_TICK), ignored -> { });
            if (!serverWork.claim(used.worldWorkUsed())) throw new IllegalStateException("coordinator exceeded tick allowance");
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected OMWH END_SERVER_TICK failure", failure);
        } finally {
            serverWork.reset();
        }
    }

    void removePending(UUID playerId) { pendingSpawns.remove(playerId); }
    void clearPending() { pendingSpawns.clear(); }

    private int completeSpawn(ServerPlayer player, SpawnDestination.Result destination) {
        return switch (destination.outcome()) {
            case NO_WORLD_SPAWN -> {
                send(player, commandMessage(config.worldSpawnUnavailableMessage, config.spawnCommand));
                yield 0;
            }
            case VEHICLE_TOO_LARGE -> {
                send(player, commandMessageWithForceGuidance(config.vehicleTooLargeMessage, config.spawnCommand));
                yield 0;
            }
            case UNSAFE -> {
                send(player, commandMessageWithForceGuidance(config.unsafeSpawnMessage, config.spawnCommand));
                yield 0;
            }
            case ACCEPT -> {
                yield teleport(player, destination.destination(), false,
                        destination.incrementalDestinationReady());
            }
        };
    }

    private boolean admit(ServerPlayer player) {
        return finalCooldownAdmission(cooldowns, player.getUUID(), config,
                message -> send(player, message));
    }

    private String commandMessage(String template, String command) {
        return renderMessage(config, template,
                new MessageValues(command, null, null, null, false));
    }

    private String commandMessageWithForceGuidance(String template, String command) {
        return renderMessageWithForceGuidance(config, template, command);
    }

    private int teleport(ServerPlayer player, DestinationSafety.Prepared destination, boolean home,
                         boolean incrementalDestinationReady) {
        if (shouldLoadDestinationChunks(incrementalDestinationReady)) {
            DestinationSafety.loadDestinationChunks(destination.level(), destination.position());
        }
        playEffects(player);
        TeleportService.Result result = TeleportService.teleport(player, destination);
        if (!continuesTeleportCompletion(result)) {
            sendSafely(player, teleportFailureMessage(
                    config, result, home ? config.homeCommand : config.spawnCommand));
            return 0;
        }

        try {
            cooldowns.recordRegular(player.getUUID());
            String command = home ? config.homeCommand : config.spawnCommand;
            String destinationName = home
                    ? config.homePassengerDestination
                    : config.spawnPassengerDestination;
            String passengerMessage = renderMessage(config, config.passengerNotificationMessage,
                    new MessageValues(command, player.getName().getString(), destinationName, null, false));
            for (ServerPlayer passenger : result.passengerPlayers()) sendSafely(passenger, passengerMessage);
            if (result.partial()) {
                sendSafely(player, commandMessage(config.partialTeleportMessage, command));
                return 0;
            }
            String successTemplate = home ? config.homeSuccessMessage : config.spawnSuccessMessage;
            sendSafely(player, commandMessage(successTemplate, command));
            return 1;
        } catch (RuntimeException failure) {
            LOGGER.error("OMWH completion failed after teleport mutation", failure);
            cooldowns.recordRegular(player.getUUID());
            sendSafely(player, commandMessage(config.partialTeleportMessage,
                    home ? config.homeCommand : config.spawnCommand));
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
