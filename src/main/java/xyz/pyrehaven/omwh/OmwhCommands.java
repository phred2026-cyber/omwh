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
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;


public final class OmwhCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    static final int SEARCH_CANDIDATES_PER_TICK = 4_096;
    static final int TELEPORT_PARTICLE_COUNT = 40;
    static final int MAX_EFFECT_DISPATCHES = 1 // optional sound dispatch
            + TELEPORT_PARTICLE_COUNT; // optional particle dispatches
    static final int MAX_PENDING_TICKET_RELEASE_WORK = DestinationSafety.SPAWN_PREPARATION_CHUNK_CAP // search-ticket removals
            + DestinationSafety.DESTINATION_CHUNK_CAP; // accepted-destination ticket removals
    static final int MAX_IMMEDIATE_ROUTE_WORK = Math.max(
            DestinationSafety.MAX_MOUNTED_HOME_SAFETY_WORK, // exact and optional above-bed home policy
            DestinationSafety.MAX_END_SAFETY_WORK) // root and player-only End placement checks
            + DestinationSafety.DESTINATION_CHUNK_CAP // fixed destination chunk preparation
            + MAX_EFFECT_DISPATCHES // one sound plus forty particle sends
            + TeleportService.COMPLETION_WORK; // teleport and passenger reconciliation
    static final int SEARCH_WORLD_WORK_PER_TICK = TeleportService.LIFECYCLE_CAPTURE_WORK // new-request fence capture
            + MAX_PENDING_TICKET_RELEASE_WORK // competing-request cancellation cleanup
            + Math.max(MAX_IMMEDIATE_ROUTE_WORK,
            HomeDestination.RESOLUTION_AND_SAFETY_WORK // saved-respawn resolution and home safety
                    + MAX_EFFECT_DISPATCHES // completion sound and particle sends
                    + TeleportService.COMPLETION_WORK // teleport and passenger reconciliation
                    + TeleportService.LIFECYCLE_VALIDATION_WORK); // final pending-request fence validation
    static final int PENDING_ROUTE_MINIMUM_PROGRESS_WORK = DestinationSafety.MAX_PROBE_CELL_WORK;
    static final int PENDING_ROUTE_WORK_SLICE = Math.max(
            SEARCH_CANDIDATES_PER_TICK * PENDING_ROUTE_MINIMUM_PROGRESS_WORK, // one maximum-cost probe cell per candidate start
            HomeDestination.RESOLUTION_AND_SAFETY_WORK // complete saved-respawn and home-safety work
                    + SpawnDestination.PREPARATION_CHUNKS_PER_VISIT); // one terrain-preparation quantum
    static final int PENDING_COMPLETION_WORK = MAX_EFFECT_DISPATCHES // sound and particle sends
            + TeleportService.COMPLETION_WORK; // teleport and passenger reconciliation
    static final int PENDING_ADVANCEMENT_WORK_PER_TICK = TeleportService.LIFECYCLE_VALIDATION_WORK // one full fence validation
            + PENDING_ROUTE_WORK_SLICE // one bounded search or home-resolution visit
            + PENDING_COMPLETION_WORK // effects, teleport, and reconciliation
            + MAX_PENDING_TICKET_RELEASE_WORK; // terminal ticket cleanup
    static final int MAX_PENDING_VISIT_WORK = PENDING_ADVANCEMENT_WORK_PER_TICK;
    private final OmwhConfig config;
    private final Cooldowns cooldowns;
    private final PendingSearches<UUID, Void> pendingTeleports = new PendingSearches<>();
    private final TickWorkAllowance serverWork;
    private long pendingTickEpoch;

    @FunctionalInterface
    interface PendingWork<V> extends AutoCloseable {
        PendingStep<V> step(int candidateBudget, int worldWorkBudget);
        default int closeWork() { return 0; }
        @Override default void close() { }
    }

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

        void close() {
            route.close();
        }

        int closeWork() {
            return route.closeWork();
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
        private final Deque<PendingWork<V>> cleanup = new ArrayDeque<>();

        boolean add(K key, PendingWork<V> work) {
            if (searches.putIfAbsent(key, work) != null) return false;
            roundRobin.addLast(key);
            return true;
        }
        boolean contains(K key) { return searches.containsKey(key); }
        private PendingWork<V> detach(K key) {
            PendingWork<V> removed = searches.remove(key);
            roundRobin.remove(key);
            return removed;
        }

        void cancel(K key, TickWorkAllowance allowance) {
            PendingWork<V> removed = detach(key);
            if (removed == null) return;
            int closeWork = removed.closeWork();
            if (allowance.claim(closeWork)) closeOrRetain(removed);
            else cleanup.addLast(removed);
        }

        void clearTerminal() {
            cleanup.addAll(searches.values());
            searches.clear();
            roundRobin.clear();
            int attemptsRemaining = cleanup.stream()
                    .mapToInt(work -> Math.max(2, work.closeWork() * 2 + 1))
                    .sum();
            while (!cleanup.isEmpty() && attemptsRemaining-- > 0) {
                closeOrRetain(cleanup.removeFirst());
            }
            if (!cleanup.isEmpty()) {
                throw new IllegalStateException("OMWH could not release all pending teleport terrain at shutdown");
            }
        }
        int size() { return searches.size(); }
        int cleanupSize() { return cleanup.size(); }

        PendingTick tick(int candidateBudget, int worldWorkBudget, Consumer<V> completion) {
            TickWorkAllowance allowance = new TickWorkAllowance(worldWorkBudget);
            return tick(candidateBudget, allowance, worldWorkBudget, completion);
        }

        PendingTick tick(int candidateBudget, TickWorkAllowance allowance, int worldWorkBudget,
                         Consumer<V> completion) {
            int tickWorkLimit = Math.min(worldWorkBudget, allowance.remaining());
            int candidatesUsed = 0;
            int worldWorkUsed = retryCleanup(allowance, tickWorkLimit);
            int itemsCompleted = 0;
            Set<K> visited = new HashSet<>();
            while (!roundRobin.isEmpty() && candidatesUsed < candidateBudget && worldWorkUsed < tickWorkLimit) {
                K key = roundRobin.removeFirst();
                if (!visited.add(key)) {
                    roundRobin.addFirst(key);
                    break;
                }
                PendingWork<V> work = searches.get(key);
                if (work == null) continue;
                int cleanupReserve = work.closeWork();
                int availableWorldWork = tickWorkLimit - worldWorkUsed - cleanupReserve;
                if (availableWorldWork <= 0) {
                    roundRobin.addFirst(key);
                    break;
                }
                int candidateSlice = Math.min(1, candidateBudget - candidatesUsed);
                int worldSlice = Math.min(MAX_PENDING_VISIT_WORK - cleanupReserve, availableWorldWork);
                PendingStep<V> step = work.step(candidateSlice, worldSlice);
                if (step.candidatesUsed < 0 || step.candidatesUsed > candidateSlice
                        || step.worldWorkUsed < 0 || step.worldWorkUsed > worldSlice) {
                    throw new IllegalStateException("pending search exceeded its shared allowance");
                }
                if (!allowance.claim(step.worldWorkUsed)) {
                    throw new IllegalStateException("pending search exceeded the shared server allowance");
                }
                candidatesUsed += step.candidatesUsed;
                worldWorkUsed += step.worldWorkUsed;
                if (step.complete) {
                    searches.remove(key);
                    itemsCompleted++;
                    try {
                        completion.accept(step.value);
                    } finally {
                        int closeWork = work.closeWork();
                        if (!allowance.claim(closeWork)) {
                            cleanup.addLast(work);
                        } else {
                            worldWorkUsed += closeWork;
                            closeOrRetain(work);
                        }
                    }
                } else if (step.candidatesUsed == 0 && step.worldWorkUsed == 0) {
                    roundRobin.addFirst(key);
                    break;
                } else {
                    roundRobin.addLast(key);
                }
            }
            return new PendingTick(candidatesUsed, worldWorkUsed, itemsCompleted);
        }

        private int retryCleanup(TickWorkAllowance allowance, int worldWorkBudget) {
            int worldWork = 0;
            int attempts = cleanup.size();
            while (attempts-- > 0 && !cleanup.isEmpty()) {
                PendingWork<V> work = cleanup.removeFirst();
                int attemptWork = work.closeWork();
                if (attemptWork > worldWorkBudget - worldWork) {
                    cleanup.addFirst(work);
                    break;
                }
                if (!allowance.claim(attemptWork)) {
                    cleanup.addFirst(work);
                    break;
                }
                worldWork += attemptWork;
                closeOrRetain(work);
            }
            return worldWork;
        }

        private void closeOrRetain(PendingWork<V> work) {
            try {
                work.close();
            } catch (RuntimeException failure) {
                cleanup.addLast(work);
                LOGGER.error("OMWH could not release pending teleport terrain; cleanup will retry", failure);
            }
        }
    }

    OmwhCommands(OmwhConfig config, Cooldowns cooldowns) {
        this(config, cooldowns, SEARCH_WORLD_WORK_PER_TICK);
    }

    OmwhCommands(OmwhConfig config, Cooldowns cooldowns, int serverWorkLimit) {
        this.config = config;
        this.cooldowns = cooldowns;
        this.serverWork = new TickWorkAllowance(serverWorkLimit);
    }

    <V> PendingWork<Void> createPendingCoordinator(
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
        }, TeleportService.LIFECYCLE_VALIDATION_WORK, PENDING_COMPLETION_WORK);
        return new PendingWork<>() {
            @Override
            public PendingStep<Void> step(int candidateBudget, int worldWorkBudget) {
                return coordinated.step(pendingTickEpoch, candidateBudget, worldWorkBudget);
            }

            @Override
            public int closeWork() {
                return coordinated.closeWork();
            }

            @Override
            public void close() {
                coordinated.close();
            }
        };
    }

    static boolean finalCooldownAdmission(Cooldowns cooldowns, UUID playerId, OmwhConfig config,
                                          Consumer<String> feedback) {
        Cooldowns.Blocking blocking = cooldowns.blocking(playerId);
        if (blocking.type() == Cooldowns.Type.NONE) return true;
        feedback.accept(cooldownMessage(config, blocking));
        return false;
    }

    boolean enqueuePending(UUID playerId, PendingWork<Void> pending) {
        return pendingTeleports.add(playerId, pending);
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
        cancelPending(player.getUUID());
        try {
            if (!admit(player)) return 0;
            if (!serverWork.claim(SpawnDestination.PENDING_START_WORK)) {
                send(player, commandMessage(config.busyMessage, config.homeCommand));
                return 0;
            }
            HomeDestination.Plan plan = HomeDestination.plan(
                    player, force, config.enableCrossDimensionTeleport);
            if (plan.immediate() != null) return completeHome(player, plan.immediate());
            if (!serverWork.claim(TeleportService.LIFECYCLE_CAPTURE_WORK)) {
                plan.pending().close();
                send(player, commandMessage(config.busyMessage, config.homeCommand));
                return 0;
            }
            TeleportService.LifecycleFence<Entity> lifecycle = TeleportService.captureLifecycle(player);
            PendingWork<Void> coordinated = createPendingCoordinator(
                    pendingHomeRoute(plan.pending()),
                    () -> TeleportService.lifecycleStatus(lifecycle),
                    result -> result.outcome() != HomeDestination.Outcome.ACCEPT || admit(player),
                    result -> {
                        boolean current = plan.pending().authorityCurrent();
                        if (!current) sendSafely(player,
                                commandMessage(config.noHomepointMessage, config.homeCommand));
                        return current;
                    },
                    result -> completeHome(player, result),
                    status -> {
                        if (status == TeleportService.LifecycleStatus.TOO_LARGE) {
                            sendSafely(player,
                                    commandMessage(config.passengerTreeTooLargeMessage, config.homeCommand));
                        }
                    });
            PendingWork<Void> guarded = guardPending(coordinated, failure -> {
                LOGGER.error("Unexpected pending /home failure for {}", player.getGameProfile().name(), failure);
                sendSafely(player, commandMessage(config.internalErrorMessage, config.homeCommand));
            });
            pendingTeleports.add(player.getUUID(), guarded);
            return 1;
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
        PendingSpawnAction pendingAction = pendingSpawnAction(pendingTeleports.contains(player.getUUID()), force);
        if (pendingAction == PendingSpawnAction.REFUSE) {
            send(player, commandMessage(config.spawnPendingMessage, config.spawnCommand));
            return 0;
        }
        if (pendingAction == PendingSpawnAction.CANCEL_AND_CONTINUE) {
            cancelPending(player.getUUID());
        }
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
                TeleportService.validatePassengerTreeForImmediateTeleport(player);
            } else if (!serverWork.claim(SpawnDestination.PENDING_START_WORK)) {
                send(player, commandMessage(config.busyMessage, config.spawnCommand));
                return 0;
            }
            SpawnDestination.Plan plan = SpawnDestination.plan(player, selectedLevel, force);
            if (plan.immediate() != null) return completeSpawn(player, plan.immediate());

            SpawnDestination.Pending search = plan.pending();
            if (!serverWork.claim(TeleportService.LIFECYCLE_CAPTURE_WORK)) {
                search.close();
                send(player, commandMessage(config.busyMessage, config.spawnCommand));
                return 0;
            }
            TeleportService.LifecycleFence<Entity> lifecycle = TeleportService.captureLifecycle(player);
            PendingWork<Void> coordinated = createPendingCoordinator(
                    pendingSpawnRoute(search),
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
            PendingWork<Void> guarded = guardPending(coordinated, failure -> {
                LOGGER.error("Unexpected pending /spawn failure for {}", player.getGameProfile().name(), failure);
                sendSafely(player, commandMessage(config.internalErrorMessage, config.spawnCommand));
            });
            pendingTeleports.add(player.getUUID(), guarded);
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


    static <V> PendingWork<V> guardPending(PendingWork<V> work,
                                            Consumer<RuntimeException> failureHandler) {
        return new PendingWork<>() {
            @Override
            public PendingStep<V> step(int candidateBudget, int worldWorkBudget) {
                try {
                    return work.step(candidateBudget, worldWorkBudget);
                } catch (RuntimeException failure) {
                    failureHandler.accept(failure);
                    return PendingStep.complete(null, candidateBudget, worldWorkBudget);
                }
            }

            @Override
            public int closeWork() {
                return work.closeWork();
            }

            @Override
            public void close() {
                work.close();
            }
        };
    }

    static PendingWork<HomeDestination.Result> pendingHomeRoute(HomeDestination.Pending home) {
        return new PendingWork<>() {
            @Override
            public PendingStep<HomeDestination.Result> step(int candidateBudget, int worldWorkBudget) {
                return home.step(candidateBudget, worldWorkBudget);
            }

            @Override public int closeWork() { return home.closeWork(); }
            @Override public void close() { home.close(); }
        };
    }

    static PendingWork<SpawnDestination.Result> pendingSpawnRoute(SpawnDestination.Pending search) {
        return new PendingWork<>() {
            @Override
            public PendingStep<SpawnDestination.Result> step(int candidateBudget, int worldWorkBudget) {
                SpawnDestination.Tick used = search.tick(candidateBudget, worldWorkBudget);
                if (!search.complete()) {
                    return PendingStep.pending(used.candidatesStarted(), used.worldWork());
                }
                return PendingStep.complete(search.result(), used.candidatesStarted(), used.worldWork());
            }

            @Override
            public int closeWork() {
                return search.closeWork();
            }

            @Override
            public void close() {
                search.close();
            }
        };
    }


    void tick() {
        try {
            pendingTickEpoch++;
            pendingTeleports.tick(SEARCH_CANDIDATES_PER_TICK, serverWork,
                    Math.min(serverWork.remaining(), PENDING_ADVANCEMENT_WORK_PER_TICK), ignored -> { });
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected OMWH END_SERVER_TICK failure", failure);
        } finally {
            serverWork.reset();
        }
    }

    void cancelPending(UUID playerId) {
        pendingTeleports.cancel(playerId, serverWork);
    }

    void clearPending() { pendingTeleports.clearTerminal(); }
    int remainingServerWork() { return serverWork.remaining(); }

    private int completeHome(ServerPlayer player, HomeDestination.Result destination) {
        return switch (destination.outcome()) {
            case NO_HOME -> {
                send(player, commandMessage(config.noHomepointMessage, config.homeCommand));
                yield 0;
            }
            case CROSS_DIMENSION -> {
                send(player, commandMessage(config.crossDimensionMessage, config.homeCommand));
                yield 0;
            }
            case CURRENT_WORLD_UNAVAILABLE -> {
                send(player, homeDenialMessage(config, destination.outcome()));
                yield 0;
            }
            case VEHICLE_TOO_LARGE -> {
                send(player, commandMessageWithForceGuidance(config.vehicleTooLargeMessage, config.homeCommand));
                yield 0;
            }
            case UNSAFE -> {
                send(player, commandMessageWithForceGuidance(config.unsafeHomeMessage, config.homeCommand));
                yield 0;
            }
            case ACCEPT -> teleport(player, destination.destination(), true, true);
        };
    }

    static String homeDenialMessage(OmwhConfig config, HomeDestination.Outcome outcome) {
        if (outcome != HomeDestination.Outcome.CURRENT_WORLD_UNAVAILABLE) {
            throw new IllegalArgumentException("outcome has no unavailable-world message");
        }
        return renderMessage(config, config.currentWorldUnavailableMessage,
                new MessageValues(config.homeCommand, null, null, null, false));
    }

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
                        destination.destinationPrepared());
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
                         boolean destinationPrepared) {
        if (!destinationPrepared) {
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
        for (int i = 0; i < TELEPORT_PARTICLE_COUNT; i++) {
            double angle = i * 2 * Math.PI / TELEPORT_PARTICLE_COUNT;
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
        StringBuilder formatted = new StringBuilder(message.length());
        for (int index = 0; index < message.length(); index++) {
            char current = message.charAt(index);
            if (current != '&') {
                formatted.append(current);
            } else if (index + 1 < message.length() && message.charAt(index + 1) == '&') {
                formatted.append('&');
                index++;
            } else {
                formatted.append('§');
            }
        }
        return formatted.toString();
    }
}
