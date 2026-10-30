package io.ruin.model.item.containers.collectionlog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.Expose;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Collection log, ported from REIGN and trimmed for Kronos rev 184.
 *
 * Which items are recorded:   kronos-server/data/collection_log.json
 * What the player sees:       cache enums/structs built by tools/make_clog_cache.py from that same JSON
 * Recording an item:          player.getCollectionLog().checkItemReceived(item, sourceName)
 *
 * Every entry (monster) in the JSON keeps its own list of obtained items, so an item that two monsters can drop is
 * only unlocked for the monster that actually dropped it. The client counts "Obtained: x/y" from inventory 620, so
 * whenever the player selects an entry the server sends inventory 620 holding only THAT entry's obtained items.
 *
 * An entry can list the NPC names it covers in "npcs"; those names are the sources that unlock its items and the
 * kills that make up the kill count shown for it.
 */
@Slf4j
public class CollectionLog extends ItemContainerG<CollectionLogItem> {

    public static final int CONTAINER_ID = 620;   // inventory the client scripts read (inv_620)
    public static final int INTERFACE_ID = 621;   // collection log interface
    public static final int ITEM_ID = 22711;      // the "Collection log" inventory item
    public static final int SIZE = 500;           // slots; raise it if you ever track more than 500 different items
    public static final int BLANK_ID = -1;

    /* varp the client's collection log scripts read the kill count from (%var2048 in collection_category_count) */
    private static final int KILL_COUNT_VARP = 2048;

    /* tab order: 0 bosses, 1 raids, 2 clues, 3 minigames, 4 other (same order the cache enums use) */
    private static final String[] TAB_NAMES = {"Bosses", "Raids", "Clues", "Minigames", "Other"};
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

    /** One entry of collection_log.json: a monster (or other source) and the items that unlock in it. */
    private static final class Entry {
        final String name;
        final String key;          // what is saved in the player file, so entry names must be unique
        final int[] itemIds;
        final String[] npcs;       // NPC names whose drops unlock the items and whose kills form the kill count

        Entry(String name, int[] itemIds, String[] npcs) {
            this.name = name;
            this.key = key(name);
            this.itemIds = itemIds;
            this.npcs = npcs;
        }

        boolean hasItem(int id) {
            for (int itemId : itemIds)
                if (itemId == id)
                    return true;
            return false;
        }
    }

    /* ENTRIES.get(tab).get(slot) is the entry shown in that tab at that position of the left-hand list */
    private static final List<List<Entry>> ENTRIES = new ArrayList<>();
    /* lower-case NPC name or entry name -> the entries it can unlock items in */
    private static final Map<String, List<Entry>> BY_SOURCE = new HashMap<>();

    /* entry key -> (item id -> amount) obtained from that entry only */
    @Expose
    private HashMap<String, HashMap<Integer, Integer>> sourceItems = new HashMap<>();

    private static String key(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private static Entry getEntry(int tab, int slot) {
        if (tab < 0 || tab >= ENTRIES.size())
            return null;
        List<Entry> entries = ENTRIES.get(tab);
        return slot < 0 || slot >= entries.size() ? null : entries.get(slot);
    }

    /* ---------- opening and tabs ---------- */

    public void openCollectionLog(Player player) {
        player.openInterface(InterfaceType.MAIN, INTERFACE_ID);
        for (int component : MASKED_COMPONENTS)
            player.getPacketSender().sendAccessMask(INTERFACE_ID, component, 0, MAX_LIST_SLOT, 2);
        sendTab(player, 0, 0);
    }

    /* selects a tab and an entry inside it (entry = slot clicked in the left-hand list) */
    private static void sendTab(Player player, int tab, int entry) {
        int[] hashes = TAB_PARAMS[tab];
        player.collection_log_current_tab = tab;
        player.collection_log_current_struct = TAB_STRUCTS[tab];
        player.collection_log_current_entry = entry;
        /* inventory 620 and the kill count must be in place before the client draws the entry */
        player.getCollectionLog().sendSelection(player, getEntry(tab, entry));
        player.getPacketSender().sendClientScript(2389, "i", tab);
        player.getPacketSender().sendClientScript(2730, "iiiiii",
                hashes[0], hashes[1], hashes[2], hashes[3], TAB_STRUCTS[tab], entry);
    }

    /* sends inventory 620 with only this entry's obtained items, plus the entry's kill count */
    private void sendSelection(Player player, Entry entry) {
        Item[] view = new Item[SIZE];
        int kills = 0;
        if (entry != null) {
            Map<Integer, Integer> owned = sourceItems == null ? null : sourceItems.get(entry.key);
            if (owned != null) {
                int slot = 0;
                for (int id : entry.itemIds) {
                    Integer amount = owned.get(id);
                    if (amount != null && slot < SIZE)
                        view[slot++] = new Item(id, amount);
                }
            }
            kills = getKillCount(player, entry);
        }
        player.getPacketSender().sendItems(-1, -1, CONTAINER_ID, view, view.length);
        player.getPacketSender().sendVarp(KILL_COUNT_VARP, kills);
    }

    /* kills of every NPC the entry covers, from the same counts the quest tab and NPC examine use */
    private static int getKillCount(Player player, Entry entry) {
        long total = 0;
        for (String npc : entry.npcs)
            total += player.getNpcKillCount(npc);
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /* redraws the log if the player is looking at this entry right now */
    private void refresh(Player player, Entry entry) {
        if (!player.isVisibleInterface(INTERFACE_ID))
            return;
        if (getEntry(player.collection_log_current_tab, player.collection_log_current_entry) == entry)
            sendTab(player, player.collection_log_current_tab, player.collection_log_current_entry);
    }

    /* ---------- receiving items ---------- */

    /**
     * Call this wherever a player receives an item that could belong in the collection log.
     * source = the NPC name that dropped it, or the name of the entry for sources that are not NPCs.
     * Only the entries that source belongs to unlock the item.
     */
    public void checkItemReceived(Item item, String source) {
        if (player == null || item == null || source == null)
            return;
        List<Entry> entries = BY_SOURCE.get(key(source));
        if (entries == null)
            return;
        int id = item.getId();
        ItemDef def = ItemDef.get(id);
        if (def == null || def.isPlaceholder())
            return;
        if (sourceItems == null)
            sourceItems = new HashMap<>();
        for (Entry entry : entries) {
            if (!entry.hasItem(id))
                continue;
            HashMap<Integer, Integer> owned = sourceItems.computeIfAbsent(entry.key, k -> new HashMap<>());
            boolean isNew = !owned.containsKey(id);
            owned.merge(id, Math.max(1, item.getAmount()), (a, b) -> (int) Math.min((long) a + b, Integer.MAX_VALUE));
            if (isNew)
                player.sendMessage("<col=ef1020>New item added to your collection log: " + def.name);
            refresh(player, entry);
        }
    }

    /* call after a kill has been counted so the kill count on screen stays current */
    public void onNpcKill(String npcName) {
        if (player == null || npcName == null)
            return;
        List<Entry> entries = BY_SOURCE.get(key(npcName));
        if (entries == null)
            return;
        for (Entry entry : entries)
            refresh(player, entry);
    }

    /* ---------- container ---------- */

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
        for (int i = 0; i < TAB_NAMES.length; i++)
            ENTRIES.add(new ArrayList<>());
        try (Reader reader = new FileReader("./data/collection_log.json")) {
            JsonObject root = new JsonParser().parse(reader).getAsJsonObject();
            int items = 0;
            for (int tab = 0; tab < TAB_NAMES.length; tab++) {
                JsonElement category = root.get(TAB_NAMES[tab]);
                if (category == null || !category.isJsonArray())
                    continue;
                for (JsonElement element : category.getAsJsonArray()) {
                    JsonObject json = element.getAsJsonObject();
                    Entry entry = new Entry(json.get("name").getAsString(), readItemIds(json.getAsJsonArray("items")),
                            readNames(json.getAsJsonArray("npcs")));
                    ENTRIES.get(tab).add(entry);
                    items += entry.itemIds.length;
                    Set<String> sources = new LinkedHashSet<>();
                    sources.add(entry.key);
                    for (String npc : entry.npcs)
                        sources.add(key(npc));
                    for (String source : sources)
                        BY_SOURCE.computeIfAbsent(source, k -> new ArrayList<>()).add(entry);
                }
            }
            log.info("Loaded {} collection log items in {} entries from collection_log.json",
                    items, ENTRIES.stream().mapToInt(List::size).sum());
        } catch (Exception e) {
            log.error("Failed to load collection_log.json", e);
        }
    }

    private static int[] readItemIds(JsonArray array) {
        if (array == null)
            return new int[0];
        int[] ids = new int[array.size()];
        for (int i = 0; i < ids.length; i++)
            ids[i] = array.get(i).getAsJsonObject().get("itemId").getAsInt();
        return ids;
    }

    private static String[] readNames(JsonArray array) {
        if (array == null)
            return new String[0];
        String[] names = new String[array.size()];
        for (int i = 0; i < names.length; i++)
            names[i] = array.get(i).getAsString();
        return names;
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
