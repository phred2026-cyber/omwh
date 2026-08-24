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
        Commands commands = new Commands(config, cooldowns);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                commands.register(dispatcher));
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                cooldowns.recordJoin(handler.player.getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            cooldowns.remove(handler.player.getUUID());
            commands.removePending(handler.player.getUUID());
        });
        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) ->
                commands.respawnPending(oldPlayer.getUUID()));
        ServerTickEvents.END_SERVER_TICK.register(server -> commands.tick());
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> commands.clearPending());
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof ServerPlayer victim) {
                if (source.getEntity() instanceof ServerPlayer attacker) {
                    cooldowns.recordIncomingDamageAllowedByOmwh(victim.getUUID(), attacker.getUUID());
                } else {
                    cooldowns.recordIncomingDamageAllowedByOmwh(victim.getUUID(), null);
                }
            }
            return true;
        });

        LOGGER.info("OMWH commands ready: /{}, /{}", config.homeCommand, config.spawnCommand);
    }
}
