package io.ruin.cache;

public class Varp {

    /**
     * Varp ids below this always have a slot, even if no varbit uses them.
     * Must match the size of Varps_main / Varps_temp in the client (Varps.java).
     */
    public static final int COUNT = 10000;

    public static Varp[] LOADED;

    public static Varp get(int id) {
        if(id < 0 || id >= LOADED.length)
            return null;
        return LOADED[id];
    }

    /**
     * Separator
     */

    public final int id;

    public final Varpbit[] bits;

    public Varp(int id, Varpbit[] bits) {
        this.id = id;
        this.bits = bits;
        LOADED[id] = this;
    }

}
