package xyz.pyrehaven.omwh;

import java.nio.file.Files;
import java.nio.file.Path;

public final class ConfigTest {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("omwh-config-test");
        try {
            missingConfigCreatesCompleteDefaults(root.resolve("missing/omwh.json"));
            existingConfigKeepsDefaultsForOmittedFields(root.resolve("partial.json"));
            emptyAndUnreadableConfigsUseDefaults(root);
            malformedAndCoercedKnownTypesFailStartup(root);
            unknownFieldsRemainPermissive(root.resolve("unknown.json"));
            initialWriteFailureStillUsesDefaults(root);
            validationRejectsInvalidCommandsAndDurations();
            System.out.println("ConfigTest PASS (7 behaviors)");
        } finally {
            deleteTree(root);
        }
    }

    private static void missingConfigCreatesCompleteDefaults(Path path) throws Exception {
        OmwhConfig config = OmwhConfig.load(path);
        check(config.homeCommand.equals("home") && config.spawnCommand.equals("spawn"), "default commands");
        check(config.enableForceOverride, "force override enabled by default");
        check(config.unsafeHomeMessage.equals("§cIt is not safe to teleport here."), "unsafe home default");
        check(config.unsafeSpawnMessage.equals("§cIt is not safe to teleport here."), "unsafe spawn default");
        String json = Files.readString(path);
        check(json.contains("\"regularCooldownSeconds\": 30"), "complete default cooldown");
        check(json.contains("\"enableForceOverride\": true"), "complete default force setting");
        check(json.contains("\"regularCooldownMessage\""), "complete default messages");
    }

    private static void existingConfigKeepsDefaultsForOmittedFields(Path path) throws Exception {
        Files.writeString(path, "{\"homeCommand\":\"return\",\"regularCooldownSeconds\":7}");
        OmwhConfig config = OmwhConfig.load(path);
        check(config.homeCommand.equals("return"), "configured command");
        check(config.regularCooldownSeconds == 7, "configured cooldown");
        check(config.spawnCommand.equals("spawn") && config.pvpCooldownSeconds == 45, "omitted defaults");
    }

    private static void emptyAndUnreadableConfigsUseDefaults(Path root) throws Exception {
        Path empty = root.resolve("empty.json");
        Files.writeString(empty, "");
        check(OmwhConfig.load(empty).regularCooldownSeconds == 30, "empty fallback");
        check(OmwhConfig.load(root).regularCooldownSeconds == 30, "I/O fallback");
    }

    private static void malformedAndCoercedKnownTypesFailStartup(Path root) throws Exception {
        Path malformed = root.resolve("malformed.json");
        Files.writeString(malformed, "{");
        expectFailure(() -> OmwhConfig.load(malformed), "malformed JSON");
        Path incompatible = root.resolve("incompatible.json");
        Files.writeString(incompatible, "{\"regularCooldownSeconds\":\"soon\"}");
        expectFailure(() -> OmwhConfig.load(incompatible), "incompatible type");
        Path quotedNumber = root.resolve("quoted-number.json");
        Files.writeString(quotedNumber, "{\"regularCooldownSeconds\":\"30\"}");
        expectFailure(() -> OmwhConfig.load(quotedNumber), "quoted number");
        Path quotedBoolean = root.resolve("quoted-boolean.json");
        Files.writeString(quotedBoolean, "{\"enableRegularCooldown\":\"false\"}");
        expectFailure(() -> OmwhConfig.load(quotedBoolean), "quoted boolean");
        Path quotedForceBoolean = root.resolve("quoted-force-boolean.json");
        Files.writeString(quotedForceBoolean, "{\"enableForceOverride\":\"false\"}");
        expectFailure(() -> OmwhConfig.load(quotedForceBoolean), "quoted force boolean");
    }

    private static void unknownFieldsRemainPermissive(Path path) throws Exception {
        Files.writeString(path, "{\"futureSetting\":{\"enabled\":true}}");
        check(OmwhConfig.load(path).regularCooldownSeconds == 30, "unknown field ignored");
    }

    private static void initialWriteFailureStillUsesDefaults(Path root) throws Exception {
        Path parentFile = root.resolve("not-a-directory");
        Files.writeString(parentFile, "occupied");
        check(OmwhConfig.load(parentFile.resolve("omwh.json")).homeCommand.equals("home"), "write fallback");
    }

    private static void validationRejectsInvalidCommandsAndDurations() {
        OmwhConfig duplicate = new OmwhConfig();
        duplicate.spawnCommand = "home";
        expectFailure(duplicate::validate, "duplicate commands");
        OmwhConfig negative = new OmwhConfig();
        negative.damageCooldownSeconds = -1;
        expectFailure(negative::validate, "negative duration");
    }

    private static void expectFailure(Runnable action, String behavior) {
        try {
            action.run();
            throw new AssertionError("Expected failure: " + behavior);
        } catch (IllegalStateException | IllegalArgumentException expected) {
            // expected
        }
    }

    private static void check(boolean condition, String behavior) {
        if (!condition) throw new AssertionError(behavior);
    }

    private static void deleteTree(Path root) throws Exception {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) Files.deleteIfExists(path);
        }
    }
}
