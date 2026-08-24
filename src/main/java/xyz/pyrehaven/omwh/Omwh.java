package xyz.pyrehaven.omwh;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Omwh implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");

    @Override
    public void onInitialize() {
        OmwhConfig config = OmwhConfig.load();
        Cooldowns cooldowns = new Cooldowns(config);
        OmwhCommands commands = new OmwhCommands(config, cooldowns);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                commands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                cooldowns.recordJoin(handler.player.getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            cooldowns.remove(handler.player.getUUID());
            commands.cancelPending(handler.player.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                commands.cancelPending(oldPlayer.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server -> commands.tick());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> commands.clearPending());
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damage, blocked) -> {
            if (entity instanceof ServerPlayer victim) {
                cooldowns.afterDamage(victim.getUUID(), playerAttacker(source), damage, blocked);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof ServerPlayer victim) {
                cooldowns.afterDeath(victim.getUUID(), playerAttacker(source));
            }
        });

        LOGGER.info("OMWH commands ready: /{}, /{}", config.homeCommand, config.spawnCommand);
    }

    private static java.util.UUID playerAttacker(net.minecraft.world.damagesource.DamageSource source) {
        return source.getEntity() instanceof ServerPlayer attacker ? attacker.getUUID() : null;
    }
}
