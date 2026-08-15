package xyz.pyrehaven.omwh;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BooleanSupplier;

public final class Commands {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    private static final String INTERNAL_ERROR = "§cInternal error executing /%s. Check server log.";
    private static final String VEHICLE_TOO_LARGE = "§cYour vehicle is too big. Please dismount and try again.";
    private final OmwhConfig config;
    private final Cooldowns cooldowns;

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
            home.then(net.minecraft.commands.Commands.literal("--force")
                    .executes(context -> executeHome(context.getSource().getPlayer(), true)));
            spawn.then(net.minecraft.commands.Commands.literal("--force")
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
        return format(message.replace("{time}", Integer.toString(blocking.remainingSeconds())));
    }

    static String passengerMessage(String playerName, boolean home) {
        return "§e" + playerName + " teleported you with their vehicle to "
                + (home ? "their home" : "spawn") + ".";
    }

    private int executeHome(ServerPlayer player, boolean force) {
        if (player == null) return 0;
        try {
            if (!admit(player)) return 0;
            HomeDestination.Result destination = HomeDestination.find(player, force);
            switch (destination.outcome()) {
                case NO_HOME -> send(player, config.noHomepointMessage);
                case CROSS_DIMENSION -> send(player, config.crossDimensionMessage);
                case VEHICLE_TOO_LARGE -> send(player, VEHICLE_TOO_LARGE);
                case UNSAFE -> send(player, config.unsafeHomeMessage);
                case ACCEPT -> { return teleport(player, destination.destination(), true); }
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected /home failure for {}", player.getGameProfile().name(), failure);
            send(player, INTERNAL_ERROR.formatted(config.homeCommand));
        }
        return 0;
    }

    private int executeSpawn(ServerPlayer player, boolean force) {
        if (player == null) return 0;
        try {
            if (!admit(player)) return 0;
            if (!(player.level() instanceof ServerLevel level)) {
                send(player, "§cCannot determine your current world.");
                return 0;
            }
            SpawnDestination.Result destination = SpawnDestination.find(player, level, force);
            switch (destination.outcome()) {
                case NO_WORLD_SPAWN -> send(player, "§cCannot determine world spawn.");
                case VEHICLE_TOO_LARGE -> send(player, VEHICLE_TOO_LARGE);
                case UNSAFE -> send(player, config.unsafeSpawnMessage);
                case ACCEPT -> { return teleport(player, destination.destination(), false); }
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Unexpected /spawn failure for {}", player.getGameProfile().name(), failure);
            send(player, INTERNAL_ERROR.formatted(config.spawnCommand));
        }
        return 0;
    }

    private boolean admit(ServerPlayer player) {
        Cooldowns.Blocking blocking = cooldowns.blocking(player.getUUID());
        if (blocking.type() == Cooldowns.Type.NONE) return true;
        send(player, cooldownMessage(config, blocking));
        return false;
    }

    private int teleport(ServerPlayer player, DestinationSafety.Prepared destination, boolean home) {
        TeleportService.Result[] result = new TeleportService.Result[1];
        return completeTeleport(
                () -> playEffects(player),
                () -> { if (!home) DestinationSafety.loadDestinationChunks(
                        destination.level(), destination.position()); },
                () -> {
                    result[0] = TeleportService.teleport(player, destination);
                    return result[0].success();
                },
                () -> send(player, home ? config.unsafeHomeMessage : config.unsafeSpawnMessage),
                () -> {
                    cooldowns.recordRegular(player.getUUID());
                    send(player, home ? config.homeSuccessMessage : config.spawnSuccessMessage);
                    String passengerMessage = passengerMessage(player.getName().getString(), home);
                    for (ServerPlayer passenger : result[0].passengerPlayers()) send(passenger, passengerMessage);
                });
    }

    static int completeTeleport(Runnable effects, Runnable chunkPreparation, BooleanSupplier teleport,
                                Runnable failureFeedback, Runnable completion) {
        effects.run();
        chunkPreparation.run();
        if (!teleport.getAsBoolean()) {
            failureFeedback.run();
            return 0;
        }
        completion.run();
        return 1;
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

    private static String format(String message) {
        return message.replace('&', '§');
    }
}
