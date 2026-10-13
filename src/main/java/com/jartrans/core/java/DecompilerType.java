package com.jartrans.core.java;

/** 受支持的进程内反编译器种类。 */
public enum DecompilerType {
    VINEFLOWER("vineflower", "Vineflower"),
    CFR("cfr", "CFR"),
    PROCYON("procyon", "Procyon");

    public final String key;
    private final String displayName;

    DecompilerType(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static DecompilerType fromKey(String key) {
        for (DecompilerType type : values()) {
            if (type.key.equals(key)) {
                return type;
            }
        }
        return null;
    }
}
