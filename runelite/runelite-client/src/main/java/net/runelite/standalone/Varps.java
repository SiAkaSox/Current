package net.runelite.standalone;

import net.runelite.mapping.ObfuscatedName;
import net.runelite.mapping.ObfuscatedSignature;

@ObfuscatedName("hl")
public class Varps {
   @ObfuscatedName("pb")
   @ObfuscatedSignature(
      signature = "Ljk;"
   )
   static ClanChat clanChat;
   @ObfuscatedName("n")
   public static int[] Varps_temp;
   @ObfuscatedName("v")
   public static int[] Varps_main;
   @ObfuscatedName("z")
   static int[] Varps_masks;

   static {
      Varps_masks = new int[32];
      int var0 = 2;

      for(int var1 = 0; var1 < 32; ++var1) {
         Varps_masks[var1] = var0 - 1;
         var0 += var0;
      }

      // Vanilla is 4000. Must match Varp.COUNT on the server; ids >= 20000 are custom (CustomMisc).
      Varps_temp = new int[10000];
      Varps_main = new int[10000];
   }
}
