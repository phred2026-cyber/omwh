package com.omwh;

import com.omwh.command.CommandRegistrar;
import com.omwh.config.ConfigManager;
import com.omwh.listeners.PlayerListener;
import com.omwh.utils.CooldownManager;
import com.omwh.utils.EffectsManager;
import com.omwh.utils.MessageUtils;
import com.omwh.utils.TeleportVehicles;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OMWH implements ModInitializer {
    private final Logger logger = LoggerFactory.getLogger("omwh");

    public static final CooldownManager COOLDOWN_MANAGER = new CooldownManager();
    public static final EffectsManager EFFECTS_MANAGER = new EffectsManager();
    public static final MessageUtils MESSAGE_UTILS = new MessageUtils();

    @Override
    public void onInitialize() {
        ConfigManager.load();

        logger.info("OMWH Mod is initializing!");

        CommandRegistrar.init();
        TeleportVehicles.ensureTickHook();

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            PlayerListener playerListener = new PlayerListener(COOLDOWN_MANAGER);
            playerListener.registerEvents();
        } else {
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
                try {
                    PlayerListener playerListener = new PlayerListener(COOLDOWN_MANAGER);
                    playerListener.registerEvents();
                    logger.info("OMWH: Registered server listeners in integrated environment");
                } catch (Exception e) {
                    logger.warn("OMWH: Failed to register server listeners in integrated environment", e);
                }
            });
        }

        logger.info("OMWH commands ready: /{}, /{}", ConfigManager.get().homeCommand, ConfigManager.get().spawnCommand);
    }
}
