package xyz.pyrehaven.omwh;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OmwhConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("omwh");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String[] STRING_FIELDS = {
            "homeCommand", "spawnCommand", "homeSuccessMessage", "spawnSuccessMessage",
            "noHomepointMessage", "crossDimensionMessage", "unsafeHomeMessage", "unsafeSpawnMessage",
            "pvpCooldownMessage", "damageCooldownMessage", "joinCooldownMessage", "regularCooldownMessage"
    };
    private static final String[] BOOLEAN_FIELDS = {
            "enableRegularCooldown", "enablePvpCooldown", "enableDamageCooldown",
            "playTeleportSound", "spawnTeleportParticles", "enableForceOverride",
            "enableCrossDimensionTeleport", "enableOverworldSpawn", "enableNetherSpawn", "enableEndSpawn"
    };
    private static final String[] INTEGER_FIELDS = {
            "regularCooldownSeconds", "pvpCooldownSeconds", "damageCooldownSeconds", "joinCooldownSeconds"
    };

    public String homeCommand = "home";
    public String spawnCommand = "spawn";
    public boolean enableRegularCooldown = true;
    public int regularCooldownSeconds = 30;
    public boolean enablePvpCooldown = true;
    public int pvpCooldownSeconds = 45;
    public boolean enableDamageCooldown = true;
    public int damageCooldownSeconds = 10;
    public int joinCooldownSeconds = 30;
    public boolean playTeleportSound = true;
    public boolean spawnTeleportParticles = true;
    public boolean enableForceOverride = true;
    public boolean enableCrossDimensionTeleport = true;
    public boolean enableOverworldSpawn = true;
    public boolean enableNetherSpawn = true;
    public boolean enableEndSpawn = true;
    public String homeSuccessMessage = "§aTeleported to your home!";
    public String spawnSuccessMessage = "§aTeleported to world spawn!";
    public String noHomepointMessage = "§cYou don't have a spawn point set!";
    public String crossDimensionMessage = "§cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!";
    public String unsafeHomeMessage = "§cIt is not safe to teleport here.";
    public String unsafeSpawnMessage = "§cIt is not safe to teleport here.";
    public String pvpCooldownMessage = "§cYou were recently in combat! Please wait {time} seconds before teleporting.";
    public String damageCooldownMessage = "§cYou recently took damage! Please wait {time} seconds before teleporting.";
    public String joinCooldownMessage = "§cYou must wait {time} seconds after joining before teleporting!";
    public String regularCooldownMessage = "§cYou recently teleported! Please wait {time} seconds before trying again.";

    public static OmwhConfig load() {
        return load(FabricLoader.getInstance().getConfigDir().resolve("omwh.json"));
    }

    static OmwhConfig load(Path path) {
        if (!Files.exists(path)) {
            OmwhConfig defaults = new OmwhConfig();
            defaults.validate();
            try {
                Path parent = path.getParent();
                if (parent != null) Files.createDirectories(parent);
                try (Writer writer = Files.newBufferedWriter(path)) {
                    GSON.toJson(defaults, writer);
                }
            } catch (IOException exception) {
                LOGGER.error("Could not write initial OMWH config {}; using defaults", path, exception);
            }
            return defaults;
        }

        if (!Files.isRegularFile(path)) {
            LOGGER.error("Could not read OMWH config {}; using defaults for this server run", path);
            return new OmwhConfig();
        }
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed.isJsonNull()) {
                LOGGER.error("OMWH config {} is empty; using defaults for this server run", path);
                return new OmwhConfig();
            }
            if (!parsed.isJsonObject()) throw new JsonParseException("config root must be an object");
            JsonObject object = parsed.getAsJsonObject();
            requirePrimitiveTypes(object);
            OmwhConfig loaded = GSON.fromJson(object, OmwhConfig.class);
            loaded.validate();
            return loaded;
        } catch (JsonParseException exception) {
            throw new IllegalStateException("Cannot parse OMWH config " + path, exception);
        } catch (IOException exception) {
            LOGGER.error("Could not read OMWH config {}; using defaults for this server run", path, exception);
            return new OmwhConfig();
        }
    }

    private static void requirePrimitiveTypes(JsonObject object) {
        for (String field : STRING_FIELDS) requireType(object, field, Type.STRING);
        for (String field : BOOLEAN_FIELDS) requireType(object, field, Type.BOOLEAN);
        for (String field : INTEGER_FIELDS) requireType(object, field, Type.INTEGER);
    }

    private static void requireType(JsonObject object, String field, Type type) {
        if (!object.has(field)) return;
        JsonElement value = object.get(field);
        boolean valid = value != null && value.isJsonPrimitive() && switch (type) {
            case STRING -> value.getAsJsonPrimitive().isString();
            case BOOLEAN -> value.getAsJsonPrimitive().isBoolean();
            case INTEGER -> isInteger(value);
        };
        if (!valid) throw new JsonParseException(field + " must be a JSON " + type.label);
    }

    private static boolean isInteger(JsonElement value) {
        if (!value.getAsJsonPrimitive().isNumber()) return false;
        try {
            value.getAsBigDecimal().intValueExact();
            return true;
        } catch (ArithmeticException exception) {
            return false;
        }
    }

    private enum Type {
        STRING("string"), BOOLEAN("boolean"), INTEGER("integer");

        private final String label;

        Type(String label) {
            this.label = label;
        }
    }

    void validate() {
        requireLiteral(homeCommand, "homeCommand");
        requireLiteral(spawnCommand, "spawnCommand");
        if (homeCommand.equals(spawnCommand)) {
            throw new IllegalArgumentException("homeCommand and spawnCommand must be distinct");
        }
        if (regularCooldownSeconds < 0 || pvpCooldownSeconds < 0
                || damageCooldownSeconds < 0 || joinCooldownSeconds < 0) {
            throw new IllegalArgumentException("cooldown durations must be nonnegative");
        }
        requireMessage(homeSuccessMessage, "homeSuccessMessage");
        requireMessage(spawnSuccessMessage, "spawnSuccessMessage");
        requireMessage(noHomepointMessage, "noHomepointMessage");
        requireMessage(crossDimensionMessage, "crossDimensionMessage");
        requireMessage(unsafeHomeMessage, "unsafeHomeMessage");
        requireMessage(unsafeSpawnMessage, "unsafeSpawnMessage");
        requireMessage(pvpCooldownMessage, "pvpCooldownMessage");
        requireMessage(damageCooldownMessage, "damageCooldownMessage");
        requireMessage(joinCooldownMessage, "joinCooldownMessage");
        requireMessage(regularCooldownMessage, "regularCooldownMessage");
    }

    private static void requireLiteral(String value, String field) {
        if (value == null || !value.matches("[0-9A-Za-z_.+\\-]+")) {
            throw new IllegalArgumentException(field + " must be a nonblank Brigadier literal");
        }
    }

    private static void requireMessage(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must not be null");
    }
}
