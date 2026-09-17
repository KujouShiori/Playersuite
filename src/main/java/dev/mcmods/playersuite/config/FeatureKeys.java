package dev.mcmods.playersuite.config;

/**
 * 七个功能的固定键名（同时用于 {@code enabledFeatures} 配置项与界面/指令的路由）。
 *
 * <p>键名必须是 ASCII，因为它们会出现在配置文件、指令与语言键里。
 */
public final class FeatureKeys {
    public static final String WAREHOUSE = "warehouse";
    public static final String MAIL = "mail";
    public static final String BANK = "bank";
    public static final String SHOP = "shop";
    public static final String MARKET = "market";
    public static final String TITLE = "title";
    public static final String CHECKIN = "checkin";

    /** 全部功能键（与总入口按钮顺序一致）。 */
    public static final String[] ALL = {WAREHOUSE, MAIL, BANK, SHOP, MARKET, TITLE, CHECKIN};

    private FeatureKeys() {
    }
}
