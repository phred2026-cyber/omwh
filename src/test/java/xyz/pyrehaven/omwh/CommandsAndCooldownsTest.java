package xyz.pyrehaven.omwh;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongFunction;

public final class CommandsAndCooldownsTest {
    public static void main(String[] args) {
        cooldownPolicyUsesUuidExpiryAndPriority();
        damageCallbacksRecordOnlyFinalAppliedOutcomes();
        disconnectCleanupAndSelfDamageClassification();
        forceSyntaxFollowsTheServerSetting();
        commandFeedbackUsesSpecificMessagesColorsAndPassengerDestination();
        teleportOutcomesUseInternalFailureAndPartialPolicies();
        disabledSpawnFeedbackDoesNotClaimAWorldIsMissing();
        pendingSpawnLifecycleRejectsDuplicatesAndCompletesExactlyOnce();
        pendingWorkClosureCoversEveryTerminalExit();
        pendingCleanupIsAccountedAndRetried();
        productionCancellationCallersOwnCleanupAccounting();
        productionSpawnRouteDelegatesTicketOwnership();
        productionHomeRouteReleasesTerrainAcrossEveryCommandsTerminalExit();
        pendingHomeRespawnAuthorityChangesCancelBeforeResolutionAndReleaseExactly();
        pendingGenerationFailureRetiresAndReleasesExactlyOnce();
        pendingSpawnSchedulingSharesOneFairServerWideBudget();
        preparationProgressYieldsAfterOneQuantumPerSchedulerTick();
        productionPendingSchedulerSlicesRevalidationAndRetiresStaleWork();
        commandsTickPassesASeparatePendingAdvancementSlice();
        productionCoordinatorOwnsLifecycleFinalGatesAndDispatch();
        productionFactoryRunsFinalGatesAndOversizeFeedbackThroughCommandsTick();
        admissionAndLifecycleWorkShareHardAggregateAllowances();
        zeroProgressKeepsTheBlockedRequestNext();
        pendingCommandAdmissionCancelsOnlyCompetingTeleports();
        System.out.println("CommandsAndCooldownsTest PASS (23 behavior groups)");
    }

    private static void forceSyntaxFollowsTheServerSetting() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        OmwhConfig enabled = new OmwhConfig();
        CommandDispatcher<CommandSourceStack> enabledDispatcher = new CommandDispatcher<>();
        new OmwhCommands(enabled, new Cooldowns(enabled, () -> 0L)).register(enabledDispatcher);
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
        new OmwhCommands(disabled, new Cooldowns(disabled, () -> 0L)).register(disabledDispatcher);
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
        cooldowns.afterDamage(player, null, 1.0f, false);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.JOIN, "shorter event cannot reduce join");
        UUID attacker = UUID.randomUUID();
        cooldowns.afterDamage(player, attacker, 1.0f, false);
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
        cooldowns.afterDamage(player, UUID.randomUUID(), 1.0f, false);
        cooldowns.afterDamage(player, null, 1.0f, false);
        cooldowns.recordJoin(player);
        cooldowns.recordRegular(player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.NONE, "disabled and zero cooldowns");

        config.enableDamageCooldown = true;
        config.damageCooldownSeconds = 10;
        cooldowns.afterDamage(player, null, 1.0f, false);
        now.addAndGet(1L);
        check(cooldowns.blocking(player).remainingSeconds() == 10, "remaining time rounds up");
        check(cooldowns.blocking(UUID.fromString(player.toString())).type() == Cooldowns.Type.DAMAGE,
                "UUID state survives player replacement");
    }

    private static void damageCallbacksRecordOnlyFinalAppliedOutcomes() {
        OmwhConfig config = new OmwhConfig();
        Cooldowns cooldowns = new Cooldowns(config, () -> 1_000L);
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        cooldowns.afterDamage(victim, attacker, 4.0f, true);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.NONE,
                "shield-blocked damage starts no cooldown");
        cooldowns.afterDamage(victim, attacker, 0.0f, false);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.NONE,
                "non-positive damage starts no cooldown");

        cooldowns.afterDamage(victim, attacker, 4.0f, false);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.PVP
                        && cooldowns.blocking(attacker).type() == Cooldowns.Type.PVP,
                "nonfatal applied PvP damage records both players through AFTER_DAMAGE");
        cooldowns.remove(victim);
        cooldowns.remove(attacker);

        cooldowns.afterDeath(victim, attacker);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.PVP
                        && cooldowns.blocking(attacker).type() == Cooldowns.Type.PVP,
                "fatal PvP damage records both players through AFTER_DEATH");
        cooldowns.remove(victim);
        cooldowns.remove(attacker);

        config.enablePvpCooldown = false;
        cooldowns.afterDamage(victim, attacker, 4.0f, false);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.NONE
                        && cooldowns.blocking(attacker).type() == Cooldowns.Type.NONE,
                "disabled PvP cooldown does not fall back to ordinary damage");

        cooldowns.afterDamage(victim, victim, 4.0f, false);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.DAMAGE,
                "nonfatal self-damage uses the ordinary damage cooldown");
        cooldowns.remove(victim);
        cooldowns.afterDeath(victim, victim);
        check(cooldowns.blocking(victim).type() == Cooldowns.Type.DAMAGE,
                "fatal self-damage uses the ordinary damage cooldown");
    }

    private static void disconnectCleanupAndSelfDamageClassification() {
        OmwhConfig config = new OmwhConfig();
        Cooldowns cooldowns = new Cooldowns(config, () -> 1_000L);
        UUID player = UUID.randomUUID();
        cooldowns.afterDamage(player, player, 1.0f, false);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.DAMAGE,
                "self-inflicted damage uses the ordinary damage cooldown");
        cooldowns.remove(player);
        check(cooldowns.blocking(player).type() == Cooldowns.Type.NONE,
                "disconnect removes the player's cooldown state");
    }

    private static void commandFeedbackUsesSpecificMessagesColorsAndPassengerDestination() {
        OmwhConfig config = new OmwhConfig();
        check(OmwhCommands.renderMessage(config, config.pvpCooldownMessage,
                        new OmwhCommands.MessageValues(null, null, null, 12, false))
                .contains("12 seconds"), "cooldown placeholder");
        config.regularCooldownMessage = "&cWait {time}";
        String rawCooldown = OmwhCommands.renderMessage(config, config.regularCooldownMessage,
                new OmwhCommands.MessageValues(null, null, null, 3, false));
        check(rawCooldown.equals("&cWait 3"), "cooldown formatting is deferred to the send boundary");
        check(OmwhCommands.format(rawCooldown).equals("§cWait 3"), "send boundary applies ampersand colors once");
        check(OmwhCommands.format("Fish && Chips &aGreen").equals("Fish & Chips §aGreen"),
                "double ampersands escape a literal while single ampersand color codes still translate");
        config.passengerNotificationMessage = "&e{player} took you to {destination}.";
        check(OmwhCommands.renderMessage(config, config.passengerNotificationMessage,
                        new OmwhCommands.MessageValues(null, "Alex", config.homePassengerDestination, null, false))
                        .equals("&eAlex took you to their home."),
                "home passenger notification is configurable");
        check(OmwhCommands.renderMessage(config, config.passengerNotificationMessage,
                        new OmwhCommands.MessageValues(null, "Alex", config.spawnPassengerDestination, null, false))
                        .equals("&eAlex took you to spawn."),
                "spawn passenger notification is configurable");
        config.homeCommand = "return";
        config.spawnCommand = "hub";
        config.unsafeHomeMessage = "§cBlocked home.{forceGuidance}";
        config.unsafeSpawnMessage = "§cBlocked spawn.{forceGuidance}";
        config.forceGuidanceMessage = "\n§eUse /{command} force to teleport anyway.";
        check(OmwhCommands.renderMessage(config, config.unsafeHomeMessage,
                        new OmwhCommands.MessageValues(config.homeCommand, null, null, null, true))
                        .equals("§cBlocked home.\n§eUse /return force to teleport anyway."),
                "unsafe home advertises the configured force command");
        check(OmwhCommands.renderMessage(config, config.unsafeSpawnMessage,
                        new OmwhCommands.MessageValues(config.spawnCommand, null, null, null, true))
                        .equals("§cBlocked spawn.\n§eUse /hub force to teleport anyway."),
                "unsafe spawn advertises the configured force command");
        check(OmwhCommands.renderMessage(config, config.unsafeHomeMessage,
                        new OmwhCommands.MessageValues(config.homeCommand, null, null, null, false))
                        .equals("§cBlocked home."),
                "unsafe home does not advertise a disabled force command");
        check(OmwhCommands.renderMessage(config, config.unsafeSpawnMessage,
                        new OmwhCommands.MessageValues(config.spawnCommand, null, null, null, false))
                        .equals("§cBlocked spawn."),
                "unsafe spawn does not advertise a disabled force command");
        check(OmwhCommands.renderMessageWithForceGuidance(config, "§cLegacy unsafe text.", config.homeCommand)
                        .equals("§cLegacy unsafe text.\n§eUse /return force to teleport anyway."),
                "existing unsafe messages without the placeholder retain force guidance");
        check(OmwhCommands.renderMessage(config, config.vehicleTooLargeMessage,
                        new OmwhCommands.MessageValues(config.homeCommand, null, null, null, true))
                        .contains("/return force"),
                "home vehicle-too-large feedback offers its configured force command");
        check(OmwhCommands.renderMessage(config, config.vehicleTooLargeMessage,
                        new OmwhCommands.MessageValues(config.spawnCommand, null, null, null, true))
                        .contains("/hub force"),
                "spawn vehicle-too-large feedback offers its configured force command");
        check(!OmwhCommands.renderMessage(config, config.vehicleTooLargeMessage,
                        new OmwhCommands.MessageValues(config.homeCommand, null, null, null, false))
                        .contains("force"),
                "vehicle-too-large feedback does not advertise disabled force");
        config.enableForceOverride = false;
        check(OmwhCommands.renderMessageWithForceGuidance(config, "§cLegacy unsafe text.", config.homeCommand)
                        .equals("§cLegacy unsafe text."),
                "existing unsafe messages do not advertise disabled force");
        config.enableForceOverride = true;
        config.forceGuidanceMessage = "";
        check(OmwhCommands.renderMessageWithForceGuidance(config, "§cLegacy unsafe text.", config.homeCommand)
                        .equals("§cLegacy unsafe text."),
                "empty force guidance disables both placeholder and compatibility guidance");
        config.forceGuidanceMessage = "\n§eUse /{command} force to teleport anyway.";
        check(config.passengerTreeTooLargeMessage.toLowerCase().contains("passenger")
                        && config.passengerTreeTooLargeMessage.toLowerCase().contains("large"),
                "passenger cap denial has deliberate configurable feedback");
        config.homeSuccessMessage = "&a/{command} complete.";
        check(OmwhCommands.renderMessage(config, config.homeSuccessMessage,
                        new OmwhCommands.MessageValues(config.homeCommand, null, null, null, false))
                        .equals("&a/return complete."),
                "success feedback is rendered from configuration");
    }

    private static void teleportOutcomesUseInternalFailureAndPartialPolicies() {
        OmwhConfig config = new OmwhConfig();
        var partial = new TeleportService.Result(TeleportService.Outcome.PARTIAL, java.util.List.of());
        var failed = new TeleportService.Result(TeleportService.Outcome.FAILED, java.util.List.of());
        check(OmwhCommands.teleportFailureMessage(config, partial, "home")
                        .equals("§eCheck your group — some passengers may not have made the trip."),
                "post-movement verification failure gets clear player guidance");
        check(OmwhCommands.continuesTeleportCompletion(partial),
                "partial teleport continues through cooldown and passenger notifications");
        check(OmwhCommands.teleportFailureMessage(config, failed, "home")
                        .equals("§cSomething went wrong with /home. Please let a server admin know."),
                "home teleport invariant failure gets command-specific internal error text");
        check(OmwhCommands.teleportFailureMessage(config, failed, "spawn")
                        .equals("§cSomething went wrong with /spawn. Please let a server admin know."),
                "spawn teleport invariant failure gets command-specific internal error text");
        check(!OmwhCommands.teleportFailureMessage(config, failed, "home").toLowerCase().contains("safe"),
                "teleport invariant failure never exposes destination-safety wording");
        check(!OmwhCommands.continuesTeleportCompletion(failed),
                "failed teleport stops before cooldown and passenger notifications");
    }

    private static void disabledSpawnFeedbackDoesNotClaimAWorldIsMissing() {
        OmwhConfig config = new OmwhConfig();
        String unavailableHome = OmwhCommands.homeDenialMessage(
                config, HomeDestination.Outcome.CURRENT_WORLD_UNAVAILABLE);
        check(unavailableHome.equals(config.currentWorldUnavailableMessage),
                "unavailable current world uses its dedicated home message");
        check(!unavailableHome.toLowerCase().contains("force"),
                "unavailable current world never advertises force");
        String message = config.spawnDisabledMessage.toLowerCase();
        check(message.contains("disabled"), "disabled spawn has an explicit policy message");
        check(!message.contains("missing") && !message.contains("cannot determine"),
                "disabled spawn is not reported as a missing world");
    }

    private static void pendingSpawnLifecycleRejectsDuplicatesAndCompletesExactlyOnce() {
        OmwhCommands.PendingSearches<String, Integer> pending = new OmwhCommands.PendingSearches<>();
        AtomicLong steps = new AtomicLong();
        List<Integer> completions = new ArrayList<>();
        OmwhCommands.PendingWork<Integer> work = (candidateBudget, worldBudget) -> {
            long current = steps.incrementAndGet();
            return current == 3 ? OmwhCommands.PendingStep.complete(7, 1, 1) : OmwhCommands.PendingStep.pending(1, 1);
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
                        OmwhCommands.PendingStep.complete(-1, 1, 1)), "failed search completion added");
        pending.tick(1, 1, completions::add);
        pending.tick(1, 1, completions::add);
        check(completions.equals(List.of(7, -1)) && pending.size() == 0,
                "failed completion is delivered and removed exactly once");

        check(pending.add("disconnect", (candidateBudget, worldBudget) ->
                OmwhCommands.PendingStep.pending(1, 1)), "disconnect search added");
        pending.cancel("disconnect", new OmwhCommands.TickWorkAllowance(1));
        check(pending.size() == 0, "disconnect cleanup is bounded direct removal");
        check(pending.add("a", (candidateBudget, worldBudget) -> OmwhCommands.PendingStep.pending(1, 1))
                        && pending.add("b", (candidateBudget, worldBudget) -> OmwhCommands.PendingStep.pending(1, 1)),
                "stop-cleanup searches added");
        pending.clearTerminal();
        check(pending.size() == 0, "server-stop cleanup clears all pending searches");
    }

    private static void pendingWorkClosureCoversEveryTerminalExit() {
        class ClosingWork implements OmwhCommands.PendingWork<String> {
            private final boolean complete;
            private final AtomicInteger closes;
            ClosingWork(boolean complete, AtomicInteger closes) {
                this.complete = complete;
                this.closes = closes;
            }
            @Override public OmwhCommands.PendingStep<String> step(int candidateBudget, int worldWorkBudget) {
                return complete ? OmwhCommands.PendingStep.complete("done", 0, 0)
                        : OmwhCommands.PendingStep.pending(1, 1);
            }
            @Override public void close() { closes.incrementAndGet(); }
        }

        AtomicInteger terminalCloses = new AtomicInteger();
        OmwhCommands.PendingSearches<String, String> terminal = new OmwhCommands.PendingSearches<>();
        check(terminal.add("terminal", new ClosingWork(true, terminalCloses)), "terminal work added");
        terminal.tick(1, 1, value -> check(terminalCloses.get() == 0,
                "completion callback runs while accepted destination tickets are still retained"));
        check(terminalCloses.get() == 1, "normal terminal completion closes after its callback");

        AtomicInteger removedCloses = new AtomicInteger();
        OmwhCommands.PendingSearches<String, String> removed = new OmwhCommands.PendingSearches<>();
        removed.add("home-or-force", new ClosingWork(false, removedCloses));
        removed.cancel("home-or-force", new OmwhCommands.TickWorkAllowance(1));
        check(removedCloses.get() == 1, "home, force, disconnect, and respawn removal closes pending work");

        AtomicInteger clearedCloses = new AtomicInteger();
        OmwhCommands.PendingSearches<String, String> cleared = new OmwhCommands.PendingSearches<>();
        cleared.add("one", new ClosingWork(false, clearedCloses));
        cleared.add("two", new ClosingWork(false, clearedCloses));
        cleared.clearTerminal();
        check(clearedCloses.get() == 2, "server stop closes every pending request");

        AtomicInteger callbackFailureCloses = new AtomicInteger();
        OmwhCommands.PendingSearches<String, String> callbackFailure = new OmwhCommands.PendingSearches<>();
        callbackFailure.add("callback-failure", new ClosingWork(true, callbackFailureCloses));
        boolean threw = false;
        try {
            callbackFailure.tick(1, 1, value -> { throw new IllegalStateException("completion failed"); });
        } catch (IllegalStateException expected) {
            threw = expected.getMessage().contains("completion failed");
        }
        check(threw && callbackFailureCloses.get() == 1 && callbackFailure.size() == 0,
                "exceptional terminal callbacks still retire and close exactly once");
    }

    private static void pendingCleanupIsAccountedAndRetried() {
        class AccountedClose implements OmwhCommands.PendingWork<String> {
            private final AtomicInteger closes = new AtomicInteger();
            private boolean failOnce;
            private boolean released;
            AccountedClose(boolean failOnce) { this.failOnce = failOnce; }
            @Override public OmwhCommands.PendingStep<String> step(int candidateBudget, int worldWorkBudget) {
                return OmwhCommands.PendingStep.complete("done", 1, 1);
            }
            @Override public int closeWork() { return released ? 0 : 7; }
            @Override public void close() {
                closes.incrementAndGet();
                if (failOnce) {
                    failOnce = false;
                    throw new IllegalStateException("release failed once");
                }
                released = true;
            }
        }

        AccountedClose terminalWork = new AccountedClose(false);
        OmwhCommands.PendingSearches<String, String> terminal = new OmwhCommands.PendingSearches<>();
        terminal.add("terminal", terminalWork);
        OmwhCommands.PendingTick terminalUsed = terminal.tick(1, 8,
                value -> check(terminalWork.closes.get() == 0,
                        "accepted tickets remain through the completion callback"));
        check(terminalUsed.worldWorkUsed() == 8 && terminalWork.closes.get() == 1,
                "terminal ticket removal is charged after completion dispatch");

        AccountedClose removedWork = new AccountedClose(false);
        OmwhCommands.PendingSearches<String, String> removed = new OmwhCommands.PendingSearches<>();
        removed.add("remove", removedWork);
        OmwhCommands.TickWorkAllowance removalAllowance = new OmwhCommands.TickWorkAllowance(7);
        removed.cancel("remove", removalAllowance);
        check(removalAllowance.remaining() == 0 && removedWork.closes.get() == 1,
                "direct cancellation reports its exact ticket-removal work");

        AccountedClose firstClear = new AccountedClose(false);
        AccountedClose secondClear = new AccountedClose(false);
        OmwhCommands.PendingSearches<String, String> cleared = new OmwhCommands.PendingSearches<>();
        cleared.add("first", firstClear);
        cleared.add("second", secondClear);
        cleared.clearTerminal();
        check(firstClear.closes.get() == 1 && secondClear.closes.get() == 1,
                "server-stop cleanup reports every pending ticket removal");

        AccountedClose retryWork = new AccountedClose(true);
        OmwhCommands.PendingSearches<String, String> retry = new OmwhCommands.PendingSearches<>();
        retry.add("retry", retryWork);
        OmwhCommands.PendingTick failedClose = retry.tick(1, 8, ignored -> { });
        check(failedClose.worldWorkUsed() == 8 && retry.cleanupSize() == 1,
                "failed ticket removal remains owned after its charged attempt");
        OmwhCommands.PendingTick successfulRetry = retry.tick(1, 7, ignored -> { });
        check(successfulRetry.worldWorkUsed() == 7 && retry.cleanupSize() == 0
                        && retryWork.closes.get() == 2,
                "later scheduler tick charges and completes the retained cleanup retry");
    }

    private static void productionCancellationCallersOwnCleanupAccounting() {
        class AccountedClose implements OmwhCommands.PendingWork<Void> {
            private final AtomicInteger closes = new AtomicInteger();
            private final int work;
            private boolean failOnce;
            private boolean released;
            private java.util.function.BooleanSupplier releaseAllowed = () -> true;
            AccountedClose(boolean failOnce) { this(failOnce, 7); }
            AccountedClose(boolean failOnce, int work) {
                this.failOnce = failOnce;
                this.work = work;
            }
            AccountedClose releaseOnlyWhen(java.util.function.BooleanSupplier allowed) {
                releaseAllowed = allowed;
                return this;
            }
            @Override public OmwhCommands.PendingStep<Void> step(int candidateBudget, int worldWorkBudget) {
                return OmwhCommands.PendingStep.pending(1, 1);
            }
            @Override public int closeWork() { return released ? 0 : work; }
            @Override public void close() {
                check(releaseAllowed.getAsBoolean(), "cleanup allowance is claimed before ticket release");
                closes.incrementAndGet();
                if (failOnce) {
                    failOnce = false;
                    throw new IllegalStateException("release failed once");
                }
                released = true;
            }
        }

        java.util.function.Function<Integer, OmwhCommands> commandsWithBudget = limit -> {
            OmwhConfig config = new OmwhConfig();
            return new OmwhCommands(config, new Cooldowns(config, () -> 1_000L), limit);
        };

        OmwhCommands home = commandsWithBudget.apply(7);
        AccountedClose homeWork = new AccountedClose(false)
                .releaseOnlyWhen(() -> home.remainingServerWork() == 0);
        UUID homePlayer = UUID.randomUUID();
        check(home.enqueuePending(homePlayer, homeWork), "/home cancellation fixture enqueued");
        home.cancelPending(homePlayer);
        check(homeWork.closes.get() == 1 && home.remainingServerWork() == 0,
                "/home claims exact cleanup work before releasing pending terrain");

        OmwhCommands forcedSpawn = commandsWithBudget.apply(7);
        AccountedClose forcedWork = new AccountedClose(false)
                .releaseOnlyWhen(() -> forcedSpawn.remainingServerWork() == 0);
        UUID forcedPlayer = UUID.randomUUID();
        check(forcedSpawn.enqueuePending(forcedPlayer, forcedWork), "forced /spawn cancellation fixture enqueued");
        forcedSpawn.cancelPending(forcedPlayer);
        check(forcedWork.closes.get() == 1 && forcedSpawn.remainingServerWork() == 0,
                "forced /spawn claims exact cleanup work before continuing its route");

        OmwhConfig routeConfig = new OmwhConfig();
        OmwhCommands routeBudget = new OmwhCommands(routeConfig,
                new Cooldowns(routeConfig, () -> 1_000L));
        AccountedClose maximumCancellation = new AccountedClose(false,
                OmwhCommands.MAX_PENDING_TICKET_RELEASE_WORK);
        UUID routePlayer = UUID.randomUUID();
        check(routeBudget.enqueuePending(routePlayer, maximumCancellation),
                "maximum forced-route cancellation fixture enqueued");
        routeBudget.cancelPending(routePlayer);
        check(routeBudget.remainingServerWork()
                        >= TeleportService.LIFECYCLE_CAPTURE_WORK + OmwhCommands.MAX_IMMEDIATE_ROUTE_WORK,
                "maximum ticket cleanup leaves the promised forced route inside the same shared allowance");

        OmwhCommands disconnect = commandsWithBudget.apply(7);
        AccountedClose disconnectWork = new AccountedClose(false)
                .releaseOnlyWhen(() -> disconnect.remainingServerWork() == 0);
        UUID disconnectedPlayer = UUID.randomUUID();
        check(disconnect.enqueuePending(disconnectedPlayer, disconnectWork), "disconnect fixture enqueued");
        disconnect.cancelPending(disconnectedPlayer);
        check(disconnectWork.closes.get() == 1 && disconnect.remainingServerWork() == 0,
                "disconnect cleanup is charged through the OmwhCommands allowance");

        OmwhCommands respawn = commandsWithBudget.apply(7);
        AccountedClose respawnWork = new AccountedClose(false)
                .releaseOnlyWhen(() -> respawn.remainingServerWork() == 0);
        UUID respawnedPlayer = UUID.randomUUID();
        check(respawn.enqueuePending(respawnedPlayer, respawnWork), "respawn fixture enqueued");
        respawn.cancelPending(respawnedPlayer);
        check(respawnWork.closes.get() == 1 && respawn.remainingServerWork() == 0,
                "respawn cleanup is charged through the OmwhCommands allowance");

        OmwhCommands deferred = commandsWithBudget.apply(7);
        AccountedClose priorWork = new AccountedClose(false, 1)
                .releaseOnlyWhen(() -> deferred.remainingServerWork() == 6);
        UUID priorPlayer = UUID.randomUUID();
        check(deferred.enqueuePending(priorPlayer, priorWork), "prior cleanup fixture enqueued");
        deferred.cancelPending(priorPlayer);
        AccountedClose deferredWork = new AccountedClose(false)
                .releaseOnlyWhen(() -> deferred.remainingServerWork() == 0);
        UUID deferredPlayer = UUID.randomUUID();
        check(deferred.enqueuePending(deferredPlayer, deferredWork), "deferred cleanup fixture enqueued");
        deferred.cancelPending(deferredPlayer);
        check(deferredWork.closes.get() == 0,
                "live cancellation transfers cleanup when its exact work cannot be claimed");
        deferred.tick();
        check(deferredWork.closes.get() == 0,
                "the exhausted live tick does not release transferred cleanup outside its allowance");
        deferred.tick();
        check(deferredWork.closes.get() == 1,
                "a later shared-budget tick claims transferred cleanup before releasing it");

        OmwhCommands stopped = commandsWithBudget.apply(0);
        AccountedClose stoppedFirst = new AccountedClose(true);
        AccountedClose stoppedSecond = new AccountedClose(false);
        check(stopped.enqueuePending(UUID.randomUUID(), stoppedFirst)
                        && stopped.enqueuePending(UUID.randomUUID(), stoppedSecond),
                "server-stop cleanup fixtures enqueued");
        stopped.clearPending();
        check(stoppedFirst.closes.get() == 2 && stoppedSecond.closes.get() == 1,
                "SERVER_STOPPED exhaustively retries and releases every ticket without a later tick");
    }

    private static void productionSpawnRouteDelegatesTicketOwnership() {
        Set<Long> retained = new HashSet<>();
        AtomicInteger releases = new AtomicInteger();
        DestinationSafety.ChunkPreparation preparation = DestinationSafety.ChunkPreparation.controlled(
                0, 0, 0, 0, new DestinationSafety.TicketAccess() {
                    @Override public void retain(long chunk) { retained.add(chunk); }
                    @Override public Object load(long chunk) { return new Object(); }
                    @Override public void release(long chunk) {
                        check(retained.remove(chunk), "production route releases its exact retained ticket");
                        releases.incrementAndGet();
                    }
                });
        SpawnDestination.SearchStage search = new SpawnDestination.PreparedSearchStage(
                SpawnDestination.offsets(0).iterator(), false, preparation,
                ignored -> new SpawnDestination.CandidateProbe() {
                    @Override public void begin(SpawnDestination.Offset offset, SpawnDestination.ProbeKind kind) { }
                    @Override public SpawnDestination.ProbeStep step(int availableWorldWork) {
                        return new SpawnDestination.ProbeStep(SpawnDestination.ProbeOutcome.REJECTED, 1);
                    }
                }, BlockPos.ZERO, 1);
        SpawnDestination.Pending pending = new SpawnDestination.Pending(search,
                new SpawnDestination.DirectFinalStage(
                        feet -> { throw new AssertionError("unsafe search must not start final preparation"); }),
                null, BlockPos.ZERO, BlockPos.ZERO, 1, 0, 0);

        OmwhCommands.PendingWork<SpawnDestination.Result> route = OmwhCommands.pendingSpawnRoute(pending);

        OmwhConfig config = new OmwhConfig();
        OmwhCommands commands = new OmwhCommands(config, new Cooldowns(config, () -> 1_000L));
        List<SpawnDestination.Result> completions = new ArrayList<>();
        UUID player = UUID.randomUUID();
        check(commands.enqueuePending(player, commands.createPendingCoordinator(
                        route,
                        () -> TeleportService.LifecycleStatus.CURRENT,
                        value -> true,
                        value -> true,
                        completions::add,
                        status -> { throw new AssertionError("current lifecycle cannot reject"); })),
                "real /spawn pending route enqueued through OmwhCommands");

        commands.tick();
        check(retained.size() == 1 && releases.get() == 0 && completions.isEmpty(),
                "terrain tickets transfer to the pending OmwhCommands owner between ticks");
        commands.tick();
        check(retained.size() == 1 && releases.get() == 0 && completions.isEmpty(),
                "terrain tickets remain owned while the prepared search is still pending");
        commands.tick();
        check(retained.isEmpty() && releases.get() == 1
                        && completions.size() == 1
                        && completions.getFirst().outcome() == SpawnDestination.Outcome.UNSAFE,
                "terminal production routing releases owned terrain exactly once after completion");
    }

    private static void productionHomeRouteReleasesTerrainAcrossEveryCommandsTerminalExit() {
        class HomeRoute {
            final AtomicInteger releases = new AtomicInteger();
            final AtomicInteger releaseAttempts = new AtomicInteger();
            final Set<Long> retained = new HashSet<>();
            final OmwhCommands.PendingWork<HomeDestination.Result> work;

            HomeRoute(HomeDestination.Outcome resolutionOutcome, int candidateChunks, boolean failFirstRelease) {
                this(resolutionOutcome, candidateChunks, failFirstRelease, false);
            }

            HomeRoute(HomeDestination.Outcome resolutionOutcome, int candidateChunks,
                      boolean failFirstRelease, boolean force) {
                DestinationSafety.ChunkPreparation preparation =
                        DestinationSafety.ChunkPreparation.expandableControlled(new DestinationSafety.TicketAccess() {
                            @Override public void retain(long chunk) { retained.add(chunk); }
                            @Override public Object load(long chunk) { return new Object(); }
                            @Override public void release(long chunk) {
                                int attempt = releaseAttempts.incrementAndGet();
                                if (failFirstRelease && attempt == 1) throw new IllegalStateException("release once");
                                check(retained.remove(chunk), "concrete home release owns the exact ticket");
                                releases.incrementAndGet();
                            }
                        });
                HomeDestination.SavedHome home = new HomeDestination.SavedHome(
                        null, null, null, BlockPos.ZERO, false, null);
                HomeDestination.HomeAccess access = new HomeDestination.HomeAccess() {
                    @Override public HomeDestination.Validation validate() { throw new AssertionError("already validated"); }
                    @Override public HomeDestination.RespawnAuthority currentAuthority(
                            HomeDestination.SavedHome saved) { return saved.authority(); }
                    @Override public HomeDestination.PreparedSavedHome prepare(HomeDestination.SavedHome saved) {
                        return new HomeDestination.PreparedSavedHome(saved);
                    }
                    @Override public List<HomeDestination.TerrainRead> resolutionTerrain(
                            HomeDestination.SavedHome saved) { return List.of(); }
                    @Override public HomeDestination.Resolution resolve(HomeDestination.PreparedSavedHome prepared) {
                        return resolutionOutcome == HomeDestination.Outcome.ACCEPT
                                ? new HomeDestination.Resolution(HomeDestination.Outcome.ACCEPT,
                                new HomeDestination.ResolvedHome(prepared, null, null))
                                : new HomeDestination.Resolution(resolutionOutcome, null);
                    }
                };
                List<HomeDestination.TerrainRead> reads = new ArrayList<>();
                for (int chunk = 0; chunk < candidateChunks; chunk++) {
                    reads.add(new HomeDestination.TerrainRead(chunk * 16, chunk * 16, 0, 0));
                }
                HomeDestination.Pending pending = new HomeDestination.Pending(force, access, home, preparation);
                work = OmwhCommands.pendingHomeRoute(pending);
            }
        }

        java.util.function.Supplier<OmwhCommands> commands = () -> {
            OmwhConfig config = new OmwhConfig();
            return new OmwhCommands(config, new Cooldowns(config, () -> 1_000L));
        };

        OmwhCommands deniedCommands = commands.get();
        HomeRoute denied = new HomeRoute(HomeDestination.Outcome.NO_HOME, 1, false);
        AtomicInteger deniedCompletions = new AtomicInteger();
        check(deniedCommands.enqueuePending(UUID.randomUUID(), deniedCommands.createPendingCoordinator(
                        denied.work, () -> TeleportService.LifecycleStatus.CURRENT,
                        value -> true, value -> true,
                        value -> {
                            check(value.outcome() == HomeDestination.Outcome.NO_HOME,
                                    "invalid vanilla resolution reaches concrete home completion");
                            deniedCompletions.incrementAndGet();
                        }, status -> { throw new AssertionError("current lifecycle"); })),
                "denied concrete home route enqueued");
        deniedCommands.tick();
        deniedCommands.tick();
        check(deniedCompletions.get() == 1 && denied.releases.get() == 1 && denied.retained.isEmpty(),
                "denied/invalid /home releases retained terrain exactly once");

        java.util.function.BiConsumer<OmwhCommands, UUID> cancellation = OmwhCommands::cancelPending;
        for (var cancel : List.of(cancellation)) {
            OmwhCommands owner = commands.get();
            HomeRoute pendingHome = new HomeRoute(HomeDestination.Outcome.ACCEPT, 3, false);
            UUID player = UUID.randomUUID();
            check(owner.enqueuePending(player, owner.createPendingCoordinator(
                            pendingHome.work, () -> TeleportService.LifecycleStatus.CURRENT,
                            value -> true, value -> true,
                            value -> { throw new AssertionError("cancelled home route cannot complete"); },
                            status -> { throw new AssertionError("current lifecycle"); })),
                    "concrete pending home cancellation enqueued");
            owner.tick();
            check(pendingHome.retained.size() == 1,
                    "one concrete home chunk is retained before lifecycle cancellation");
            cancel.accept(owner, player);
            check(pendingHome.releases.get() == 1 && pendingHome.retained.isEmpty(),
                    "shared cancellation path releases concrete home terrain once");
        }

        OmwhCommands stopped = commands.get();
        HomeRoute stopping = new HomeRoute(HomeDestination.Outcome.ACCEPT, 3, true);
        UUID stoppingPlayer = UUID.randomUUID();
        check(stopped.enqueuePending(stoppingPlayer, stopped.createPendingCoordinator(
                        stopping.work, () -> TeleportService.LifecycleStatus.CURRENT,
                        value -> true, value -> true,
                        value -> { throw new AssertionError("stopped home route cannot complete"); },
                        status -> { throw new AssertionError("current lifecycle"); })),
                "SERVER_STOPPED home route enqueued");
        stopped.tick();
        stopped.clearPending();
        check(stopping.releaseAttempts.get() == 2 && stopping.releases.get() == 1
                        && stopping.retained.isEmpty(),
                "SERVER_STOPPED retries a failed concrete /home release and leaves no ticket");
    }

    private static void pendingHomeRespawnAuthorityChangesCancelBeforeResolutionAndReleaseExactly() {
        Object oldLevel = new Object();
        Object movedLevel = new Object();
        HomeDestination.RespawnAuthority expected = new HomeDestination.RespawnAuthority(
                oldLevel, "overworld", new BlockPos(8, 70, 8), 35.0f, -12.0f, false);
        List<HomeDestination.RespawnAuthority> staleAuthorities = java.util.Arrays.asList(
                null,
                new HomeDestination.RespawnAuthority(
                        oldLevel, "overworld", new BlockPos(40, 80, -4), 35.0f, -12.0f, false),
                new HomeDestination.RespawnAuthority(
                        movedLevel, "nether", new BlockPos(8, 70, 8), 35.0f, -12.0f, false));

        for (HomeDestination.RespawnAuthority stale : staleAuthorities) {
            AtomicReference<HomeDestination.RespawnAuthority> current = new AtomicReference<>(expected);
            AtomicInteger resolutions = new AtomicInteger();
            AtomicInteger completions = new AtomicInteger();
            AtomicInteger feedback = new AtomicInteger();
            AtomicInteger releases = new AtomicInteger();
            Set<Long> retained = new HashSet<>();
            DestinationSafety.ChunkPreparation preparation =
                    DestinationSafety.ChunkPreparation.expandableControlled(new DestinationSafety.TicketAccess() {
                        @Override public void retain(long chunk) { retained.add(chunk); }
                        @Override public Object load(long chunk) { return new Object(); }
                        @Override public void release(long chunk) {
                            check(retained.remove(chunk), "stale home releases the exact retained ticket");
                            releases.incrementAndGet();
                        }
                    });
            HomeDestination.SavedHome home = new HomeDestination.SavedHome(
                    null, null, null, expected.pos(), expected.forced(), expected);
            HomeDestination.HomeAccess access = new HomeDestination.HomeAccess() {
                @Override public HomeDestination.Validation validate() { throw new AssertionError("already validated"); }
                @Override public HomeDestination.RespawnAuthority currentAuthority(
                        HomeDestination.SavedHome saved) {
                    return current.get();
                }
                @Override public HomeDestination.PreparedSavedHome prepare(HomeDestination.SavedHome saved) {
                    return new HomeDestination.PreparedSavedHome(saved);
                }
                @Override public List<HomeDestination.TerrainRead> resolutionTerrain(
                        HomeDestination.SavedHome saved) {
                    return List.of(new HomeDestination.TerrainRead(0, 0, 0, 0),
                            new HomeDestination.TerrainRead(16, 16, 0, 0));
                }
                @Override public HomeDestination.Resolution resolve(HomeDestination.PreparedSavedHome prepared) {
                    resolutions.incrementAndGet();
                    return new HomeDestination.Resolution(HomeDestination.Outcome.ACCEPT,
                            new HomeDestination.ResolvedHome(prepared, null, null));
                }
            };
            HomeDestination.Pending pending = new HomeDestination.Pending(false, access, home, preparation);
            OmwhConfig config = new OmwhConfig();
            OmwhCommands commands = new OmwhCommands(config, new Cooldowns(config, () -> 1_000L));
            UUID player = UUID.randomUUID();
            check(commands.enqueuePending(player, commands.createPendingCoordinator(
                            OmwhCommands.pendingHomeRoute(pending),
                            () -> TeleportService.LifecycleStatus.CURRENT,
                            value -> true,
                            value -> {
                                boolean authoritative = pending.authorityCurrent();
                                if (!authoritative) feedback.incrementAndGet();
                                return authoritative;
                            },
                            value -> completions.incrementAndGet(),
                            status -> { throw new AssertionError("entity lifecycle remains current"); })),
                    "stale-authority home route enqueued");

            commands.tick();
            check(retained.size() == 1 && releases.get() == 0,
                    "first route visit retains one exact home-resolution chunk");
            current.set(stale);
            commands.tick();
            check(resolutions.get() == 0 && completions.get() == 0,
                    "cleared, moved, and cross-dimension respawn changes prevent resolution and completion");
            check(feedback.get() == 1 && releases.get() == 1 && retained.isEmpty(),
                    "stale respawn cancellation gives one no-home retry signal and releases exactly once");
        }
    }

    private static void pendingGenerationFailureRetiresAndReleasesExactlyOnce() {
        AtomicInteger failures = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        OmwhCommands.PendingWork<Void> guarded = OmwhCommands.guardPending(new OmwhCommands.PendingWork<Void>() {
            @Override public OmwhCommands.PendingStep<Void> step(int candidateBudget, int worldWorkBudget) {
                throw new IllegalStateException("generation failed");
            }
            @Override public void close() { closes.incrementAndGet(); }
        }, failure -> failures.incrementAndGet());

        OmwhCommands.PendingSearches<String, Void> pending = new OmwhCommands.PendingSearches<>();
        check(pending.add("generation", guarded), "guarded production failure path added");
        OmwhCommands.PendingTick used = pending.tick(1, 11, ignored -> { });
        check(used.itemsCompleted() == 1 && used.candidatesUsed() == 1 && used.worldWorkUsed() == 11,
                "generation failure retires while conservatively charging its complete assigned slice");
        check(failures.get() == 1 && closes.get() == 1 && pending.size() == 0,
                "generation failure reports once, releases tickets once, and leaves no pending retry");
    }

    private static void pendingSpawnSchedulingSharesOneFairServerWideBudget() {
        OmwhCommands.PendingSearches<Integer, Integer> pending = new OmwhCommands.PendingSearches<>();
        int players = 100;
        int[] progress = new int[players];
        for (int player = 0; player < players; player++) {
            int id = player;
            check(pending.add(id, (candidateBudget, worldBudget) -> {
                check(candidateBudget > 0 && worldBudget > 0, "scheduler supplies positive slices");
                progress[id]++;
                return OmwhCommands.PendingStep.pending(1, 1);
            }), "fair-search fixture added");
        }

        OmwhCommands.PendingTick first = pending.tick(OmwhCommands.SEARCH_CANDIDATES_PER_TICK,
                OmwhCommands.SEARCH_WORLD_WORK_PER_TICK, ignored -> { });
        check(first.candidatesUsed() <= OmwhCommands.SEARCH_CANDIDATES_PER_TICK
                        && first.worldWorkUsed() <= OmwhCommands.SEARCH_WORLD_WORK_PER_TICK,
                "all players share one aggregate server-wide allowance");
        for (int player = 0; player < players; player++) {
            check(progress[player] == 1,
                    "each pending request receives exactly one scheduler visit in a server tick");
        }

        int[] before = progress.clone();
        OmwhCommands.PendingTick second = pending.tick(50, 50, ignored -> { });
        check(second.candidatesUsed() == 50 && second.worldWorkUsed() == 50,
                "smaller aggregate allowance is consumed exactly once across the queue");
        int advanced = 0;
        for (int player = 0; player < players; player++) if (progress[player] > before[player]) advanced++;
        check(advanced == 50, "round-robin advances distinct players before returning to the front");
    }

    private static void preparationProgressYieldsAfterOneQuantumPerSchedulerTick() {
        OmwhCommands.PendingSearches<String, Void> pending = new OmwhCommands.PendingSearches<>();
        AtomicInteger firstVisits = new AtomicInteger();
        AtomicInteger secondVisits = new AtomicInteger();
        check(pending.add("first", (candidateBudget, worldBudget) -> {
                    firstVisits.incrementAndGet();
                    return OmwhCommands.PendingStep.pending(0, SpawnDestination.PREPARATION_CHUNKS_PER_VISIT);
                }) && pending.add("second", (candidateBudget, worldBudget) -> {
                    secondVisits.incrementAndGet();
                    return OmwhCommands.PendingStep.pending(0, SpawnDestination.PREPARATION_CHUNKS_PER_VISIT);
                }), "preparing routes added to the production scheduler");

        OmwhCommands.PendingTick used = pending.tick(OmwhCommands.SEARCH_CANDIDATES_PER_TICK, 100, ignored -> { });
        check(firstVisits.get() == 1 && secondVisits.get() == 1,
                "each preparing route receives one fixed chunk quantum per server tick");
        check(used.candidatesUsed() == 0
                        && used.worldWorkUsed() == 2 * SpawnDestination.PREPARATION_CHUNKS_PER_VISIT,
                "preparation is charged once to aggregate world work without pretending chunks are candidates");
    }

    private static void productionPendingSchedulerSlicesRevalidationAndRetiresStaleWork() {
        Object chunk = new Object();
        DestinationSafety.ChunkResidency resident = testResidencyValues(
                -16, 16, -16, 16, ignored -> chunk);
        java.util.function.Function<BlockPos, net.minecraft.world.level.block.state.BlockState> safeStates =
                position -> position.getY() == -1 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
        SpawnDestination.Search search = new SpawnDestination.Search(
                SpawnDestination.offsets(0).iterator(),
                DestinationsTest.spawnProbe(
                        BlockPos.ZERO, 14, 16, resident, safeStates), false);
        SpawnDestination.Pending route = new SpawnDestination.Pending(
                new SpawnDestination.DirectSearchStage(search),
                new SpawnDestination.DirectFinalStage(
                        feet -> DestinationsTest.spawnProbe(feet, 14, 16, resident, safeStates)),
                null, BlockPos.ZERO, BlockPos.ZERO, 14, 0, 0);

        OmwhCommands.PendingSearches<String, SpawnDestination.Result> pending = new OmwhCommands.PendingSearches<>();
        for (int stale = 0; stale < 3; stale++) {
            check(pending.add("stale-" + stale, (candidateBudget, worldBudget) ->
                    OmwhCommands.PendingStep.complete(new SpawnDestination.Result(
                            SpawnDestination.Outcome.UNSAFE, null), 0, 0)), "stale completion added");
        }
        check(pending.add("valid", (candidateBudget, worldBudget) -> {
            SpawnDestination.Tick used = route.tick(candidateBudget, worldBudget);
            return route.complete()
                    ? OmwhCommands.PendingStep.complete(route.result(), used.candidatesStarted(), used.worldWork())
                    : OmwhCommands.PendingStep.pending(used.candidatesStarted(), used.worldWork());
        }), "production pending route added behind stale completions");

        List<SpawnDestination.Result> completions = new ArrayList<>();

        long totalWorldWork = 0;
        int schedulerTicks = 0;
        OmwhCommands.PendingTick first = null;
        while (pending.size() > 0) {
            OmwhCommands.PendingTick used = pending.tick(OmwhCommands.SEARCH_CANDIDATES_PER_TICK,
                    OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK, completions::add);
            if (first == null) first = used;
            check(used.candidatesUsed() <= OmwhCommands.SEARCH_CANDIDATES_PER_TICK
                            && used.worldWorkUsed() <= OmwhCommands.SEARCH_WORLD_WORK_PER_TICK,
                    "actual PendingSearches layer bounds aggregate production work every tick");
            totalWorldWork += used.worldWorkUsed();
            schedulerTicks++;
            check(schedulerTicks < 30, "search and maximum revalidation complete over bounded slices");
        }

        check(first != null && first.itemsCompleted() >= 3 && first.worldWorkUsed() > 0,
                "multiple zero-work stale completions retire before later valid work advances in the same tick");
        long revalidationWorldWork = totalWorldWork - 46_372;
        check(revalidationWorldWork == 46_372,
                "maximum live revalidation charges safety work without inventing a second chunk capture");
        check(totalWorldWork == 92_744,
                "search and fresh revalidation share production accounting over retained prepared chunks");
        check(completions.size() == 4
                        && completions.getLast().outcome() == SpawnDestination.Outcome.ACCEPT
                        && completions.getLast().destinationPrepared(),
                "stale and accepted lifecycle completions are each delivered exactly once");
        check(schedulerTicks > 1,
                "maximum production search plus live revalidation remains genuinely resumable");

        System.out.printf("Pending scheduler ticks=%d totalWorldWork=%d revalidationWorldWork=%d completions=%d%n",
                schedulerTicks, totalWorldWork, revalidationWorldWork, completions.size());
    }

    private static void commandsTickPassesASeparatePendingAdvancementSlice() {
        OmwhConfig config = new OmwhConfig();
        OmwhCommands commands = new OmwhCommands(config, new Cooldowns(config, () -> 1_000L));
        AtomicInteger suppliedWork = new AtomicInteger();
        check(commands.enqueuePending(UUID.randomUUID(), (candidateBudget, worldBudget) -> {
                    suppliedWork.set(worldBudget);
                    return OmwhCommands.PendingStep.pending(1, worldBudget);
                }), "pending advancement probe enqueued");
        commands.tick();
        check(suppliedWork.get() == OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK,
                "OmwhCommands.tick passes the separately derived pending advancement slice");
        check(OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK < OmwhCommands.SEARCH_WORLD_WORK_PER_TICK,
                "pending advancement is strictly smaller than the aggregate immediate-plus-pending allowance");
        check(OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK
                        == TeleportService.LIFECYCLE_VALIDATION_WORK
                        + OmwhCommands.PENDING_ROUTE_WORK_SLICE
                        + OmwhCommands.MAX_EFFECT_DISPATCHES
                        + TeleportService.COMPLETION_WORK
                        + OmwhCommands.MAX_PENDING_TICKET_RELEASE_WORK,
                "pending slice reserves lifecycle, route, effects, completion, and ticket cleanup");
    }

    private static void productionCoordinatorOwnsLifecycleFinalGatesAndDispatch() {
        List<String> order = new ArrayList<>();
        OmwhCommands.CoordinatedPending<String> coordinated = new OmwhCommands.CoordinatedPending<>(
                (candidateBudget, worldBudget) -> {
                    order.add("route");
                    return OmwhCommands.PendingStep.complete("accepted", 1, 1);
                }, new OmwhCommands.CoordinatorHooks<>() {
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

        OmwhCommands.PendingStep<Void> first = coordinated.step(1, 1,
                2 + 3 + OmwhCommands.PENDING_ROUTE_MINIMUM_PROGRESS_WORK - 1);
        check(!first.complete() && first.candidatesUsed() == 0 && first.worldWorkUsed() == 0,
                "coordinator waits until lifecycle, atomic route progress, and completion are all reserved");
        OmwhCommands.PendingStep<Void> second = coordinated.step(1, 1,
                2 + 3 + OmwhCommands.PENDING_ROUTE_MINIMUM_PROGRESS_WORK);
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
        OmwhCommands commands = new OmwhCommands(config, cooldowns);
        UUID player = UUID.randomUUID();
        BlockPos acceptedAnchor = new BlockPos(4, 70, -2);
        List<String> events = new ArrayList<>();

        OmwhCommands.PendingWork<Void> accepted = commands.createPendingCoordinator(
                (candidateBudget, worldBudget) -> OmwhCommands.PendingStep.complete("accepted", 1, 1),
                () -> TeleportService.LifecycleStatus.CURRENT,
                value -> OmwhCommands.finalCooldownAdmission(cooldowns, player, config, events::add),
                value -> SpawnDestination.matchesSearchAnchor(acceptedAnchor, acceptedAnchor),
                value -> events.add("complete:" + value),
                status -> events.add("lifecycle:" + status));
        check(commands.enqueuePending(player, accepted), "production-created accepted coordinator enqueued");
        commands.tick();
        check(events.equals(List.of("complete:accepted")),
                "OmwhCommands.tick runs cooldown admission, anchor comparison, and completion dispatch");

        cooldowns.recordRegular(player);
        events.clear();
        check(commands.enqueuePending(player, commands.createPendingCoordinator(
                (candidateBudget, worldBudget) -> OmwhCommands.PendingStep.complete("blocked", 1, 1),
                () -> TeleportService.LifecycleStatus.CURRENT,
                value -> OmwhCommands.finalCooldownAdmission(cooldowns, player, config, events::add),
                value -> true, value -> events.add("complete:" + value),
                status -> events.add("lifecycle:" + status))), "cooldown coordinator enqueued");
        commands.tick();
        check(events.size() == 1 && !events.getFirst().startsWith("complete:"),
                "final cooldown denial sends feedback and suppresses completion");

        events.clear();
        check(commands.enqueuePending(player, commands.createPendingCoordinator(
                (candidateBudget, worldBudget) -> OmwhCommands.PendingStep.pending(1, 1),
                () -> TeleportService.LifecycleStatus.TOO_LARGE,
                value -> true, value -> true, value -> events.add("complete"),
                status -> events.add(status == TeleportService.LifecycleStatus.TOO_LARGE
                        ? config.passengerTreeTooLargeMessage : "stale"))),
                "oversized lifecycle coordinator enqueued");
        commands.tick();
        check(events.equals(List.of(config.passengerTreeTooLargeMessage)),
                "oversized pending lifecycle retires with explicit passenger-cap feedback");

        AtomicInteger ticketReleases = new AtomicInteger();
        OmwhCommands.PendingWork<String> retainedRoute = new OmwhCommands.PendingWork<>() {
            @Override public OmwhCommands.PendingStep<String> step(int candidateBudget, int worldWorkBudget) {
                return OmwhCommands.PendingStep.pending(1, 1);
            }
            @Override public void close() { ticketReleases.incrementAndGet(); }
        };
        UUID stalePlayer = UUID.randomUUID();
        check(commands.enqueuePending(stalePlayer, commands.createPendingCoordinator(
                        retainedRoute,
                        () -> TeleportService.LifecycleStatus.STALE,
                        value -> true, value -> true, value -> events.add("complete"),
                        status -> { })),
                "ticket-owning production coordinator enqueued");
        commands.tick();
        check(ticketReleases.get() == 1,
                "production lifecycle rejection closes its route and releases temporary tickets");
    }

    private static void admissionAndLifecycleWorkShareHardAggregateAllowances() {
        int admissionLimit = SpawnDestination.PENDING_START_WORK
                + TeleportService.LIFECYCLE_CAPTURE_WORK;
        OmwhCommands.TickWorkAllowance admission = new OmwhCommands.TickWorkAllowance(admissionLimit);
        check(admission.claim(SpawnDestination.PENDING_START_WORK)
                        && admission.claim(TeleportService.LIFECYCLE_CAPTURE_WORK),
                "one pending-plan start and one maximum valid lifecycle capture fit the aggregate allowance");
        check(!admission.claim(1) && admission.remaining() == 0,
                "another synchronous probe cannot exceed the per-tick aggregate allowance");
        admission.reset();
        check(admission.remaining() == admissionLimit,
                "tick boundary restores exactly the mechanically derived allowance");

        OmwhCommands.PendingSearches<Integer, Void> pending = new OmwhCommands.PendingSearches<>();
        AtomicLong lifecycleChecks = new AtomicLong();
        AtomicLong routeSteps = new AtomicLong();
        int crowdedRequests = 1_000;
        for (int request = 0; request < crowdedRequests; request++) {
            OmwhCommands.CoordinatedPending<Void> coordinated = new OmwhCommands.CoordinatedPending<>(
                    (candidateBudget, worldBudget) -> {
                        routeSteps.incrementAndGet();
                        return OmwhCommands.PendingStep.pending(1, 1);
                    }, new OmwhCommands.CoordinatorHooks<>() {
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
        OmwhCommands.PendingTick used = pending.tick(OmwhCommands.SEARCH_CANDIDATES_PER_TICK,
                OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK, ignored -> { });
        check(used.worldWorkUsed() <= OmwhCommands.SEARCH_WORLD_WORK_PER_TICK,
                "crowded lifecycle traversal cannot exceed aggregate per-tick work");
        check(lifecycleChecks.get() >= routeSteps.get()
                        && lifecycleChecks.get() - routeSteps.get() <= 1
                        && routeSteps.get() > 0 && lifecycleChecks.get() < crowdedRequests,
                "lifecycle accounting bounds a crowded queue and preserves the next round-robin position");
        long firstTickRoutes = routeSteps.get();
        pending.tick(OmwhCommands.SEARCH_CANDIDATES_PER_TICK,
                OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK, ignored -> { });
        check(routeSteps.get() > firstTickRoutes && routeSteps.get() < crowdedRequests,
                "the next crowded slice advances waiting requests before returning to the front");
    }

    private static void zeroProgressKeepsTheBlockedRequestNext() {
        OmwhCommands.PendingSearches<Integer, Void> pending = new OmwhCommands.PendingSearches<>();
        List<Integer> progressOrder = new ArrayList<>();
        for (int request = 0; request < 4; request++) {
            int id = request;
            OmwhCommands.CoordinatedPending<Void> coordinated = new OmwhCommands.CoordinatedPending<>(
                    (candidateBudget, worldBudget) -> {
                        progressOrder.add(id);
                        return OmwhCommands.PendingStep.pending(1, worldBudget);
                    }, new OmwhCommands.CoordinatorHooks<>() {
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
            pending.tick(OmwhCommands.SEARCH_CANDIDATES_PER_TICK,
                    OmwhCommands.PENDING_ADVANCEMENT_WORK_PER_TICK, ignored -> { });
        }
        check(progressOrder.subList(0, 4).equals(List.of(0, 1, 2, 3)),
                "a blocked maximum-work request stays next instead of starving behind alternating peers");
    }

    private static void pendingCommandAdmissionCancelsOnlyCompetingTeleports() {
        check(OmwhCommands.pendingSpawnAction(true, false) == OmwhCommands.PendingSpawnAction.REFUSE,
                "duplicate normal /spawn remains refused");
        check(OmwhCommands.pendingSpawnAction(true, true) == OmwhCommands.PendingSpawnAction.CANCEL_AND_CONTINUE,
                "/spawn force cancels the stale normal search and uses ordinary force admission");
        check(OmwhCommands.pendingSpawnAction(false, false) == OmwhCommands.PendingSpawnAction.CONTINUE,
                "a fresh normal /spawn proceeds");
    }

    private static DestinationSafety.ChunkResidency testResidencyValues(
            int minBlockX, int maxBlockX, int minBlockZ, int maxBlockZ, LongFunction<?> values) {
        int minChunkX = Math.floorDiv(minBlockX, 16);
        int maxChunkX = Math.floorDiv(maxBlockX, 16);
        int minChunkZ = Math.floorDiv(minBlockZ, 16);
        int maxChunkZ = Math.floorDiv(maxBlockZ, 16);
        int capacity = (maxChunkX - minChunkX + 1) * (maxChunkZ - minChunkZ + 1);
        long[] keys = new long[capacity];
        Object[] chunks = new Object[capacity];
        int count = 0;
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                long key = ((long) x << 32) | (z & 0xffffffffL);
                Object chunk = values.apply(key);
                if (chunk == null) continue;
                keys[count] = key;
                chunks[count] = chunk;
                count++;
            }
        }
        int captured = count;
        return new DestinationSafety.ChunkResidency(keys, chunks, () -> captured);
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
