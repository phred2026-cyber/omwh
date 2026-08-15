package com.omwh.config;

import java.io.StringReader;

public final class ConfigParserRegressionTest {
    private static final String VALID = """
            {
              "homeCommand": "home",
              "spawnCommand": "spawn",
              "enableRegularCooldown": true,
              "regularCooldownSeconds": 30,
              "enablePvpCooldown": true,
              "pvpCooldownSeconds": 45,
              "enableDamageCooldown": true,
              "damageCooldownSeconds": 10,
              "joinCooldownSeconds": 30,
              "playTeleportSound": true,
              "spawnTeleportParticles": true,
              "homeSuccessMessage": "home ok",
              "spawnSuccessMessage": "spawn ok",
              "noHomepointMessage": "no home",
              "crossDimensionMessage": "wrong world",
              "unsafeHomeMessage": "unsafe home",
              "unsafeSpawnMessage": "unsafe spawn",
              "pvpCooldownMessage": "pvp {time}",
              "damageCooldownMessage": "damage {time}",
              "joinCooldownMessage": "join {time}",
              "regularCooldownMessage": "regular {time}"
            }
            """;

    private ConfigParserRegressionTest() { }

    public static void run() {
        OmwhConfig parsed = ConfigManager.parse(new StringReader(VALID));
        assertEquals("home", parsed.homeCommand, "a complete config must parse");
        assertEquals(45, parsed.pvpCooldownSeconds, "integer fields must retain their value");
        assertEquals(true, parsed.enableDamageCooldown, "boolean fields must retain their value");

        assertRejected("{}", "homeCommand", "an empty object must report a missing key");
        assertRejected(withoutField("joinCooldownSeconds"), "joinCooldownSeconds",
                "one missing key must be rejected by name");
        assertRejected(VALID.replaceFirst("\\{", "{\n  \"surprise\": 1,"), "surprise",
                "unknown keys must be rejected by name");
        assertRejected(VALID.replace("\"homeCommand\": \"home\",",
                        "\"homeCommand\": \"home\",\n  \"homeCommand\": \"again\","),
                "homeCommand", "duplicate keys must be rejected by name");
        assertRejected(VALID.replace("\"regularCooldownSeconds\": 30",
                        "\"regularCooldownSeconds\": \"30\""),
                "regularCooldownSeconds", "quoted integers must be rejected");
        assertRejected(VALID.replace("\"enablePvpCooldown\": true",
                        "\"enablePvpCooldown\": \"true\""),
                "enablePvpCooldown", "quoted booleans must be rejected");
        assertRejected(VALID.replace("\"unsafeHomeMessage\": \"unsafe home\"",
                        "\"unsafeHomeMessage\": null"),
                "unsafeHomeMessage", "null strings must be rejected");
        assertRejected(VALID.replace("\"damageCooldownSeconds\": 10",
                        "\"damageCooldownSeconds\": 10.5"),
                "damageCooldownSeconds", "fractional integers must be rejected");
        assertRejected(VALID.replace("\"joinCooldownSeconds\": 30",
                        "\"joinCooldownSeconds\": 2147483648"),
                "joinCooldownSeconds", "out-of-range integers must be rejected");
        assertRejected(VALID.substring(0, VALID.lastIndexOf('}')), "JSON",
                "truncated JSON must be rejected");
        assertRejected(VALID + " true", "trailing", "trailing JSON content must be rejected");
    }

    private static String withoutField(String field) {
        return VALID.replaceFirst("(?m)^\\s*\\\"" + field + "\\\"[^\\n]*\\n", "");
    }

    private static void assertRejected(String json, String expectedMessage, String reason) {
        try {
            ConfigManager.parse(new StringReader(json));
        } catch (IllegalArgumentException exception) {
            if (exception.getMessage() != null && exception.getMessage().contains(expectedMessage)) return;
            throw new AssertionError(reason + "; error did not name " + expectedMessage + ": " + exception, exception);
        }
        throw new AssertionError(reason + "; parser accepted invalid JSON");
    }

    private static void assertEquals(Object expected, Object actual, String message) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError(message + "; expected=" + expected + ", actual=" + actual);
        }
    }
}
