package io.ruin.model.entity.npc.actions.edgeville;

import io.ruin.cache.ItemDef;
import io.ruin.cache.NPCDef;
import io.ruin.model.entity.npc.NPCAction;
import io.ruin.model.entity.player.Player;
import io.ruin.model.entity.shared.listeners.SpawnListener;
import io.ruin.model.inter.dialogue.*;
import io.ruin.model.inter.utils.Config;
import io.ruin.model.inter.utils.Option;
import io.ruin.model.item.Item;
import io.ruin.model.item.actions.ItemNPCAction;
import io.ruin.model.map.Bounds;
import io.ruin.model.shop.Shop;
import io.ruin.model.shop.ShopItem;
import io.ruin.model.shop.ShopManager;

import static io.ruin.cache.ItemID.*;

public class EmblemTrader {

    static {
        //Wilderness Emblem Trader
        NPCAction.register(7941, "talk-to", (player, npc) -> {
            String currencyName = "coins";
            player.dialogue(new NPCDialogue(npc, "If you find an ancient emblem, totem, or statuette, use it on me in revenant caves and I'll exchange it for " + currencyName + "."));
        });
        int[][] ancientArtifacts = {
                {21807, 500_000},   //Emblem
                {21810, 1_000_000},  //Totem
                {21813, 2_000_000},  //Statuette
                {22299, 4_000_000},  //Medallion
                {22302, 8_000_000},  //Effigy
                {22305, 10_000_000}  //Relic
        };
        for (int[] artifact : ancientArtifacts) {
            int id = artifact[0];
            ItemDef.get(id).sigmundBuyPrice = artifact[1];
            ItemNPCAction.register(id, 7941, (player, item, npc) -> player.dialogue(new YesNoDialogue("Are you sure you want to do this?", "Sell your " + ItemDef.get(id).name +
                    " for " + artifact[1] + " coins?", item, () -> {
                item.remove();
                player.getInventory().add(COINS_995, artifact[1]);
                player.dialogue(new NPCDialogue(npc, "Excellent find, " + player.getName() + "! If you find anymore artifacts you know where to find me!"));
            })));
        }
        //Edgeville Emblem Trader
        NPCAction.register(315, "talk-to", (player, npc) -> {
            String currencyName = "coins";
            player.dialogue(new NPCDialogue(npc, "If you find an ancient emblem, totem, or statuette, use it on me in revenant caves and I'll exchange it for " + currencyName + "."));
        });
        NPCAction.register(315, "set-skull", (player, npc) -> skull(player));
        NPCAction.register(315, "reset-kdr", (player, npc) -> player.dialogue(
                new MessageDialogue("<col=ff0000>Warning:</col> You are about to reset your kills & deaths. All " +
                        "statistics related to kills will also be reset. Are you sure you want to continue?").lineHeight(25),
                new OptionsDialogue(
                        new Option("Yes", () -> {
                            Config.PVP_KILLS.set(player, 0);
                            Config.PVP_DEATHS.set(player, 0);
                            player.currentKillSpree = 0;
                            player.highestKillSpree = 0;
                            player.highestShutdown = 0;
                            player.dialogue(new NPCDialogue(npc, "Your kills, deaths, sprees and highest shutdown has been reset."));
                        }),
                        new Option("No")
                )
        ));
        SpawnListener.forEach(npc -> {
            if (npc.getId() == 315 && npc.walkBounds != null)
                npc.walkBounds = new Bounds(3099, 3518, 3092, 3516, 0);
        });


    }


    public static final String SHOP_UUID = "";//TODO fill this out
    public static int getResellPrices(Item itemSelling) {
        if (itemSelling.getDef().id == 21034 || itemSelling.getDef().id == 21079)
            return 0;
        Shop shop = ShopManager.getByUUID(SHOP_UUID);
        if(shop == null)
            return 0;
        for (ShopItem item : shop.defaultStock) {
            int price;
            ItemDef def = item.getDef();
            if (def.id == itemSelling.getDef().id) {
                price =+ item.price;
                return price / 4;
            }
        }
        return 0;
    }

    public static void skull(Player player) {
        player.dialogue(new OptionsDialogue(
                new Option("<img=46> Regular <img=46>", () -> player.getCombat().skullNormal()),
                new Option("<img=93> High-Risk <img=93>", () -> player.dialogue(new OptionsDialogue("This skull will prevent you from using the Protect Item prayer.",
                        new Option("Give me the high risk skull!", () -> player.getCombat().skullHighRisk()),
                        new Option("No, I want to use the Protect Item prayer.")))),
                new Option("No Skull", () -> player.getCombat().resetSkull())
        ));
    }


}
