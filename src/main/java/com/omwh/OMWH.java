package com.omwh;

import com.omwh.command.CommandSupport;
import com.omwh.command.HomeCommand;
import com.omwh.command.SpawnCommand;
import com.omwh.config.ConfigManager;
import com.omwh.config.OmwhConfig;
import com.omwh.utils.CooldownManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OMWH implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");

    @Override
    public void onInitialize() {
        OmwhConfig config = ConfigManager.load();
        CooldownManager cooldowns = new CooldownManager(config);
        CommandSupport commands = new CommandSupport(config, cooldowns);

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            HomeCommand.register(dispatcher, commands);
            SpawnCommand.register(dispatcher, commands);
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                cooldowns.recordJoin(handler.player.getUUID()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                cooldowns.clear(handler.player.getUUID()));
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, base, taken, blocked) -> {
            if (taken > 0 && !blocked) {
                recordAcceptedDamage(entity instanceof ServerPlayer player ? player : null, source, cooldowns);
            }
        });
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) ->
                recordAcceptedDamage(entity instanceof ServerPlayer player ? player : null, source, cooldowns));

        LOGGER.info("OMWH commands ready: /{}, /{}", config.homeCommand, config.spawnCommand);
    }

    private static void recordAcceptedDamage(ServerPlayer victim, DamageSource source,
                                             CooldownManager cooldowns) {
        if (victim == null) return;
        if (source.getEntity() instanceof ServerPlayer attacker) {
            cooldowns.recordPvp(victim.getUUID());
            cooldowns.recordPvp(attacker.getUUID());
        } else {
            cooldowns.recordDamage(victim.getUUID());
        }
    }
}
