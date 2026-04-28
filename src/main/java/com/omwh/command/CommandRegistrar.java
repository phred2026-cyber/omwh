package com.omwh.command;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

public class CommandRegistrar {
  private static boolean registered = false;

  public static void init() {
      if (registered) return;
      CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
          HomeCommand.register(dispatcher);
          SpawnCommand.register(dispatcher);
      });
      registered = true;
  }
}

