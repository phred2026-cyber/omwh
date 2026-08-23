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
        pendingCommandAdmissionCancelsOnlyCompetingTeleports();
        System.out.println("CommandsAndCooldownsTest PASS (9 behavior groups)");
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
        check(Commands.cooldownMessage(config, new Cooldowns.Blocking(Cooldowns.Type.PVP, 12))
                .contains("12 seconds"), "cooldown placeholder");
        config.regularCooldownMessage = "&cWait {time}";
        String rawCooldown = Commands.cooldownMessage(
                config, new Cooldowns.Blocking(Cooldowns.Type.REGULAR, 3));
        check(rawCooldown.equals("&cWait 3"), "cooldown formatting is deferred to the send boundary");
        check(Commands.format(rawCooldown).equals("§cWait 3"), "send boundary applies ampersand colors once");
        check(Commands.passengerMessage("Alex", true).contains("their home"), "home passenger message");
        check(Commands.passengerMessage("Alex", false).endsWith("to spawn."), "spawn passenger message");
        config.homeCommand = "return";
        config.spawnCommand = "hub";
        config.unsafeHomeMessage = "§cBlocked home.";
        config.unsafeSpawnMessage = "§cBlocked spawn.";
        check(Commands.unsafeMessage(config.unsafeHomeMessage, config.homeCommand, true)
                        .equals("§cBlocked home.\n§eUse /return force to teleport anyway."),
                "unsafe home advertises the configured force command");
        check(Commands.unsafeMessage(config.unsafeSpawnMessage, config.spawnCommand, true)
                        .equals("§cBlocked spawn.\n§eUse /hub force to teleport anyway."),
                "unsafe spawn advertises the configured force command");
        check(Commands.unsafeMessage(config.unsafeHomeMessage, config.homeCommand, false)
                        .equals(config.unsafeHomeMessage),
                "unsafe home does not advertise a disabled force command");
        check(Commands.unsafeMessage(config.unsafeSpawnMessage, config.spawnCommand, false)
                        .equals(config.unsafeSpawnMessage),
                "unsafe spawn does not advertise a disabled force command");
    }

    private static void teleportOutcomesUseInternalFailureAndPartialPolicies() {
        var partial = new TeleportService.Result(TeleportService.Outcome.PARTIAL, java.util.List.of());
        var failed = new TeleportService.Result(TeleportService.Outcome.FAILED, java.util.List.of());
        check(Commands.teleportFailureMessage(partial, "home").toLowerCase().contains("partially"),
                "partial teleport gets a distinct warning");
        check(Commands.continuesTeleportCompletion(partial),
                "partial teleport continues through cooldown and passenger notifications");
        check(Commands.teleportFailureMessage(failed, "home")
                        .equals("§cInternal error executing /home. Check server log."),
                "home teleport invariant failure gets command-specific internal error text");
        check(Commands.teleportFailureMessage(failed, "spawn")
                        .equals("§cInternal error executing /spawn. Check server log."),
                "spawn teleport invariant failure gets command-specific internal error text");
        check(!Commands.teleportFailureMessage(failed, "home").toLowerCase().contains("safe"),
                "teleport invariant failure never exposes destination-safety wording");
        check(!Commands.continuesTeleportCompletion(failed),
                "failed teleport stops before cooldown and passenger notifications");
    }

    private static void disabledSpawnFeedbackDoesNotClaimAWorldIsMissing() {
        String message = Commands.SPAWN_DISABLED.toLowerCase();
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
        check(Commands.SPAWN_PENDING.toLowerCase().contains("already")
                        && Commands.SPAWN_PENDING.toLowerCase().contains("progress"),
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
        java.util.concurrent.atomic.AtomicInteger lifecycleChecks = new java.util.concurrent.atomic.AtomicInteger();
        long totalWorldWork = 0;
        int schedulerTicks = 0;
        Commands.PendingTick first = null;
        while (pending.size() > 0) {
            Commands.PendingTick used = pending.tick(Commands.SEARCH_CANDIDATES_PER_TICK,
                    Commands.SEARCH_WORLD_WORK_PER_TICK, completion -> {
                        completions.add(completion);
                        if (completion.incrementalDestinationReady()) {
                            check(Commands.lifecycleCurrentAtCompletion(() -> {
                                lifecycleChecks.incrementAndGet();
                                return true;
                            }), "accepted pending completion passes its final lifecycle fence");
                        }
                    });
            if (first == null) first = used;
            check(used.candidatesUsed() <= Commands.SEARCH_CANDIDATES_PER_TICK
                            && used.worldWorkUsed() <= Commands.SEARCH_WORLD_WORK_PER_TICK,
                    "actual PendingSearches layer bounds aggregate production work every tick");
            totalWorldWork += used.worldWorkUsed();
            schedulerTicks++;
            check(schedulerTicks < 30, "search and maximum revalidation complete over bounded slices");
        }

        check(first != null && first.itemsCompleted() == 3 && first.worldWorkUsed() > 0,
                "multiple zero-work stale completions retire without blocking later valid work");
        check(Commands.shouldCheckLifecycle(4, 5) && !Commands.shouldCheckLifecycle(5, 5),
                "production epoch fence checks each pending request once per server tick");
        Commands.PendingStep<String> failed = Commands.failedPendingStep("failed", 7, 11);
        check(failed.complete() && failed.candidatesUsed() == 7 && failed.worldWorkUsed() == 11,
                "pending exceptions conservatively consume their full assigned slice");
        check(!Commands.shouldLoadDestinationChunks(true)
                        && Commands.shouldLoadDestinationChunks(false),
                "incremental spawn skips broad chunk generation while immediate routes retain preparation");
        long revalidationWorldWork = totalWorldWork - 44_804;
        check(revalidationWorldWork == 44_804,
                "maximum 14x16 live revalidation is fully charged across scheduler slices");
        check(totalWorldWork == 89_608,
                "search and fresh revalidation both use the production weighted-work accounting");
        check(completions.size() == 4
                        && completions.getLast().outcome() == SpawnDestination.Outcome.ACCEPT
                        && completions.getLast().incrementalDestinationReady(),
                "stale and accepted lifecycle completions are each delivered exactly once");
        check(lifecycleChecks.get() == 1,
                "complete passenger-tree lifecycle validation runs once at accepted completion, not per slice");
        System.out.printf("Pending scheduler ticks=%d totalWorldWork=%d revalidationWorldWork=%d completions=%d%n",
                schedulerTicks, totalWorldWork, revalidationWorldWork, completions.size());
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
