package xyz.pyrehaven.omwh;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class CommandsAndCooldownsTest {
    public static void main(String[] args) {
        cooldownPolicyUsesUuidExpiryAndPriority();
        disconnectCleanupAndSelfDamageClassification();
        forceSyntaxFollowsTheServerSetting();
        commandFeedbackUsesSpecificMessagesColorsAndPassengerDestination();
        teleportOutcomesUseInternalFailureAndPartialPolicies();
        disabledSpawnFeedbackDoesNotClaimAWorldIsMissing();
        pendingSpawnLifecycleRejectsDuplicatesAndCompletesExactlyOnce();
        pendingSpawnSchedulingSharesOneFairServerWideBudget();
        productionPendingSchedulerSlicesRevalidationAndRetiresStaleWork();
        commandsTickPassesASeparatePendingAdvancementSlice();
        productionCoordinatorOwnsLifecycleFinalGatesAndDispatch();
        productionFactoryRunsFinalGatesAndOversizeFeedbackThroughCommandsTick();
        admissionAndLifecycleWorkShareHardAggregateAllowances();
        zeroProgressKeepsTheBlockedRequestNext();
        pendingCommandAdmissionCancelsOnlyCompetingTeleports();
        System.out.println("CommandsAndCooldownsTest PASS (13 behavior groups)");
    }

    private static void forceSyntaxFollowsTheServerSetting() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        OmwhConfig enabled = new OmwhConfig();
        CommandDispatcher<CommandSourceStack> enabledDispatcher = new CommandDispatcher<>();
        new Commands(enabled, new Cooldowns(enabled, () -> 0L)).register(enabledDispatcher);
        check(enabledDispatcher.getRoot().getChild("home").getChild("force") != null,
                "/home force registered by default");
        check(enabledDispatcher.getRoot().getChild("spawn").getChild("force") != null,
                "/spawn force registered by default");
        check(enabledDispatcher.getRoot().getChild("home").getChild("--force") == null,
                "legacy dashed home force syntax absent");
        check(enabledDispatcher.getRoot().getChild("spawn").getChild("--force") == null,
                "legacy dashed spawn force syntax absent");
        var ordinary = net.minecraft.commands.Commands.createCompilationContext(LevelBasedPermissionSet.ALL);
        var gamemaster = net.minecraft.commands.Commands.createCompilationContext(LevelBasedPermissionSet.GAMEMASTER);
        check(enabledDispatcher.getRoot().getChild("home").getChild("force").canUse(ordinary),
                "/home force accepts ordinary players");
        check(enabledDispatcher.getRoot().getChild("spawn").getChild("force").canUse(ordinary),
                "/spawn force accepts ordinary players");
        check(enabledDispatcher.getRoot().getChild("home").getChild("force").canUse(gamemaster),
                "/home force accepts gamemasters");
        check(enabledDispatcher.getRoot().getChild("spawn").getChild("force").canUse(gamemaster),
                "/spawn force accepts gamemasters");

        OmwhConfig disabled = new OmwhConfig();
        disabled.enableForceOverride = false;
        CommandDispatcher<CommandSourceStack> disabledDispatcher = new CommandDispatcher<>();
        new Commands(disabled, new Cooldowns(disabled, () -> 0L)).register(disabledDispatcher);
        check(disabledDispatcher.getRoot().getChild("home").getChild("force") == null,
                "disabled /home force syntax absent");
        check(disabledDispatcher.getRoot().getChild("spawn").getChild("force") == null,
                "disabled /spawn force syntax absent");
    }

    private static void cooldownPolicyUsesUuidExpiryAndPriority() {
        OmwhConfig config = new OmwhConfig();
        AtomicLong now = new AtomicLong(1_000L);
        Cooldowns cooldowns = new Cooldowns(config, now::get);
        UUID player = UUID.randomUUID();

        cooldowns.recordJoin(player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.JOIN, "join restriction");
        cooldowns.recordIncomingDamageAllowedByOmwh(player, null);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.JOIN, "shorter event cannot reduce join");
        UUID attacker = UUID.randomUUID();
        cooldowns.recordIncomingDamageAllowedByOmwh(player, attacker);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.PVP, "longest event wins");
        check(cooldowns.blocking(attacker).type() == Cooldowns.Type.PVP, "allowed PvP records attacker");
        cooldowns.recordRegular(player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.PVP, "event before regular");

        now.set(46_000L);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.NONE, "expired entries removed");

        config.enablePvpCooldown = false;
        config.damageCooldownSeconds = 0;
        config.joinCooldownSeconds = 0;
        config.enableRegularCooldown = false;
        cooldowns.recordIncomingDamageAllowedByOmwh(player, UUID.randomUUID());
        cooldowns.recordIncomingDamageAllowedByOmwh(player, null);
        cooldowns.recordJoin(player);
        cooldowns.recordRegular(player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.NONE, "disabled and zero cooldowns");

        config.enableDamageCooldown = true;
        config.damageCooldownSeconds = 10;
        cooldowns.recordIncomingDamageAllowedByOmwh(player, null);
        now.addAndGet(1L);
        check(cooldowns.blocking(player).remainingSeconds() == 10, "remaining time rounds up");
        check(cooldowns.blocking(UUID.fromString(player.toString())).type() == Cooldowns.Type.DAMAGE,
                "UUID state survives player replacement");
    }

    private static void disconnectCleanupAndSelfDamageClassification() {
        OmwhConfig config = new OmwhConfig();
        Cooldowns cooldowns = new Cooldowns(config, () -> 1_000L);
        UUID player = UUID.randomUUID();
        cooldowns.recordIncomingDamageAllowedByOmwh(player, player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.DAMAGE,
                "self-inflicted damage uses the ordinary damage cooldown");
        cooldowns.remove(player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.NONE,
                "disconnect removes the player's cooldown state");
    }

    private static void commandFeedbackUsesSpecificMessagesColorsAndPassengerDestination() {
        OmwhConfig config = new OmwhConfig();
        check(Commands.renderMessage(config, config.pvpCooldownMessage,
                        new Commands.MessageValues(null, null, null, 12, false))
                .contains("12 seconds"), "cooldown placeholder");
        config.regularCooldownMessage = "&cWait {time}";
        String rawCooldown = Commands.renderMessage(config, config.regularCooldownMessage,
                new Commands.MessageValues(null, null, null, 3, false));
        check(rawCooldown.equals("&cWait 3"), "cooldown formatting is deferred to the send boundary");
        check(Commands.format(rawCooldown).equals("§cWait 3"), "send boundary applies ampersand colors once");
        config.passengerNotificationMessage = "&e{player} took you to {destination}.";
        check(Commands.renderMessage(config, config.passengerNotificationMessage,
                        new Commands.MessageValues(null, "Alex", config.homePassengerDestination, null, false))
                        .equals("&eAlex took you to their home."),
                "home passenger notification is configurable");
        check(Commands.renderMessage(config, config.passengerNotificationMessage,
                        new Commands.MessageValues(null, "Alex", config.spawnPassengerDestination, null, false))
                        .equals("&eAlex took you to spawn."),
                "spawn passenger notification is configurable");
        config.homeCommand = "return";
        config.spawnCommand = "hub";
        config.unsafeHomeMessage = "§cBlocked home.{forceGuidance}";
        config.unsafeSpawnMessage = "§cBlocked spawn.{forceGuidance}";
        config.forceGuidanceMessage = "\n§eUse /{command} force to teleport anyway.";
        check(Commands.renderMessage(config, config.unsafeHomeMessage,
                        new Commands.MessageValues(config.homeCommand, null, null, null, true))
                        .equals("§cBlocked home.\n§eUse /return force to teleport anyway."),
                "unsafe home advertises the configured force command");
        check(Commands.renderMessage(config, config.unsafeSpawnMessage,
                        new Commands.MessageValues(config.spawnCommand, null, null, null, true))
                        .equals("§cBlocked spawn.\n§eUse /hub force to teleport anyway."),
                "unsafe spawn advertises the configured force command");
        check(Commands.renderMessage(config, config.unsafeHomeMessage,
                        new Commands.MessageValues(config.homeCommand, null, null, null, false))
                        .equals("§cBlocked home."),
                "unsafe home does not advertise a disabled force command");
        check(Commands.renderMessage(config, config.unsafeSpawnMessage,
                        new Commands.MessageValues(config.spawnCommand, null, null, null, false))
                        .equals("§cBlocked spawn."),
                "unsafe spawn does not advertise a disabled force command");
        check(Commands.renderMessageWithForceGuidance(config, "§cLegacy unsafe text.", config.homeCommand)
                        .equals("§cLegacy unsafe text.\n§eUse /return force to teleport anyway."),
                "existing unsafe messages without the placeholder retain force guidance");
        check(Commands.renderMessage(config, config.vehicleTooLargeMessage,
                        new Commands.MessageValues(config.homeCommand, null, null, null, true))
                        .contains("/return force"),
                "home vehicle-too-large feedback offers its configured force command");
        check(Commands.renderMessage(config, config.vehicleTooLargeMessage,
                        new Commands.MessageValues(config.spawnCommand, null, null, null, true))
                        .contains("/hub force"),
                "spawn vehicle-too-large feedback offers its configured force command");
        check(!Commands.renderMessage(config, config.vehicleTooLargeMessage,
                        new Commands.MessageValues(config.homeCommand, null, null, null, false))
                        .contains("force"),
                "vehicle-too-large feedback does not advertise disabled force");
        config.enableForceOverride = false;
        check(Commands.renderMessageWithForceGuidance(config, "§cLegacy unsafe text.", config.homeCommand)
                        .equals("§cLegacy unsafe text."),
                "existing unsafe messages do not advertise disabled force");
        config.enableForceOverride = true;
        check(config.passengerTreeTooLargeMessage.toLowerCase().contains("passenger")
                        && config.passengerTreeTooLargeMessage.toLowerCase().contains("large"),
                "passenger cap denial has deliberate configurable feedback");
        config.homeSuccessMessage = "&a/{command} complete.";
        check(Commands.renderMessage(config, config.homeSuccessMessage,
                        new Commands.MessageValues(config.homeCommand, null, null, null, false))
                        .equals("&a/return complete."),
                "success feedback is rendered from configuration");
    }

    private static void teleportOutcomesUseInternalFailureAndPartialPolicies() {
        OmwhConfig config = new OmwhConfig();
        var partial = new TeleportService.Result(TeleportService.Outcome.PARTIAL, java.util.List.of());
        var failed = new TeleportService.Result(TeleportService.Outcome.FAILED, java.util.List.of());
        check(Commands.teleportFailureMessage(config, partial, "home")
                        .equals("§eMinecraft started moving your group, but OMWH could not verify every passenger. Check your group before moving again."),
                "post-movement verification failure gets clear player guidance");
        check(Commands.continuesTeleportCompletion(partial),
                "partial teleport continues through cooldown and passenger notifications");
        check(Commands.teleportFailureMessage(config, failed, "home")
                        .equals("§cInternal error executing /home. Check server log."),
                "home teleport invariant failure gets command-specific internal error text");
        check(Commands.teleportFailureMessage(config, failed, "spawn")
                        .equals("§cInternal error executing /spawn. Check server log."),
                "spawn teleport invariant failure gets command-specific internal error text");
        check(!Commands.teleportFailureMessage(config, failed, "home").toLowerCase().contains("safe"),
                "teleport invariant failure never exposes destination-safety wording");
        check(!Commands.continuesTeleportCompletion(failed),
                "failed teleport stops before cooldown and passenger notifications");
    }

    private static void disabledSpawnFeedbackDoesNotClaimAWorldIsMissing() {
        String message = new OmwhConfig().spawnDisabledMessage.toLowerCase();
        check(message.contains("disabled"), "disabled spawn has an explicit policy message");
        check(!message.contains("missing") && !message.contains("cannot determine"),
                "disabled spawn is not reported as a missing world");
    }

    private static void pendingSpawnLifecycleRejectsDuplicatesAndCompletesExactlyOnce() {
        Commands.PendingSearches<String, Integer> pending = new Commands.PendingSearches<>();
        AtomicLong steps = new AtomicLong();
        List<Integer> completions = new ArrayList<>();
        Commands.PendingWork<Integer> work = (candidateBudget, worldBudget) -> {
            long current = steps.incrementAndGet();
            return current == 3 ? Commands.PendingStep.complete(7, 1, 1) : Commands.PendingStep.pending(1, 1);
        };
        check(pending.add("player", work), "first /spawn search becomes pending");
        check(!pending.add("player", work), "duplicate /spawn cannot replace or double a pending search");
        check(new OmwhConfig().spawnPendingMessage.toLowerCase().contains("already")
                        && new OmwhConfig().spawnPendingMessage.toLowerCase().contains("progress"),
                "duplicate pending invocation has explicit player feedback");
        pending.tick(1, 1, completions::add);
        pending.tick(1, 1, completions::add);
        check(completions.isEmpty() && pending.size() == 1, "incomplete search remains pending");
        pending.tick(1, 1, completions::add);
        pending.tick(1, 1, completions::add);
        check(completions.equals(List.of(7)) && pending.size() == 0,
                "successful completion is delivered and removed exactly once");
        check(pending.add("failure", (candidateBudget, worldBudget) ->
                        Commands.PendingStep.complete(-1, 1, 1)), "failed search completion added");
        pending.tick(1, 1, completions::add);
        pending.tick(1, 1, completions::add);
        check(completions.equals(List.of(7, -1)) && pending.size() == 0,
                "failed completion is delivered and removed exactly once");

        check(pending.add("disconnect", (candidateBudget, worldBudget) ->
                Commands.PendingStep.pending(1, 1)), "disconnect search added");
        pending.remove("disconnect");
        check(pending.size() == 0, "disconnect cleanup is bounded direct removal");
        check(pending.add("a", (candidateBudget, worldBudget) -> Commands.PendingStep.pending(1, 1))
                        && pending.add("b", (candidateBudget, worldBudget) -> Commands.PendingStep.pending(1, 1)),
                "stop-cleanup searches added");
        pending.clear();
        check(pending.size() == 0, "server-stop cleanup clears all pending searches");
    }

    private static void pendingSpawnSchedulingSharesOneFairServerWideBudget() {
        Commands.PendingSearches<Integer, Integer> pending = new Commands.PendingSearches<>();
        int players = 100;
        int[] progress = new int[players];
        for (int player = 0; player < players; player++) {
            int id = player;
            check(pending.add(id, (candidateBudget, worldBudget) -> {
                check(candidateBudget > 0 && worldBudget > 0, "scheduler supplies positive slices");
                progress[id]++;
                return Commands.PendingStep.pending(1, 1);
            }), "fair-search fixture added");
        }

        Commands.PendingTick first = pending.tick(Commands.SEARCH_CANDIDATES_PER_TICK,
                Commands.SEARCH_WORLD_WORK_PER_TICK, ignored -> { });
        check(first.candidatesUsed() <= Commands.SEARCH_CANDIDATES_PER_TICK
                        && first.worldWorkUsed() <= Commands.SEARCH_WORLD_WORK_PER_TICK,
                "all players share one aggregate server-wide allowance");
        for (int player = 0; player < players; player++) {
            check(progress[player] > 0, "round-robin gives every pending player progress in a busy tick");
        }

        int[] before = progress.clone();
        Commands.PendingTick second = pending.tick(50, 50, ignored -> { });
        check(second.candidatesUsed() == 50 && second.worldWorkUsed() == 50,
                "smaller aggregate allowance is consumed exactly once across the queue");
        int advanced = 0;
        for (int player = 0; player < players; player++) if (progress[player] > before[player]) advanced++;
        check(advanced == 50, "round-robin advances distinct players before returning to the front");
    }

    private static void productionPendingSchedulerSlicesRevalidationAndRetiresStaleWork() {
        Object chunk = new Object();
        DestinationSafety.ChunkResidency resident = DestinationSafety.ChunkResidency.captureValues(
                -16, 16, -16, 16, ignored -> chunk);
        java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> safeStates =
                position -> position.getY() == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        SpawnDestination.Search search = new SpawnDestination.Search(
                SpawnDestination.offsets(0, 0).iterator(),
                DestinationSafety.SpawnProbe.controlled(
                        BlockPos.ZERO, 14, 16, resident, safeStates), false);
        SpawnDestination.Pending route = SpawnDestination.Pending.controlled(
                search, BlockPos.ZERO, 14,
                feet -> DestinationSafety.SpawnProbe.controlled(feet, 14, 16, resident, safeStates));

        Commands.PendingSearches<String, SpawnDestination.Result> pending = new Commands.PendingSearches<>();
        for (int stale = 0; stale < 3; stale++) {
            check(pending.add("stale-" + stale, (candidateBudget, worldBudget) ->
                    Commands.PendingStep.complete(new SpawnDestination.Result(
                            SpawnDestination.Outcome.UNSAFE, null), 0, 0)), "stale completion added");
        }
        check(pending.add("valid", (candidateBudget, worldBudget) -> {
            SpawnDestination.Tick used = route.tick(candidateBudget, worldBudget);
            return route.complete()
                    ? Commands.PendingStep.complete(route.result(), used.candidatesStarted(), used.worldWork())
                    : Commands.PendingStep.pending(used.candidatesStarted(), used.worldWork());
        }), "production pending route added behind stale completions");

        List<SpawnDestination.Result> completions = new ArrayList<>();

        long totalWorldWork = 0;
        int schedulerTicks = 0;
        Commands.PendingTick first = null;
        while (pending.size() > 0) {
            Commands.PendingTick used = pending.tick(Commands.SEARCH_CANDIDATES_PER_TICK,
                    Commands.PENDING_ADVANCEMENT_WORK_PER_TICK, completions::add);
            if (first == null) first = used;
            check(used.candidatesUsed() <= Commands.SEARCH_CANDIDATES_PER_TICK
                            && used.worldWorkUsed() <= Commands.SEARCH_WORLD_WORK_PER_TICK,
                    "actual PendingSearches layer bounds aggregate production work every tick");
            totalWorldWork += used.worldWorkUsed();
            schedulerTicks++;
            check(schedulerTicks < 30, "search and maximum revalidation complete over bounded slices");
        }

        check(first != null && first.itemsCompleted() >= 3 && first.worldWorkUsed() > 0,
                "multiple zero-work stale completions retire before later valid work advances in the same tick");
        Commands.PendingStep<String> failed = Commands.failedPendingStep("failed", 7, 11);
        check(failed.complete() && failed.candidatesUsed() == 7 && failed.worldWorkUsed() == 11,
                "pending exceptions conservatively consume their full assigned slice");
        check(!Commands.shouldLoadDestinationChunks(true)
                        && Commands.shouldLoadDestinationChunks(false),
                "incremental spawn skips broad chunk generation while immediate routes retain preparation");
        long revalidationWorldWork = totalWorldWork - 46_372;
        check(revalidationWorldWork == 46_376,
                "maximum live revalidation charges four final snapshot probes plus safety work");
        check(totalWorldWork == 92_748,
                "search, final snapshot probes, and fresh revalidation share production accounting");
        check(completions.size() == 4
                        && completions.getLast().outcome() == SpawnDestination.Outcome.ACCEPT
                        && completions.getLast().incrementalDestinationReady(),
                "stale and accepted lifecycle completions are each delivered exactly once");
        check(schedulerTicks > 1,
                "maximum production search plus live revalidation remains genuinely resumable");

        System.out.printf("Pending scheduler ticks=%d totalWorldWork=%d revalidationWorldWork=%d completions=%d%n",
                schedulerTicks, totalWorldWork, revalidationWorldWork, completions.size());
    }

    private static void commandsTickPassesASeparatePendingAdvancementSlice() {
        OmwhConfig config = new OmwhConfig();
        Commands commands = new Commands(config, new Cooldowns(config, () -> 1_000L));
        AtomicInteger suppliedWork = new AtomicInteger();
        check(commands.enqueuePending(UUID.randomUUID(), (candidateBudget, worldBudget) -> {
                    suppliedWork.set(worldBudget);
                    return Commands.PendingStep.pending(1, worldBudget);
                }), "pending advancement probe enqueued");
        commands.tick();
        check(suppliedWork.get() == Commands.PENDING_ADVANCEMENT_WORK_PER_TICK,
                "Commands.tick passes the separately derived pending advancement slice");
        check(Commands.PENDING_ADVANCEMENT_WORK_PER_TICK < Commands.SEARCH_WORLD_WORK_PER_TICK,
                "pending advancement is strictly smaller than the aggregate immediate-plus-pending allowance");
        check(Commands.PENDING_ADVANCEMENT_WORK_PER_TICK
                        == TeleportService.LIFECYCLE_VALIDATION_WORK
                        + Commands.PENDING_ROUTE_WORK_SLICE
                        + TeleportService.COMPLETION_WORK,
                "pending slice reserves lifecycle validation, bounded route advancement, and final completion");
    }

    private static void productionCoordinatorOwnsLifecycleFinalGatesAndDispatch() {
        List<String> order = new ArrayList<>();
        Commands.CoordinatedPending<String> coordinated = new Commands.CoordinatedPending<>(
                (candidateBudget, worldBudget) -> {
                    order.add("route");
                    return Commands.PendingStep.complete("accepted", 1, 1);
                }, new Commands.CoordinatorHooks<>() {
                    @Override public TeleportService.LifecycleStatus lifecycleStatus() {
                        order.add("lifecycle");
                        return TeleportService.LifecycleStatus.CURRENT;
                    }
                    @Override public void lifecycleRejected(TeleportService.LifecycleStatus status) {
                        throw new AssertionError("current lifecycle cannot reject");
                    }
                    @Override public boolean finalAdmission(String value) { order.add("admission:" + value); return true; }
                    @Override public boolean anchorCurrent(String value) { order.add("anchor:" + value); return true; }
                    @Override public void complete(String value) { order.add("complete:" + value); }
                }, 2, 3);

        Commands.PendingStep<Void> first = coordinated.step(1, 1,
                2 + 3 + Commands.PENDING_ROUTE_MINIMUM_PROGRESS_WORK - 1);
        check(!first.complete() && first.candidatesUsed() == 0 && first.worldWorkUsed() == 0,
                "coordinator waits until lifecycle, atomic route progress, and completion are all reserved");
        Commands.PendingStep<Void> second = coordinated.step(1, 1,
                2 + 3 + Commands.PENDING_ROUTE_MINIMUM_PROGRESS_WORK);
        check(second.complete() && second.candidatesUsed() == 1 && second.worldWorkUsed() == 6,
                "reserved lifecycle, route, and completion dispatch make progress without deadlock");
        check(order.equals(List.of("lifecycle", "route", "admission:accepted",
                        "anchor:accepted", "complete:accepted")),
                "production coordinator orders lifecycle, route, final admission, anchor, and completion dispatch");
    }

    private static void productionFactoryRunsFinalGatesAndOversizeFeedbackThroughCommandsTick() {
        OmwhConfig config = new OmwhConfig();
        AtomicLong now = new AtomicLong(1_000L);
        Cooldowns cooldowns = new Cooldowns(config, now::get);
        Commands commands = new Commands(config, cooldowns);
        UUID player = UUID.randomUUID();
        BlockPos acceptedAnchor = new BlockPos(4, 70, -2);
        List<String> events = new ArrayList<>();

        Commands.PendingWork<Void> accepted = commands.createSpawnCoordinator(
                (candidateBudget, worldBudget) -> Commands.PendingStep.complete("accepted", 1, 1),
                () -> TeleportService.LifecycleStatus.CURRENT,
                value -> Commands.finalCooldownAdmission(cooldowns, player, config, events::add),
                value -> SpawnDestination.matchesSearchAnchor(acceptedAnchor, acceptedAnchor),
                value -> events.add("complete:" + value),
                status -> events.add("lifecycle:" + status));
        check(commands.enqueuePending(player, accepted), "production-created accepted coordinator enqueued");
        commands.tick();
        check(events.equals(List.of("complete:accepted")),
                "Commands.tick runs cooldown admission, anchor comparison, and completion dispatch");

        cooldowns.recordRegular(player);
        events.clear();
        check(commands.enqueuePending(player, commands.createSpawnCoordinator(
                (candidateBudget, worldBudget) -> Commands.PendingStep.complete("blocked", 1, 1),
                () -> TeleportService.LifecycleStatus.CURRENT,
                value -> Commands.finalCooldownAdmission(cooldowns, player, config, events::add),
                value -> true, value -> events.add("complete:" + value),
                status -> events.add("lifecycle:" + status))), "cooldown coordinator enqueued");
        commands.tick();
        check(events.size() == 1 && !events.getFirst().startsWith("complete:"),
                "final cooldown denial sends feedback and suppresses completion");

        events.clear();
        check(commands.enqueuePending(player, commands.createSpawnCoordinator(
                (candidateBudget, worldBudget) -> Commands.PendingStep.pending(1, 1),
                () -> TeleportService.LifecycleStatus.TOO_LARGE,
                value -> true, value -> true, value -> events.add("complete"),
                status -> events.add(status == TeleportService.LifecycleStatus.TOO_LARGE
                        ? config.passengerTreeTooLargeMessage : "stale"))),
                "oversized lifecycle coordinator enqueued");
        commands.tick();
        check(events.equals(List.of(config.passengerTreeTooLargeMessage)),
                "oversized pending lifecycle retires with explicit passenger-cap feedback");
    }

    private static void admissionAndLifecycleWorkShareHardAggregateAllowances() {
        int admissionLimit = SpawnDestination.ADMISSION_SNAPSHOT_PROBE_WORK
                + TeleportService.LIFECYCLE_CAPTURE_WORK;
        Commands.TickWorkAllowance admission = new Commands.TickWorkAllowance(admissionLimit);
        check(admission.claim(SpawnDestination.ADMISSION_SNAPSHOT_PROBE_WORK)
                        && admission.claim(TeleportService.LIFECYCLE_CAPTURE_WORK),
                "one admission snapshot and one maximum valid lifecycle capture fit the aggregate allowance");
        check(!admission.claim(1) && admission.remaining() == 0,
                "another synchronous probe cannot exceed the per-tick aggregate allowance");
        admission.reset();
        check(admission.remaining() == admissionLimit,
                "tick boundary restores exactly the mechanically derived allowance");

        Commands.PendingSearches<Integer, Void> pending = new Commands.PendingSearches<>();
        AtomicLong lifecycleChecks = new AtomicLong();
        AtomicLong routeSteps = new AtomicLong();
        int crowdedRequests = 1_000;
        for (int request = 0; request < crowdedRequests; request++) {
            Commands.CoordinatedPending<Void> coordinated = new Commands.CoordinatedPending<>(
                    (candidateBudget, worldBudget) -> {
                        routeSteps.incrementAndGet();
                        return Commands.PendingStep.pending(1, 1);
                    }, new Commands.CoordinatorHooks<>() {
                        @Override public TeleportService.LifecycleStatus lifecycleStatus() {
                            lifecycleChecks.incrementAndGet();
                            return TeleportService.LifecycleStatus.CURRENT;
                        }
                        @Override public void lifecycleRejected(TeleportService.LifecycleStatus status) {
                            throw new AssertionError("current lifecycle cannot reject");
                        }
                        @Override public boolean finalAdmission(Void value) { throw new AssertionError("not complete"); }
                        @Override public boolean anchorCurrent(Void value) { throw new AssertionError("not complete"); }
                        @Override public void complete(Void value) { throw new AssertionError("not complete"); }
                    }, TeleportService.LIFECYCLE_VALIDATION_WORK, TeleportService.COMPLETION_WORK);
            int id = request;
            check(pending.add(id, (candidateBudget, worldBudget) ->
                    coordinated.step(1, candidateBudget, worldBudget)), "crowded coordinated request added");
        }
        Commands.PendingTick used = pending.tick(Commands.SEARCH_CANDIDATES_PER_TICK,
                Commands.PENDING_ADVANCEMENT_WORK_PER_TICK, ignored -> { });
        check(used.worldWorkUsed() <= Commands.SEARCH_WORLD_WORK_PER_TICK,
                "crowded lifecycle traversal cannot exceed aggregate per-tick work");
        check(lifecycleChecks.get() >= routeSteps.get()
                        && lifecycleChecks.get() - routeSteps.get() <= 1
                        && routeSteps.get() > 0 && lifecycleChecks.get() < crowdedRequests,
                "lifecycle accounting bounds a crowded queue and preserves the next round-robin position");
        long firstTickRoutes = routeSteps.get();
        pending.tick(Commands.SEARCH_CANDIDATES_PER_TICK,
                Commands.PENDING_ADVANCEMENT_WORK_PER_TICK, ignored -> { });
        check(routeSteps.get() > firstTickRoutes && routeSteps.get() < crowdedRequests,
                "the next crowded slice advances waiting requests before returning to the front");
    }

    private static void zeroProgressKeepsTheBlockedRequestNext() {
        Commands.PendingSearches<Integer, Void> pending = new Commands.PendingSearches<>();
        List<Integer> progressOrder = new ArrayList<>();
        for (int request = 0; request < 4; request++) {
            int id = request;
            Commands.CoordinatedPending<Void> coordinated = new Commands.CoordinatedPending<>(
                    (candidateBudget, worldBudget) -> {
                        progressOrder.add(id);
                        return Commands.PendingStep.pending(1, worldBudget);
                    }, new Commands.CoordinatorHooks<>() {
                        @Override public TeleportService.LifecycleStatus lifecycleStatus() {
                            return TeleportService.LifecycleStatus.CURRENT;
                        }
                        @Override public void lifecycleRejected(TeleportService.LifecycleStatus status) {
                            throw new AssertionError("current lifecycle cannot reject");
                        }
                        @Override public boolean finalAdmission(Void value) { throw new AssertionError("not complete"); }
                        @Override public boolean anchorCurrent(Void value) { throw new AssertionError("not complete"); }
                        @Override public void complete(Void value) { throw new AssertionError("not complete"); }
                    }, TeleportService.LIFECYCLE_VALIDATION_WORK, TeleportService.COMPLETION_WORK);
            check(pending.add(id, (candidateBudget, worldBudget) ->
                    coordinated.step(id + 1L, candidateBudget, worldBudget)),
                    "maximum-work fairness request added");
        }
        for (int tick = 0; tick < 4; tick++) {
            pending.tick(Commands.SEARCH_CANDIDATES_PER_TICK,
                    Commands.PENDING_ADVANCEMENT_WORK_PER_TICK, ignored -> { });
        }
        check(progressOrder.subList(0, 4).equals(List.of(0, 1, 2, 3)),
                "a blocked maximum-work request stays next instead of starving behind alternating peers");
    }

    private static void pendingCommandAdmissionCancelsOnlyCompetingTeleports() {
        check(Commands.pendingSpawnAction(true, false) == Commands.PendingSpawnAction.REFUSE,
                "duplicate normal /spawn remains refused");
        check(Commands.pendingSpawnAction(true, true) == Commands.PendingSpawnAction.CANCEL_AND_CONTINUE,
                "/spawn force cancels the stale normal search and uses ordinary force admission");
        check(Commands.pendingSpawnAction(false, false) == Commands.PendingSpawnAction.CONTINUE,
                "a fresh normal /spawn proceeds");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
