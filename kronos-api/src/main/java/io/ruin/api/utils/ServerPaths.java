package io.ruin.api.utils;

import java.io.File;

/**
 * Central path configuration for all server data storage.
 * All paths resolve relative to the server's working directory.
 */
public class ServerPaths {

    private static final String BASE = "server-data";

    public static String getBase() {
        return BASE;
    }

    public static String getPunishments() {
        return BASE + "/punishments";
    }

    public static String getSaved() {
        return BASE + "/_saved";
    }

    public static String getCharacters() {
        return BASE + "/_saved/characters";
    }

    public static String getSocial() {
        return BASE + "/_saved/social";
    }

    public static String getBackups() {
        return BASE + "/backups";
    }

    public static String getPlayers(String stage, String type) {
        return BASE + "/_saved/players/" + stage + "/" + type;
    }

    public static File getBaseDir() {
        return new File(BASE);
    }
}
