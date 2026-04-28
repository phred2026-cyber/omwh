package com.omwh.config;

public class OmwhConfig {

  // Command aliases
  public String homeCommand = "home";
  public String spawnCommand = "spawn";

  // Cooldowns (seconds, 0 = disabled)
  public boolean enableRegularCooldown = true;
  public int regularCooldownSeconds = 30;
  public boolean enablePvpCooldown = true;
  public int pvpCooldownSeconds = 45;
  public boolean enableDamageCooldown = true;
  public int damageCooldownSeconds = 10;
  public int joinCooldownSeconds = 30;

  // Teleport effects
  public boolean playTeleportSound = true;
  public boolean spawnTeleportParticles = true;

  // Messages (support § color codes; use {time} as placeholder in cooldown messages)
  public String homeSuccessMessage = "\u00a7aTeleported to your home!";
  public String spawnSuccessMessage = "\u00a7aTeleported to world spawn!";
  public String noHomepointMessage = "\u00a7cYou don't have a spawn point set!";
  public String crossDimensionMessage = "\u00a7cYou are not powerful enough to bend space between dimensions. Use a portal first, then try again!";
  public String unsafeHomeMessage = "\u00a7cThere is no safe spot at your home to bring you to.";
  public String unsafeSpawnMessage = "\u00a7cCannot find a safe spawn location - please contact an administrator!";
  public String pvpCooldownMessage = "\u00a7cYou were recently in combat! Please wait {time} seconds before teleporting.";
  public String damageCooldownMessage = "\u00a7cYou recently took damage! Please wait {time} seconds before teleporting.";
  public String joinCooldownMessage = "\u00a7cYou must wait {time} seconds after joining before teleporting!";
  public String regularCooldownMessage = "\u00a7cYou recently teleported! Please wait {time} seconds before trying again.";
}

