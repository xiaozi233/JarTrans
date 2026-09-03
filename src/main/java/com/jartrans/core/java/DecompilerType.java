package com.jartrans.core.java;

/** 受支持的反编译器种类。 */
public enum DecompilerType {

    VINEFLOWER("vineflower", "Vineflower/Vineflower", "vineflower"),
    CFR("cfr", "leibnitz27/CFR", "cfr"),
    PROCYON("procyon", "mstrobel/procyon", "procyon");

    /** 存入 settings.json 的类型标识。 */
    public final String key;
    /** GitHub 仓库（owner/name），用于查询最新 release。 */
    public final String repo;
    /** release 资产文件名前缀（小写比较）。 */
    public final String assetPrefix;

    DecompilerType(String key, String repo, String assetPrefix) {
        this.key = key;
        this.repo = repo;
        this.assetPrefix = assetPrefix;
    }

    public String displayName() {
        return switch (this) {
            case VINEFLOWER -> "Vineflower";
            case CFR -> "CFR";
            case PROCYON -> "Procyon";
        };
    }

    /** 按文件名内容识别反编译器类型；不匹配返回 null。 */
    public static DecompilerType fromFileName(String lowerName) {
        if (lowerName.contains("vineflower")) {
            return VINEFLOWER;
        }
        if (lowerName.contains("procyon")) {
            return PROCYON;
        }
        if (lowerName.contains("cfr")) {
            return CFR;
        }
        return null;
    }

    /** 按 settings 中的 key 反查；未知返回 null。 */
    public static DecompilerType fromKey(String key) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (DecompilerType t : values()) {
            if (t.key.equals(key)) {
                return t;
            }
        }
        return null;
    }
}
