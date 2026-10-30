package net.runelite.standalone;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor @Getter
public enum CustomWorldType {

    PVP("KronosPK", "127.0.0.1", "127.0.0.1", "127.0.0.1"),
    ECO("Kronos", "127.0.0.1", "127.0.0.1", "127.0.0.1"),
    BETA("BETA", "127.0.0.1", "127.0.0.1", "127.0.0.1"),
    DEV("Development", "127.0.0.1", "127.0.0.1", "127.0.0.1");

    private final String name;
    private final String url;
    private final String gameServerAddress;
    private final String fileServerAddress;

}
