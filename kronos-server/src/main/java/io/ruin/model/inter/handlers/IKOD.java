package io.ruin.model.inter.handlers;

import io.ruin.api.utils.NumberUtils;
import io.ruin.cache.EnumMap;
import io.ruin.cache.ItemDef;
import io.ruin.model.combat.Killer;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.Interface;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainer;
import io.ruin.model.item.actions.impl.ItemBreaking;
import io.ruin.model.item.actions.impl.ItemUpgrading;
import io.ruin.model.item.actions.impl.Pet;
import io.ruin.model.item.actions.impl.chargable.Blowpipe;
import io.ruin.model.item.actions.impl.chargable.CorruptedJavelin;
import io.ruin.model.item.actions.impl.chargable.CorruptedStaff;
import io.ruin.model.item.actions.impl.combine.ItemCombining;
import io.ruin.model.item.attributes.AttributeExtensions;
import io.ruin.model.item.attributes.AttributeTypes;
import io.ruin.model.skills.prayer.Prayer;
import io.ruin.services.Loggers;

import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.ruin.cache.ItemID.BLOOD_MONEY;
import static io.ruin.cache.ItemID.COINS_995;

public class IKOD {

    public static void open(Player player) {
        if(player.isVisibleInterface(Interface.ITEMS_KEPT_ON_DEATH))
            player.closeInterface(InterfaceType.MAIN);
        player.openInterface(InterfaceType.MAIN, Interface.ITEMS_KEPT_ON_DEATH);
        player.getPacketSender().sendAccessMask(Interface.ITEMS_KEPT_ON_DEATH, 18, 0, 4, 2);
        player.getPacketSender().sendAccessMask(Interface.ITEMS_KEPT_ON_DEATH, 21, 0, 42, 2);

        boolean skulled = player.getCombat().isSkulled();
        boolean ultimateIronMan = player.getGameMode().isUltimateIronman(); //todo
        int keepCount = getKeepCount(skulled, ultimateIronMan, player.getPrayer().isActive(Prayer.PROTECT_ITEM));

        ArrayList<Item> items = getItems(player);
        ArrayList<Item> keepItems = new ArrayList<>(keepCount);
        int keepCountRemaining = keepCount;
        long value = 0;
        for(Iterator<Item> it = items.iterator(); it.hasNext(); ) {
            Item item = it.next();
            ItemDef def = item.getDef();
            if(!def.neverProtect && keepCountRemaining > 0) {
                int keepAmount = Math.min(item.getAmount(), keepCountRemaining);
                keepItems.add(new Item(item.getId(), keepAmount));
                keepCountRemaining -= keepAmount;
                item.incrementAmount(-keepAmount);
                if(item.getAmount() == 0) {
                    it.remove();
                    continue;
                }
            }
            value += getValue(item);
        }
        player.getPacketSender().sendItems(-1, 63834, 468, items.toArray(new Item[items.size()]));
        player.getPacketSender().sendItems(-1, 63718, 584, keepItems.toArray(new Item[keepItems.size()]));
        player.getPacketSender().sendClientScript(118, "isii1s", 0, "", keepCount, skulled ? 1 : 0, ultimateIronMan ? 1 : 0, NumberUtils.formatNumber(value) + " " + ("gp"));
    }

    private static final List<Integer> CHARGED_UNTRADEABLES = Arrays.asList(
            12809, 12006, 20655, 20657, 19710,
            12926, 22550, 11283, 21633, 22002,
            12931, 13197, 13199, 22555, 20714,
            12904, 11907, 12899, 22545
    );

    private static boolean allowProtect(Player player, Item item) {  // should this item be allowed to be 'saved'?
        if (item.getDef().neverProtect)
            return false;
        if (!item.getDef().tradeable && item.getDef().breakTo != null)
            return false;
        if (CHARGED_UNTRADEABLES.contains(item.getId()))
            return true;
        if (item.getDef().breakId != 0)
            return true;
        if(item.getDef().combinedFrom != null)
            return true;
        if (item.getDef().upgradedFrom != null) {
            ItemDef broken = ItemDef.get(item.getDef().upgradedFrom.regularId);
            return broken.tradeable;
        }
        return item.getDef().tradeable || player.wildernessLevel > 20;
    }

    /**
     * Players keep their complete inventory and equipment on death.
     * Death must not clear containers, transform items, or send items to a drop consumer.
     * The normal death/respawn flow is handled by the caller.
     */
    public static void forLostItem(Player player, Killer killer, Consumer<Item> dropConsumer) {
        // Intentionally do nothing: all carried items and equipped gear are retained.
    }

    public static int getKeepCount(boolean skulled, boolean ultimateIronman, boolean protectingItem) {
        if(ultimateIronman)
            return 0;
        int keepCount = skulled ? 0 : 3;
        if(protectingItem)
            keepCount++;
        return keepCount;
    }

    public static ArrayList<Item> getItems(Player player) {
        int count = player.getInventory().getCount() + player.getEquipment().getCount();
        ArrayList<Item> list = new ArrayList<>(count);
        if(count > 0) {
            for(Item item : player.getInventory().getItems()) {
                if(item != null)
                    list.add(item.copy());
            }
            for(Item item : player.getEquipment().getItems()) {
                if(item != null)
                    list.add(item.copy());
            }

            list.sort((i1, i2) -> Integer.compare(i2.getDef().protectValue, i1.getDef().protectValue));
        }
        return list;
    }

    private static long getValue(Item item) {
        if(item.getId() == BLOOD_MONEY)
            return item.getAmount();
        if(item.getId() == COINS_995)
            return item.getAmount();
        long price = item.getDef().protectValue;
        if(price <= 0 && ((price = item.getDef().highAlchValue)) <= 0)
            return 0;
        return item.getAmount() * price;
    }

    static {
        EnumMap map = EnumMap.get(879);
        for(int id : map.keys)
            ItemDef.get(id).neverProtect = id != 13190 && id != 13192; //true when not bonds

        /**
         * Custom protect items
         */
        ItemDef.get(12931).protectValue = (int) Math.min(Integer.MAX_VALUE, 20000 * 1000L); // Serpentine helm (charged)
        ItemDef.get(13197).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); // Tanzanite helm (charged)
        ItemDef.get(13199).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); // Magma helm (charged)
        ItemDef.get(12926).protectValue = (int) Math.min(Integer.MAX_VALUE, 20000 * 1000L); //Charged blowpipe (charged)
        ItemDef.get(22550).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); //Craws bow (charged)
        ItemDef.get(22547).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); //Craws bow (uncharged)
        ItemDef.get(22545).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); //Viggora's chainmace (charged)
        ItemDef.get(22542).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); //Viggora's chainmace (uncharged)
        ItemDef.get(22555).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); //Thammaron's sceptre (charged)
        ItemDef.get(22552).protectValue = (int) Math.min(Integer.MAX_VALUE, 30000 * 1000L); //Thammaron's sceptre (uncharged)
        ItemDef.get(11283).protectValue = (int) Math.min(Integer.MAX_VALUE, 20000 * 1000L); //Dragonfire shield (charged)
        ItemDef.get(21633).protectValue = (int) Math.min(Integer.MAX_VALUE, 25000 * 1000L); //Ancient wyvern (charged)
        ItemDef.get(22002).protectValue = (int) Math.min(Integer.MAX_VALUE, 25000 * 1000L); //Dragonfire ward (charged)
        ItemDef.get(12899).protectValue = (int) Math.min(Integer.MAX_VALUE, 25000 * 1000L); //Trident of the swamp (charged)
        ItemDef.get(11907).protectValue = (int) Math.min(Integer.MAX_VALUE, 5000 * 1000L); //Trident of the seas (charged)
        ItemDef.get(11905).protectValue = (int) Math.min(Integer.MAX_VALUE, 5000 * 1000L); //Trident of the seas (fully charged)
        ItemDef.get(12904).protectValue = (int) Math.min(Integer.MAX_VALUE, 10000 * 1000L); //Toxic staff of the dead
        ItemDef.get(20714).protectValue = (int) Math.min(Integer.MAX_VALUE, 6000 * 1000L); //Tome of fire
        ItemDef.get(19550).protectValue = (int) Math.min(Integer.MAX_VALUE, 15000 * 1000L); //Ring of suffering
        ItemDef.get(22613).protectValue = (int) Math.min(Integer.MAX_VALUE, 120000 * 1000L); //Vesta long
    }

    private static boolean isLootingBag(Item item) {
        return item.getId() == 11941 || item.getId() == 22586;
    }
}
