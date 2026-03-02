package com.omwh.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public class ConfigManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static OmwhConfig instance = new OmwhConfig();

    /**
     * Returns the current config instance.
     * Always call this instead of caching — config may be reloaded at runtime.
     */
    public static OmwhConfig get() {
        return instance;
    }

    /**
     * Loads config from disk. Creates default config file if it doesn't exist.
     * Call this once at mod init.
     */
    public static void load() {
        Path configPath = FabricLoader.getInstance().getConfigDir().resolve("omwh.json");

        if (!Files.exists(configPath)) {
            LOGGER.info("[OMWH] Config not found — creating default config at {}", configPath);
            instance = new OmwhConfig();
            save(configPath);
            return;
        }

        try (Reader reader = Files.newBufferedReader(configPath)) {
            OmwhConfig loaded = GSON.fromJson(reader, OmwhConfig.class);
            if (loaded != null) {
                instance = loaded;
                LOGGER.info("[OMWH] Config loaded from {}", configPath);
            } else {
                LOGGER.warn("[OMWH] Config file was empty or invalid — using defaults");
                instance = new OmwhConfig();
            }
        } catch (IOException e) {
            LOGGER.error("[OMWH] Failed to read config file — using defaults", e);
            instance = new OmwhConfig();
        }
    }

    private static void save(Path configPath) {
        try {
            Files.createDirectories(configPath.getParent());
            try (Writer writer = Files.newBufferedWriter(configPath)) {
                GSON.toJson(instance, writer);
            }
            LOGGER.info("[OMWH] Default config written to {}", configPath);
        } catch (IOException e) {
            LOGGER.error("[OMWH] Failed to write default config", e);
        }
    }
}
