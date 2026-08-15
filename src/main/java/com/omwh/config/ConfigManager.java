package com.omwh.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class ConfigManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> REQUIRED_FIELDS = List.of(
            "homeCommand", "spawnCommand",
            "enableRegularCooldown", "regularCooldownSeconds",
            "enablePvpCooldown", "pvpCooldownSeconds",
            "enableDamageCooldown", "damageCooldownSeconds", "joinCooldownSeconds",
            "playTeleportSound", "spawnTeleportParticles",
            "homeSuccessMessage", "spawnSuccessMessage", "noHomepointMessage",
            "crossDimensionMessage", "unsafeHomeMessage", "unsafeSpawnMessage",
            "pvpCooldownMessage", "damageCooldownMessage", "joinCooldownMessage",
            "regularCooldownMessage");

    private ConfigManager() { }

    public static OmwhConfig load() {
        Path path = FabricLoader.getInstance().getConfigDir().resolve("omwh.json");
        if (!Files.exists(path)) {
            OmwhConfig defaults = new OmwhConfig();
            defaults.validate();
            writeDefaults(path, defaults);
            return defaults;
        }

        try (Reader reader = Files.newBufferedReader(path)) {
            return parse(reader);
        } catch (IOException | IllegalArgumentException exception) {
            throw new IllegalStateException("Cannot start OMWH with existing config " + path + ": "
                    + exception.getMessage(), exception);
        }
    }

    static OmwhConfig parse(Reader source) {
        JsonReader reader = new JsonReader(source);
        reader.setStrictness(Strictness.STRICT);
        OmwhConfig config = new OmwhConfig();
        Set<String> seen = new LinkedHashSet<>();
        try {
            requireToken(reader, JsonToken.BEGIN_OBJECT, "config root");
            reader.beginObject();
            while (reader.hasNext()) {
                String field = reader.nextName();
                if (!REQUIRED_FIELDS.contains(field)) {
                    throw new IllegalArgumentException("unknown config key " + field);
                }
                if (!seen.add(field)) {
                    throw new IllegalArgumentException("duplicate config key " + field);
                }
                switch (field) {
                    case "homeCommand" -> config.homeCommand = readString(reader, field);
                    case "spawnCommand" -> config.spawnCommand = readString(reader, field);
                    case "enableRegularCooldown" -> config.enableRegularCooldown = readBoolean(reader, field);
                    case "regularCooldownSeconds" -> config.regularCooldownSeconds = readInt(reader, field);
                    case "enablePvpCooldown" -> config.enablePvpCooldown = readBoolean(reader, field);
                    case "pvpCooldownSeconds" -> config.pvpCooldownSeconds = readInt(reader, field);
                    case "enableDamageCooldown" -> config.enableDamageCooldown = readBoolean(reader, field);
                    case "damageCooldownSeconds" -> config.damageCooldownSeconds = readInt(reader, field);
                    case "joinCooldownSeconds" -> config.joinCooldownSeconds = readInt(reader, field);
                    case "playTeleportSound" -> config.playTeleportSound = readBoolean(reader, field);
                    case "spawnTeleportParticles" -> config.spawnTeleportParticles = readBoolean(reader, field);
                    case "homeSuccessMessage" -> config.homeSuccessMessage = readString(reader, field);
                    case "spawnSuccessMessage" -> config.spawnSuccessMessage = readString(reader, field);
                    case "noHomepointMessage" -> config.noHomepointMessage = readString(reader, field);
                    case "crossDimensionMessage" -> config.crossDimensionMessage = readString(reader, field);
                    case "unsafeHomeMessage" -> config.unsafeHomeMessage = readString(reader, field);
                    case "unsafeSpawnMessage" -> config.unsafeSpawnMessage = readString(reader, field);
                    case "pvpCooldownMessage" -> config.pvpCooldownMessage = readString(reader, field);
                    case "damageCooldownMessage" -> config.damageCooldownMessage = readString(reader, field);
                    case "joinCooldownMessage" -> config.joinCooldownMessage = readString(reader, field);
                    case "regularCooldownMessage" -> config.regularCooldownMessage = readString(reader, field);
                    default -> throw new AssertionError("unhandled config key " + field);
                }
            }
            reader.endObject();
            try {
                if (reader.peek() != JsonToken.END_DOCUMENT) {
                    throw new IllegalArgumentException("trailing content after config object");
                }
            } catch (IOException exception) {
                throw new IllegalArgumentException("trailing or malformed content after config object", exception);
            }
        } catch (IOException | IllegalStateException exception) {
            throw new IllegalArgumentException("malformed JSON: " + exception.getMessage(), exception);
        }

        if (seen.size() != REQUIRED_FIELDS.size()) {
            for (String field : REQUIRED_FIELDS) {
                if (!seen.contains(field)) throw new IllegalArgumentException("missing config key " + field);
            }
        }
        config.validate();
        return config;
    }

    private static String readString(JsonReader reader, String field) throws IOException {
        requireToken(reader, JsonToken.STRING, field);
        return reader.nextString();
    }

    private static boolean readBoolean(JsonReader reader, String field) throws IOException {
        requireToken(reader, JsonToken.BOOLEAN, field);
        return reader.nextBoolean();
    }

    private static int readInt(JsonReader reader, String field) throws IOException {
        requireToken(reader, JsonToken.NUMBER, field);
        String value = reader.nextString();
        try {
            return new BigDecimal(value).intValueExact();
        } catch (ArithmeticException | NumberFormatException exception) {
            throw new IllegalArgumentException(field + " must be an integral 32-bit number", exception);
        }
    }

    private static void requireToken(JsonReader reader, JsonToken expected, String field) throws IOException {
        JsonToken actual = reader.peek();
        if (actual != expected) {
            throw new IllegalArgumentException(field + " must be " + expected + ", not " + actual);
        }
    }

    private static void writeDefaults(Path path, OmwhConfig defaults) {
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(defaults, writer);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create default OMWH config " + path, exception);
        }
    }
}
