package com.omwh.utils;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;

public class MessageUtils {
   public void sendMessage(ServerPlayer player, String message) {
       player.sendSystemMessage(Component.literal(formatMessage(message)));
   }

   public void sendMessageWithPlaceholders(ServerPlayer player, String message, String... placeholders) {
       if (placeholders.length % 2 != 0) throw new IllegalArgumentException("Placeholders must be provided in pairs (key, value)");
       String formatted = message;
       for (int i = 0; i < placeholders.length; i += 2) {
           formatted = formatted.replace(placeholders[i], placeholders[i + 1]);
       }
       sendMessage(player, formatted);
   }

   private String formatMessage(String message) {
       return message.replace("&", "§");
   }
}

