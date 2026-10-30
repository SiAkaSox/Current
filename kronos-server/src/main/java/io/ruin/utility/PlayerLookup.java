package io.ruin.utility;

import java.io.File;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class PlayerLookup {

    private static final File CHARACTERS_FOLDER = new File(io.ruin.api.utils.ServerPaths.getCharacters());

    public static class PlayerLookupEntry {
        public String playerName;

        public PlayerLookupEntry() {}

        public PlayerLookupEntry(String playerName) {
            this.playerName = playerName;
        }
    }

    private static final PlayerLookupEntry INVALID_ENTRY = new PlayerLookupEntry();
    private static final ConcurrentHashMap<String, PlayerLookupEntry> LOADED = new ConcurrentHashMap<>();

    public static void register(String name) {
        if (name == null || name.isEmpty()) return;
        LOADED.putIfAbsent(name.toLowerCase(), new PlayerLookupEntry(name));
    }

    public static void forObj(Object obj, Consumer<PlayerLookupEntry> consumer) {
        forName((String) obj, consumer);
    }

    public static void forName(String name, Consumer<PlayerLookupEntry> consumer) {
        PlayerLookupEntry entry = getByName(name);
        if (entry == null) {
            consumer.accept(INVALID_ENTRY);
        } else {
            consumer.accept(entry);
        }
    }

    public static PlayerLookupEntry getByName(String name) {
        if (name == null) return null;
        PlayerLookupEntry entry = LOADED.get(name.toLowerCase());
        if (entry == null) {
            File saveFile = new File(CHARACTERS_FOLDER, name.toLowerCase() + ".json");
            if (saveFile.exists()) {
                register(name);
                entry = LOADED.get(name.toLowerCase());
            }
        }
        return entry;
    }
}
