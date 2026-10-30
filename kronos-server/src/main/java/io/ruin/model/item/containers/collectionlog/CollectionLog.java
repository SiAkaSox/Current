package io.ruin.model.item.containers.collectionlog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.ruin.cache.ItemDef;
import io.ruin.model.entity.player.Player;
import io.ruin.model.inter.InterfaceHandler;
import io.ruin.model.inter.InterfaceType;
import io.ruin.model.inter.actions.DefaultAction;
import io.ruin.model.inter.actions.SimpleAction;
import io.ruin.model.item.Item;
import io.ruin.model.item.ItemContainerG;
import io.ruin.model.item.actions.ItemAction;
import lombok.extern.slf4j.Slf4j;

import java.io.FileReader;
import java.io.Reader;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Collection log, ported from REIGN and trimmed for Kronos rev 184.
 *
 * Which items are recorded:   kronos-server/data/collection_log.json
 * What the player sees:       cache enums/structs built by tools/make_clog_cache.py from that same JSON
 * Recording an item:          player.getCollectionLog().checkItemReceived(item)
 */
@Slf4j
public class CollectionLog extends ItemContainerG<CollectionLogItem> {

    public static final int CONTAINER_ID = 620;   // inventory the client scripts read (inv_620)
    public static final int INTERFACE_ID = 621;   // collection log interface
    public static final int ITEM_ID = 22711;      // the "Collection log" inventory item
    public static final int SIZE = 500;           // slots; raise it if you ever track more than 500 different items
    public static final int BLANK_ID = -1;

    /* tab order: 0 bosses, 1 raids, 2 clues, 3 minigames, 4 other */
    private static final int[] TAB_STRUCTS = {471, 472, 473, 474, 475};
    private static final int[][] TAB_PARAMS = {
            {40697866, 40697867, 40697868, 40697869},
            {40697870, 40697871, 40697872, 40697877},
            {40697878, 40697886, 40697887, 40697879},
            {40697880, 40697881, 40697890, 40697882},
            {40697883, 40697888, 40697889, 40697884},
    };

    /* interface 621 components that need click masks; 99 = highest list slot you can ever use */
    private static final int[] MASKED_COMPONENTS = {10, 11, 12, 29, 14, 15, 16, 22, 30, 31, 24, 25, 34, 27, 32, 33};
    private static final int MAX_LIST_SLOT = 99;

    private static final Set<Integer> UNLOCKABLE_ITEM_IDS = new HashSet<>();

    /* ---------- opening and tabs ---------- */

    public void openCollectionLog(Player player) {
        player.getPacketSender().sendItems(-1, -1, CONTAINER_ID, getItems());
        player.openInterface(InterfaceType.MAIN, INTERFACE_ID);
        for (int component : MASKED_COMPONENTS)
            player.getPacketSender().sendAccessMask(INTERFACE_ID, component, 0, MAX_LIST_SLOT, 2);
        sendTab(player, 0, 0);
        sendAll = true;
    }

    /* selects a tab and an entry inside it (entry = slot clicked in the left-hand list) */
    private static void sendTab(Player player, int tab, int entry) {
        int[] hashes = TAB_PARAMS[tab];
        player.collection_log_current_tab = tab;
        player.collection_log_current_struct = TAB_STRUCTS[tab];
        player.collection_log_current_entry = entry;
        player.getPacketSender().sendClientScript(2389, "i", tab);
        player.getPacketSender().sendClientScript(2730, "iiiiii",
                hashes[0], hashes[1], hashes[2], hashes[3], TAB_STRUCTS[tab], entry);
    }

    /* ---------- receiving items ---------- */

    /* call this wherever a player receives an item that could belong in the collection log */
    public void checkItemReceived(Item item) {
        if (player == null || item == null)
            return;
        int id = item.getId();
        if (!UNLOCKABLE_ITEM_IDS.contains(id))
            return;
        boolean isNew = !contains(id);
        add(id, item.getAmount());
        sendAll = true;
        send(player);
        if (isNew) {
            ItemDef def = ItemDef.get(id);
            player.sendMessage("<col=ef1020>New item added to your collection log: " + (def == null ? id : def.name));
        }
    }

    /* ---------- container ---------- */

    @Override
    public int add(int id, int amount, Map<String, String> attributes) {
        ItemDef def = ItemDef.get(id);
        if (def == null || def.isPlaceholder())
            return -1;
        if (getAmount(id) + (long) amount > Integer.MAX_VALUE)
            amount = Integer.MAX_VALUE - getAmount(id);
        int freeSlot = -1;
        for (int slot = 0; slot < items.length; slot++) {
            CollectionLogItem existing = items[slot];
            if (existing == null) {
                if (freeSlot == -1)
                    freeSlot = slot;
                continue;
            }
            if (existing.getId() == BLANK_ID)
                continue;
            if (existing.getId() == id && !existing.hasAttributes()) {
                existing.incrementAmount(amount);
                return amount;
            }
        }
        if (freeSlot != -1) {
            set(freeSlot, new CollectionLogItem(id, amount, attributes));
            return amount;
        }
        return 0;
    }

    @Override
    protected CollectionLogItem newItem(int id, int amount, Map<String, String> attributes) {
        return new CollectionLogItem(id, amount, attributes);
    }

    @Override
    protected CollectionLogItem[] newArray(int size) {
        return new CollectionLogItem[size];
    }

    /* ---------- one-time setup ---------- */

    private static void loadJson() {
        try (Reader reader = new FileReader("./data/collection_log.json")) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            for (Map.Entry<String, JsonElement> category : root.entrySet()) {
                for (JsonElement entry : category.getValue().getAsJsonArray()) {
                    JsonArray itemList = entry.getAsJsonObject().getAsJsonArray("items");
                    if (itemList == null)
                        continue;
                    for (JsonElement itemEntry : itemList)
                        UNLOCKABLE_ITEM_IDS.add(itemEntry.getAsJsonObject().get("itemId").getAsInt());
                }
            }
            log.info("Loaded {} collection log items from collection_log.json", UNLOCKABLE_ITEM_IDS.size());
        } catch (Exception e) {
            log.error("Failed to load collection_log.json", e);
        }
    }

    static {
        ItemAction.registerInventory(ITEM_ID, 1, (player, item) -> player.getCollectionLog().openCollectionLog(player));

        InterfaceHandler.register(INTERFACE_ID, h -> {
            /* clicking an entry in the left-hand list: component -> tab */
            h.actions[11] = (DefaultAction) (player, option, slot, itemId) -> sendTab(player, 0, slot);
            h.actions[15] = (DefaultAction) (player, option, slot, itemId) -> sendTab(player, 1, slot);
            h.actions[30] = (DefaultAction) (player, option, slot, itemId) -> sendTab(player, 2, slot);
            h.actions[25] = (DefaultAction) (player, option, slot, itemId) -> sendTab(player, 3, slot);
            h.actions[32] = (DefaultAction) (player, option, slot, itemId) -> sendTab(player, 4, slot);

            /* the five tab buttons along the top */
            h.actions[4] = (SimpleAction) p -> sendTab(p, 0, 0);
            h.actions[5] = (SimpleAction) p -> sendTab(p, 1, 0);
            h.actions[6] = (SimpleAction) p -> sendTab(p, 2, 0);
            h.actions[7] = (SimpleAction) p -> sendTab(p, 3, 0);
            h.actions[8] = (SimpleAction) p -> sendTab(p, 4, 0);
            h.actions[27] = (SimpleAction) p -> sendTab(p, 4, 0);

            h.closedAction = (p, i) -> p.getPacketSender().sendClientScript(101, "i", 11);
        });
        loadJson();
    }

}