package xyz.pyrehaven.omwh;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.Bootstrap;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class CommandsAndCooldownsTest {
    public static void main(String[] args) {
        cooldownPolicyUsesUuidExpiryAndPriority();
        forceSyntaxFollowsTheServerSetting();
        commandFeedbackUsesSpecificMessagesColorsAndPassengerDestination();
        disabledSpawnFeedbackDoesNotClaimAWorldIsMissing();
        acceptedCommandOperationsRunInReleasedOrder();
        System.out.println("CommandsAndCooldownsTest PASS (5 behavior groups)");
    }

    private static void forceSyntaxFollowsTheServerSetting() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        OmwhConfig enabled = new OmwhConfig();
        CommandDispatcher<CommandSourceStack> enabledDispatcher = new CommandDispatcher<>();
        new Commands(enabled, new Cooldowns(enabled, () -> 0L)).register(enabledDispatcher);
        check(enabledDispatcher.getRoot().getChild("home").getChild("--force") != null,
                "/home --force registered by default");
        check(enabledDispatcher.getRoot().getChild("spawn").getChild("--force") != null,
                "/spawn --force registered by default");

        OmwhConfig disabled = new OmwhConfig();
        disabled.enableForceOverride = false;
        CommandDispatcher<CommandSourceStack> disabledDispatcher = new CommandDispatcher<>();
        new Commands(disabled, new Cooldowns(disabled, () -> 0L)).register(disabledDispatcher);
        check(disabledDispatcher.getRoot().getChild("home").getChild("--force") == null,
                "disabled /home force syntax absent");
        check(disabledDispatcher.getRoot().getChild("spawn").getChild("--force") == null,
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

    private static void commandFeedbackUsesSpecificMessagesColorsAndPassengerDestination() {
        OmwhConfig config = new OmwhConfig();
        check(Commands.cooldownMessage(config, new Cooldowns.Blocking(Cooldowns.Type.PVP, 12))
                .contains("12 seconds"), "cooldown placeholder");
        config.regularCooldownMessage = "&cWait {time}";
        check(Commands.cooldownMessage(config, new Cooldowns.Blocking(Cooldowns.Type.REGULAR, 3))
                .equals("§cWait 3"), "ampersand colors");
        check(Commands.passengerMessage("Alex", true).contains("their home"), "home passenger message");
        check(Commands.passengerMessage("Alex", false).endsWith("to spawn."), "spawn passenger message");
    }

    private static void disabledSpawnFeedbackDoesNotClaimAWorldIsMissing() {
        String message = Commands.SPAWN_DISABLED.toLowerCase();
        check(message.contains("disabled"), "disabled spawn has an explicit policy message");
        check(!message.contains("missing") && !message.contains("cannot determine"),
                "disabled spawn is not reported as a missing world");
    }

    private static void acceptedCommandOperationsRunInReleasedOrder() {
        List<String> operations = new ArrayList<>();
        int success = Commands.completeTeleport(
                () -> operations.add("effects"),
                () -> operations.add("chunks"),
                () -> { operations.add("teleport"); return true; },
                () -> operations.add("failure"),
                () -> { operations.add("cooldown"); operations.add("messages"); });
        check(success == 1, "successful command result");
        check(operations.equals(List.of("effects", "chunks", "teleport", "cooldown", "messages")),
                "effect, chunk, teleport, cooldown, and message order");

        operations.clear();
        int failure = Commands.completeTeleport(
                () -> operations.add("effects"),
                () -> operations.add("chunks"),
                () -> { operations.add("teleport"); return false; },
                () -> operations.add("failure"),
                () -> { operations.add("cooldown"); operations.add("messages"); });
        check(failure == 0, "failed command result");
        check(operations.equals(List.of("effects", "chunks", "teleport", "failure")),
                "failed mutation suppresses cooldown and success messages");
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }
}
