package com.omwh.config;

public final class OmwhConfig {
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

    public String homeSuccessMessage = "§aTeleported to your home!";
    public String spawnSuccessMessage = "§aTeleported to world spawn!";
    public String noHomepointMessage = "§cYou don't have a spawn point set!";
    public String crossDimensionMessage = "§cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!";
    public String unsafeHomeMessage = "§cThere is no safe spot at your home to bring you to.";
    public String unsafeSpawnMessage = "§cCannot find a safe spawn location - please contact an administrator!";
    public String pvpCooldownMessage = "§cYou were recently in combat! Please wait {time} seconds before teleporting.";
    public String damageCooldownMessage = "§cYou recently took damage! Please wait {time} seconds before teleporting.";
    public String joinCooldownMessage = "§cYou must wait {time} seconds after joining before teleporting!";
    public String regularCooldownMessage = "§cYou recently teleported! Please wait {time} seconds before trying again.";

    public void validate() {
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
            throw new IllegalArgumentException(field + " must be a nonblank single Brigadier literal name");
        }
    }

    private static void requireMessage(String value, String field) {
        if (value == null) throw new IllegalArgumentException(field + " must not be null");
    }
}
