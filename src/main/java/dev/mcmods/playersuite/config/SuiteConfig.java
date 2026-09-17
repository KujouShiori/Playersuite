package dev.mcmods.playersuite.config;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * 全模组唯一配置文件：{@code config/playersuite-common.toml}（COMMON 类型）。
 *
 * <p>为什么是 COMMON：NeoForge 的 SERVER 配置随世界加载，而命令树、指令名、功能开关
 * 在服务启动阶段就要确定，SERVER 配置那一刻还没加载（这一点已在实机上验证过）。
 * COMMON 配置在 mod 构造阶段加载，因此全部功能设置统一放在这里，改完重启服务器生效。
 *
 * <p>所有读取都通过 {@code xxx()} 便捷方法，内部带兜底默认值：即使配置文件被删坏，
 * 模组也能按默认值正常运行，不会抛异常。
 */
public final class SuiteConfig {
    public static final ModConfigSpec SPEC;

    // ============================ [general] ============================
    public static final ModConfigSpec.ConfigValue<String> HUB_ROOT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> HUB_ALIASES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ENABLED_FEATURES;
    public static final ModConfigSpec.IntValue LEVEL_MANAGE;
    public static final ModConfigSpec.IntValue LEVEL_ADMIN;
    public static final ModConfigSpec.BooleanValue REGISTER_SHORTCUTS;
    public static final ModConfigSpec.ConfigValue<String> WH_COMMAND_ROOT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> WH_COMMAND_ALIASES;
    public static final ModConfigSpec.BooleanValue JOIN_HINT;
    public static final ModConfigSpec.BooleanValue ALLOW_MANAGE_OTHERS;

    // ============================ [economy] ============================
    public static final ModConfigSpec.ConfigValue<String> CURRENCY_SYMBOL;
    public static final ModConfigSpec.IntValue INITIAL_BALANCE;
    public static final ModConfigSpec.LongValue MAX_BALANCE;
    public static final ModConfigSpec.IntValue TRANSACTION_LOG_SIZE;
    public static final ModConfigSpec.BooleanValue LOG_TRANSACTIONS;

    // ============================ [warehouse] ===========================
    public static final ModConfigSpec.IntValue WH_INITIAL_ROWS;
    public static final ModConfigSpec.IntValue WH_ROWS_PER_UPGRADE;
    public static final ModConfigSpec.IntValue WH_MAX_ROWS;
    public static final ModConfigSpec.IntValue WH_ROWS_PER_PAGE;
    public static final ModConfigSpec.ConfigValue<String> WH_UPGRADE_PRICES;
    public static final ModConfigSpec.IntValue WH_BASE_PRICE;
    public static final ModConfigSpec.DoubleValue WH_PRICE_GROWTH;
    public static final ModConfigSpec.IntValue WH_PRICE_ROUNDING;
    public static final ModConfigSpec.IntValue WH_MAX_PRICE;

    // ============================ [bank] ===============================
    public static final ModConfigSpec.ConfigValue<String> BANK_QUICK_AMOUNTS;
    public static final ModConfigSpec.LongValue BANK_MIN_DEPOSIT;
    public static final ModConfigSpec.LongValue BANK_MAX_PRINCIPAL;
    public static final ModConfigSpec.DoubleValue BANK_DAILY_INTEREST_PERCENT;
    public static final ModConfigSpec.LongValue BANK_INTEREST_MAX_PER_DAY;
    public static final ModConfigSpec.DoubleValue BANK_WITHDRAW_FEE_PERCENT;
    public static final ModConfigSpec.IntValue BANK_WITHDRAW_COOLDOWN_SECONDS;
    public static final ModConfigSpec.IntValue BANK_HISTORY_SIZE;

    // ============================ [mail] ===============================
    public static final ModConfigSpec.IntValue MAIL_MAX_MAILS;
    public static final ModConfigSpec.IntValue MAIL_ATTACHMENT_SLOTS;
    public static final ModConfigSpec.IntValue MAIL_TITLE_MAX_LEN;
    public static final ModConfigSpec.IntValue MAIL_BODY_MAX_LEN;
    public static final ModConfigSpec.IntValue MAIL_SEND_COOLDOWN_SECONDS;
    public static final ModConfigSpec.BooleanValue MAIL_NOTIFY_ON_LOGIN;
    public static final ModConfigSpec.LongValue MAIL_SEND_COST;
    public static final ModConfigSpec.LongValue MAIL_ATTACHMENT_COST;
    public static final ModConfigSpec.IntValue MAIL_PAGE_SIZE;

    // ============================ [shop] ===============================
    public static final ModConfigSpec.IntValue SHOP_LISTINGS;
    public static final ModConfigSpec.DoubleValue SHOP_TAX_PERCENT;
    public static final ModConfigSpec.LongValue SHOP_MIN_PRICE;
    public static final ModConfigSpec.LongValue SHOP_MAX_PRICE;
    public static final ModConfigSpec.IntValue SHOP_MAX_STOCK;
    public static final ModConfigSpec.IntValue SHOP_BROWSE_PAGE_SIZE;
    public static final ModConfigSpec.IntValue SHOP_NAME_MAX_LEN;
    public static final ModConfigSpec.BooleanValue SHOP_OFFLINE_PAYOUT;

    // ============================ [market] =============================
    public static final ModConfigSpec.BooleanValue MARKET_BUY_ENABLED;
    public static final ModConfigSpec.BooleanValue MARKET_SELL_ENABLED;
    public static final ModConfigSpec.BooleanValue MARKET_STOCK_ENABLED;
    public static final ModConfigSpec.IntValue MARKET_DEFAULT_STOCK;
    public static final ModConfigSpec.IntValue MARKET_RESTOCK_HOURS;
    public static final ModConfigSpec.IntValue MARKET_DAILY_BUY_LIMIT;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MARKET_ENTRIES;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MARKET_CATEGORIES;

    // ============================ [title] ==============================
    public static final ModConfigSpec.ConfigValue<String> TITLE_PREFIX_FORMAT;
    public static final ModConfigSpec.BooleanValue TITLE_SHOW_IN_CHAT;
    public static final ModConfigSpec.BooleanValue TITLE_SHOW_ON_JOIN;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> TITLE_CATALOG;
    public static final ModConfigSpec.IntValue TITLE_MAX_OWNED;

    // ============================ [checkin] ============================
    public static final ModConfigSpec.IntValue CHECKIN_CYCLE_DAYS;
    public static final ModConfigSpec.ConfigValue<String> CHECKIN_COIN_REWARDS;
    public static final ModConfigSpec.ConfigValue<String> CHECKIN_ITEM_REWARDS;
    public static final ModConfigSpec.IntValue CHECKIN_RESET_HOUR;
    public static final ModConfigSpec.BooleanValue CHECKIN_ALLOW_MAKEUP;
    public static final ModConfigSpec.LongValue CHECKIN_MAKEUP_COST;
    public static final ModConfigSpec.IntValue CHECKIN_MAKEUP_MAX;
    public static final ModConfigSpec.LongValue CHECKIN_STREAK_BONUS;
    public static final ModConfigSpec.LongValue CHECKIN_STREAK_BONUS_MAX;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.comment("总入口与权限").push("general");
        HUB_ROOT = b
                .comment("打开总功能界面的指令名（只能用字母/数字/下划线/中划线/点号）。",
                        "直接输入 /" + "ps 即可打开总入口 GUI。")
                .define("root", "ps");
        HUB_ALIASES = b
                .comment("总入口指令的别名，写多个即可同时生效。")
                .defineList("aliases", List.of("suite", "fw"), () -> "",
                        o -> o instanceof String s && !s.isBlank());
        ENABLED_FEATURES = b
                .comment("启用哪些功能模块（可删掉某项来关闭对应功能，总入口界面里也不会出现该按钮）。",
                        "可选值：warehouse, mail, bank, shop, market, title, checkin")
                .defineList("enabledFeatures",
                        List.of("warehouse", "mail", "bank", "shop", "market", "title", "checkin"), () -> "",
                        o -> o instanceof String s && !s.isBlank());
        LEVEL_MANAGE = b
                .comment("界面内『管理他人数据』类按钮所需权限等级（0-4）。原版：2=OP，3=GM，4=所有者。")
                .defineInRange("managePermission", 2, 0, 4);
        LEVEL_ADMIN = b
                .comment("危险操作（改余额、重置签到、强制下架、新建称号等）所需权限等级。")
                .defineInRange("adminPermission", 3, 0, 4);
        REGISTER_SHORTCUTS = b
                .comment("是否额外注册各功能的独立指令（/warehouse、/mail、/bank、/shop、/market、/title、/checkin）。",
                        "false 时只保留统一入口指令。")
                .define("registerShortcutCommands", true);
        WH_COMMAND_ROOT = b
                .comment("个人仓库独立指令的主名（/warehouse），关掉 registerShortcutCommands 后不生效。")
                .define("warehouseRoot", "warehouse");
        WH_COMMAND_ALIASES = b
                .comment("个人仓库指令的别名。")
                .defineList("warehouseAliases", List.of("wh", "pw", "grc"), () -> "",
                        o -> o instanceof String s && !s.isBlank());
        JOIN_HINT = b
                .comment("玩家进服时是否发送一条可点击的统一入口提示（/" + "ps）。")
                .define("joinHint", true);
        ALLOW_MANAGE_OTHERS = b
                .comment("true：达到 managePermission 的玩家可以在界面里代开/代办他人功能；false：只能操作自己的。")
                .define("allowManageOthers", true);
        b.pop();

        b.comment("经济系统（金币钱包，所有功能共用同一份余额）").push("economy");
        CURRENCY_SYMBOL = b.comment("货币符号，仅用于界面显示。").define("currencySymbol", "金");
        INITIAL_BALANCE = b
                .comment("玩家第一次进服时赠送的金币（已有记录的玩家不会重复赠送）。0 表示不赠送。")
                .defineInRange("initialBalance", 500, 0, 1000000000);
        MAX_BALANCE = b
                .comment("单人钱包余额上限（防止溢出与配置事故）。").defineInRange("maxBalance", 1_000_000_000L, 1000L, Long.MAX_VALUE);
        TRANSACTION_LOG_SIZE = b
                .comment("钱包流水保留条数（界面里可查看最近收支）。").defineInRange("transactionLogSize", 20, 0, 200);
        LOG_TRANSACTIONS = b
                .comment("是否记录收支流水（关闭可省一点存档体积）。").define("logTransactions", true);
        b.pop();

        b.comment("个人仓库：容量成长（格子数 = 行数 * 9）").push("warehouse");
        WH_INITIAL_ROWS = b.comment("0 级仓库的初始行数。").defineInRange("initialRows", 2, 1, 27);
        WH_ROWS_PER_UPGRADE = b.comment("每次扩充增加的行数。").defineInRange("rowsPerUpgrade", 1, 1, 9);
        WH_MAX_ROWS = b.comment("行数上限（到达即满级，不再收费）。").defineInRange("maxRows", 6, 1, 54);
        WH_ROWS_PER_PAGE = b.comment("界面每页显示的行数（上限 6，保证任何分辨率都放得下）。")
                .defineInRange("rowsPerPage", 6, 1, 6);
        WH_BASE_PRICE = b.comment("第 1 次扩充的基础价格（upgradePrices 为空时使用）。")
                .defineInRange("basePrice", 100, 0, Integer.MAX_VALUE);
        WH_PRICE_GROWTH = b.comment("每次扩充的价格倍率，1.0 表示固定价格。")
                .defineInRange("priceGrowth", 1.6D, 0.0D, 10.0D);
        WH_PRICE_ROUNDING = b.comment("价格取整粒度，例如 10 表示四舍五入到 10 的倍数；0/1 不取整。")
                .defineInRange("priceRounding", 5, 0, 1000000);
        WH_MAX_PRICE = b.comment("单次扩充价格上限。").defineInRange("maxPrice", 10_000_000, 1, Integer.MAX_VALUE);
        WH_UPGRADE_PRICES = b
                .comment("手写价格表（优先于公式），逗号分隔：第 1 个数是 0->1 级价格，第 2 个是 1->2 级……",
                        "列表用完后最后一个价格适用于更高等级。示例：\"100, 200, 400, 800\"")
                .define("upgradePrices", "");
        b.pop();

        b.comment("银行：存取款与每日利息").push("bank");
        BANK_QUICK_AMOUNTS = b.comment("界面上的快捷金额按钮（逗号分隔，最多 4 个）。")
                .define("quickAmounts", "100,500,1000,5000");
        BANK_MIN_DEPOSIT = b.comment("单笔最低存款额。").defineInRange("minDeposit", 10L, 1L, 1000000L);
        BANK_MAX_PRINCIPAL = b.comment("存款本金上限。").defineInRange("maxPrincipal", 100_000_000L, 1000L, Long.MAX_VALUE);
        BANK_DAILY_INTEREST_PERCENT = b
                .comment("每日利息（按本金百分比，1.0 表示 1%）。0 表示关闭利息。")
                .defineInRange("dailyInterestPercent", 1.0D, 0.0D, 100.0D);
        BANK_INTEREST_MAX_PER_DAY = b.comment("单日利息上限。").defineInRange("interestMaxPerDay", 5000L, 0L, 100000000L);
        BANK_WITHDRAW_FEE_PERCENT = b.comment("取款手续费百分比（0 表示免手续费）。")
                .defineInRange("withdrawFeePercent", 0.0D, 0.0D, 99.0D);
        BANK_WITHDRAW_COOLDOWN_SECONDS = b.comment("两次取款之间的冷却秒数（0 关闭）。")
                .defineInRange("withdrawCooldownSeconds", 0, 0, 86400);
        BANK_HISTORY_SIZE = b.comment("界面显示的历史记录条数。").defineInRange("historySize", 12, 0, 60);
        b.pop();

        b.comment("邮件：玩家私信与附件").push("mail");
        MAIL_MAX_MAILS = b.comment("收件箱最大保留封数（超出会自动丢弃最旧的已读邮件）。")
                .defineInRange("maxMails", 40, 5, 200);
        MAIL_ATTACHMENT_SLOTS = b.comment("每封邮件最多携带几个附件（0 关闭附件）。")
                .defineInRange("attachmentSlots", 2, 0, 9);
        MAIL_TITLE_MAX_LEN = b.comment("标题最大字符数。").defineInRange("titleMaxLength", 24, 1, 64);
        MAIL_BODY_MAX_LEN = b.comment("正文最大字符数。").defineInRange("bodyMaxLength", 200, 1, 1024);
        MAIL_SEND_COOLDOWN_SECONDS = b.comment("两封邮件之间的发送冷却（秒）。")
                .defineInRange("sendCooldownSeconds", 3, 0, 3600);
        MAIL_NOTIFY_ON_LOGIN = b.comment("玩家进服时是否提示未读邮件数量。").define("notifyOnLogin", true);
        MAIL_SEND_COST = b.comment("每封邮件的基础费用（0 免费）。").defineInRange("sendCost", 0L, 0L, 1000000L);
        MAIL_ATTACHMENT_COST = b.comment("每个附件额外收取的费用。").defineInRange("attachmentCost", 0L, 0L, 1000000L);
        MAIL_PAGE_SIZE = b.comment("邮件列表每页显示几封。").defineInRange("pageSize", 6, 1, 9);
        b.pop();

        b.comment("玩家商店：玩家自己上架出售").push("shop");
        SHOP_LISTINGS = b.comment("每人最多几个在售货架位。").defineInRange("listingsPerShop", 6, 1, 27);
        SHOP_TAX_PERCENT = b.comment("成交时对卖家收取的手续费百分比（0 免税）。")
                .defineInRange("taxPercent", 5.0D, 0.0D, 90.0D);
        SHOP_MIN_PRICE = b.comment("单个货架最低单价。").defineInRange("minPrice", 1L, 1L, 1000000L);
        SHOP_MAX_PRICE = b.comment("单个货架最高单价。").defineInRange("maxPrice", 100000L, 1L, 1000000000L);
        SHOP_MAX_STOCK = b.comment("单个货架最大数量。").defineInRange("maxStock", 9999, 1, 999999);
        SHOP_BROWSE_PAGE_SIZE = b.comment("商店列表/店内货架每页显示几行（不超过 9）。")
                .defineInRange("browsePageSize", 6, 1, 9);
        SHOP_NAME_MAX_LEN = b.comment("店铺招牌最大字符数。").defineInRange("shopNameMaxLength", 16, 2, 32);
        SHOP_OFFLINE_PAYOUT = b
                .comment("true：店主离线时收入进入『待领取』，下次上线在商店界面领取；false：只有在线时才允许成交。")
                .define("offlinePayout", true);
        b.pop();

        b.comment("官方市场：服务端定价的固定目录（参考 TradeSquares 的 give/cost 思路）").push("market");
        MARKET_CATEGORIES = b
                .comment("分类列表（顺序即界面上的分类按钮顺序）。分类写在每个条目的最后一列。")
                .defineList("categories", List.of("方块", "矿物", "食物", "刷怪掉落", "农资", "杂物"), () -> "",
                        o -> o instanceof String s && !s.isBlank());
        MARKET_ENTRIES = b
                .comment("商品目录，每条格式： 物品ID|每组数量|买价|卖价|库存|分类",
                        "买价 = 玩家从市场购买每件的价格；卖价 = 市场收购玩家每件的价格（填 0 表示不收）。",
                        "库存填 -1 表示无限。示例：\"minecraft:iron_ingot|1|120|90|64|矿物\"",
                        "改完执行 /market reload 或重启服务器生效。")
                .defineList("entries", List.of(
                        "minecraft:cobblestone|64|8|2|-1|方块",
                        "minecraft:dirt|64|6|1|-1|方块",
                        "minecraft:oak_log|16|20|10|-1|方块",
                        "minecraft:glass|32|40|12|-1|方块",
                        "minecraft:iron_ingot|8|120|80|256|矿物",
                        "minecraft:gold_ingot|8|260|170|128|矿物",
                        "minecraft:diamond|1|1500|1000|64|矿物",
                        "minecraft:redstone|16|60|32|256|矿物",
                        "minecraft:lapis_lazuli|16|90|50|192|矿物",
                        "minecraft:coal|16|30|16|512|矿物",
                        "minecraft:cooked_beef|16|70|40|256|食物",
                        "minecraft:bread|16|40|20|512|食物",
                        "minecraft:golden_carrot|4|180|110|96|食物",
                        "minecraft:leather|16|70|40|192|刷怪掉落",
                        "minecraft:rotten_flesh|32|12|5|-1|刷怪掉落",
                        "minecraft:string|32|18|8|-1|刷怪掉落",
                        "minecraft:gunpowder|16|110|70|160|刷怪掉落",
                        "minecraft:ender_pearl|1|320|220|48|刷怪掉落",
                        "minecraft:slime_ball|8|60|35|128|刷怪掉落",
                        "minecraft:wheat|16|30|16|512|农资",
                        "minecraft:carrot|16|26|14|512|农资",
                        "minecraft:potato|16|26|14|512|农资",
                        "minecraft:sugar|16|22|10|512|农资",
                        "minecraft:bone_meal|16|18|6|-1|农资",
                        "minecraft:book|4|120|70|96|杂物",
                        "minecraft:arrow|32|20|8|-1|杂物",
                        "minecraft:torch|16|10|3|-1|杂物",
                        "minecraft:bucket|1|90|50|32|杂物"), () -> "",
                        o -> o instanceof String s && !s.isBlank());
        MARKET_BUY_ENABLED = b.comment("是否允许玩家向官方市场购买。").define("buyEnabled", true);
        MARKET_SELL_ENABLED = b.comment("是否允许玩家向官方市场出售。").define("sellEnabled", true);
        MARKET_STOCK_ENABLED = b.comment("是否启用库存限制（false = 全部无限）。").define("stockEnabled", true);
        MARKET_DEFAULT_STOCK = b.comment("条目没写库存时使用的默认库存。").defineInRange("defaultStock", 64, 1, 999999);
        MARKET_RESTOCK_HOURS = b.comment("库存自动回满的间隔小时数（0 = 只在重启时回满）。")
                .defineInRange("restockHours", 24, 0, 720);
        MARKET_DAILY_BUY_LIMIT = b.comment("单人单日累计可购买的件数上限（0 = 不限）。")
                .defineInRange("dailyBuyLimit", 0, 0, 1000000);
        b.pop();

        b.comment("玩家称号").push("title");
        TITLE_CATALOG = b
                .comment("称号目录，每条格式： id|显示名|价格|颜色",
                        "颜色取原版 ChatFormatting 名（gray/yellow/gold/aqua/light_purple/red/dark_green…），填 none 为默认色。",
                        "价格为 0 表示免费可领。示例：\"rich|富甲一方|5000|gold\"")
                .defineList("catalog", List.of(
                        "newbie|初来乍到|0|gray",
                        "worker|勤劳致富|1000|green",
                        "rich|富甲一方|5000|gold",
                        "trader|商业巨头|12000|light_purple",
                        "slayer|屠魔勇士|8000|red",
                        "lucky|天选之人|20000|aqua"), () -> "",
                        o -> o instanceof String s && !s.isBlank());
        TITLE_PREFIX_FORMAT = b
                .comment("聊天栏里的称号格式，%s 会被替换为称号显示名。填 none 则不显示前缀。")
                .define("chatFormat", "[%s]");
        TITLE_SHOW_IN_CHAT = b.comment("是否把称号注入聊天消息前缀。").define("showInChat", true);
        TITLE_SHOW_ON_JOIN = b.comment("玩家进服时是否播报其称号。").define("showOnJoin", true);
        TITLE_MAX_OWNED = b.comment("单人最多拥有几个称号。").defineInRange("maxOwned", 30, 1, 200);
        b.pop();

        b.comment("每日签到").push("checkin");
        CHECKIN_CYCLE_DAYS = b.comment("签到周期天数（循环发奖，一般 7）。").defineInRange("cycleDays", 7, 1, 31);
        CHECKIN_COIN_REWARDS = b
                .comment("每个周期第 N 天的金币奖励，逗号分隔；不足天数时用最后一个值补齐。")
                .define("coinRewards", "200,300,400,500,600,800,1200");
        CHECKIN_ITEM_REWARDS = b
                .comment("额外物品奖励，格式： 天:物品ID|数量 ，用分号分隔；留空表示不给物品。",
                        "示例：\"3:minecraft:diamond|1;7:minecraft:netherite_scrap|1\"")
                .define("itemRewards", "3:minecraft:diamond|1;7:minecraft:iron_ingot|8");
        CHECKIN_RESET_HOUR = b
                .comment("每日重置的小时（服务器本地时间 0-23，例如 6 表示早上 6 点算新的一天）。")
                .defineInRange("resetHour", 0, 0, 23);
        CHECKIN_ALLOW_MAKEUP = b.comment("是否允许补签（花钱补上漏掉的一天）。").define("allowMakeup", true);
        CHECKIN_MAKEUP_COST = b.comment("每次补签的费用。").defineInRange("makeupCost", 800L, 0L, 10000000L);
        CHECKIN_MAKEUP_MAX = b.comment("每周期最多补签几次。").defineInRange("makeupMax", 2, 0, 31);
        CHECKIN_STREAK_BONUS = b
                .comment("连续签到额外奖励：每连续一天在当天奖励上再加多少金币。").defineInRange("streakBonus", 20L, 0L, 1000000L);
        CHECKIN_STREAK_BONUS_MAX = b.comment("连续奖励的封顶值。").defineInRange("streakBonusMax", 200L, 0L, 10000000L);
        b.pop();

        SPEC = b.build();
    }

    private SuiteConfig() {
    }

    // =============================== 通用读取 ===============================

    public static boolean isLoaded() {
        try {
            return SPEC.isLoaded();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 功能是否启用（warehouse/mail/bank/shop/market/title/checkin）。 */
    public static boolean featureEnabled(String key) {
        List<? extends String> raw = safeGet(ENABLED_FEATURES, List.of());
        for (String s : raw) {
            if (s != null && s.equalsIgnoreCase(key)) {
                return true;
            }
        }
        // 配置被改坏或读不到时，默认全部开启，保证功能可用
        return raw.isEmpty();
    }

    /** 当前生效的功能开关列表（日志用）。 */
    public static List<? extends String> enabledFeatureList() {
        List<? extends String> raw = safeGet(ENABLED_FEATURES, List.of());
        return raw.isEmpty() ? List.of(FeatureKeys.ALL) : raw;
    }

    public static int managePermission() {
        return Math.min(4, Math.max(0, safeGet(LEVEL_MANAGE, 2)));
    }

    public static int adminPermission() {
        return Math.min(4, Math.max(0, safeGet(LEVEL_ADMIN, 3)));
    }

    public static boolean registerShortcutCommands() {
        return safeGet(REGISTER_SHORTCUTS, Boolean.TRUE);
    }

    public static String hubRoot() {
        return sanitize(safeGet(HUB_ROOT, "ps"), "ps");
    }

    public static List<String> hubAliases() {
        List<? extends String> raw = safeGet(HUB_ALIASES, List.of("suite", "fw"));
        String root = hubRoot();
        return raw.stream().map(s -> sanitize(s, "")).filter(s -> !s.isEmpty() && !s.equals(root)).distinct().toList();
    }

    /** 个人仓库指令主名。 */
    public static String commandRoot() {
        return sanitize(safeGet(WH_COMMAND_ROOT, "warehouse"), "warehouse");
    }

    public static List<String> commandAliases() {
        List<? extends String> raw = safeGet(WH_COMMAND_ALIASES, List.of("wh", "pw", "grc"));
        String root = commandRoot();
        return raw.stream().map(s -> sanitize(s, "")).filter(s -> !s.isEmpty() && !s.equals(root)).distinct().toList();
    }

    public static boolean joinHint() {
        return safeGet(JOIN_HINT, Boolean.TRUE);
    }

    public static boolean allowManageOthers() {
        return safeGet(ALLOW_MANAGE_OTHERS, Boolean.TRUE);
    }

    /** 把不合法字符剔除（Brigadier 只能解析 ASCII 指令名）。 */
    public static String sanitize(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String cleaned = raw.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_.\\-]", "");
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    // =============================== 经济 ===============================

    public static String currencySymbol() {
        String s = safeGet(CURRENCY_SYMBOL, "金");
        return s == null || s.isBlank() ? "金" : s;
    }

    public static long initialBalance() {
        return Math.max(0, safeGet(INITIAL_BALANCE, 500).longValue());
    }

    public static long maxBalance() {
        return Math.max(1000L, safeGet(MAX_BALANCE, 1_000_000_000L));
    }

    public static int transactionLogSize() {
        return Math.max(0, safeGet(TRANSACTION_LOG_SIZE, 20));
    }

    public static boolean logTransactions() {
        return safeGet(LOG_TRANSACTIONS, Boolean.TRUE);
    }

    // =============================== 仓库 ===============================

    public static int warehouseInitialRows() {
        return clamp(safeGet(WH_INITIAL_ROWS, 2), 1, 27);
    }

    public static int warehouseRowsPerUpgrade() {
        return clamp(safeGet(WH_ROWS_PER_UPGRADE, 1), 1, 9);
    }

    public static int warehouseMaxRows() {
        return clamp(safeGet(WH_MAX_ROWS, 6), 1, 54);
    }

    public static int warehouseRowsPerPage() {
        return clamp(safeGet(WH_ROWS_PER_PAGE, 6), 1, 6);
    }

    public static int warehouseBasePrice() {
        return Math.max(0, safeGet(WH_BASE_PRICE, 100));
    }

    public static double warehousePriceGrowth() {
        return Math.max(0.0D, Math.min(10.0D, safeGet(WH_PRICE_GROWTH, 1.6D)));
    }

    public static int warehousePriceRounding() {
        return Math.max(0, safeGet(WH_PRICE_ROUNDING, 5));
    }

    public static int warehouseMaxPrice() {
        return Math.max(1, safeGet(WH_MAX_PRICE, 10_000_000));
    }

    public static String warehouseUpgradePrices() {
        String s = safeGet(WH_UPGRADE_PRICES, "");
        return s == null ? "" : s;
    }

    // =============================== 银行 ===============================

    public static long[] bankQuickAmounts() {
        return parseLongList(safeGet(BANK_QUICK_AMOUNTS, "100,500,1000,5000"), 4, new long[]{100, 500, 1000, 5000});
    }

    public static long bankMinDeposit() {
        return Math.max(1L, safeGet(BANK_MIN_DEPOSIT, 10L));
    }

    public static long bankMaxPrincipal() {
        return Math.max(1000L, safeGet(BANK_MAX_PRINCIPAL, 100_000_000L));
    }

    public static double bankDailyInterestPercent() {
        return clampD(safeGet(BANK_DAILY_INTEREST_PERCENT, 1.0D), 0.0D, 100.0D);
    }

    public static long bankInterestMaxPerDay() {
        return Math.max(0L, safeGet(BANK_INTEREST_MAX_PER_DAY, 5000L));
    }

    public static double bankWithdrawFeePercent() {
        return clampD(safeGet(BANK_WITHDRAW_FEE_PERCENT, 0.0D), 0.0D, 99.0D);
    }

    public static int bankWithdrawCooldownSeconds() {
        return Math.max(0, safeGet(BANK_WITHDRAW_COOLDOWN_SECONDS, 0));
    }

    public static int bankHistorySize() {
        return Math.max(0, clamp(safeGet(BANK_HISTORY_SIZE, 12), 0, 60));
    }

    // =============================== 邮件 ===============================

    public static int mailMaxMails() {
        return clamp(safeGet(MAIL_MAX_MAILS, 40), 5, 200);
    }

    public static int mailAttachmentSlots() {
        return clamp(safeGet(MAIL_ATTACHMENT_SLOTS, 2), 0, 9);
    }

    public static int mailTitleMaxLen() {
        return clamp(safeGet(MAIL_TITLE_MAX_LEN, 24), 1, 64);
    }

    public static int mailBodyMaxLen() {
        return clamp(safeGet(MAIL_BODY_MAX_LEN, 200), 1, 1024);
    }

    public static int mailSendCooldownSeconds() {
        return Math.max(0, safeGet(MAIL_SEND_COOLDOWN_SECONDS, 3));
    }

    public static boolean mailNotifyOnLogin() {
        return safeGet(MAIL_NOTIFY_ON_LOGIN, Boolean.TRUE);
    }

    public static long mailSendCost() {
        return Math.max(0L, safeGet(MAIL_SEND_COST, 0L));
    }

    public static long mailAttachmentCost() {
        return Math.max(0L, safeGet(MAIL_ATTACHMENT_COST, 0L));
    }

    public static int mailPageSize() {
        return clamp(safeGet(MAIL_PAGE_SIZE, 6), 1, 9);
    }

    // =============================== 玩家商店 ===============================

    public static int shopListings() {
        return clamp(safeGet(SHOP_LISTINGS, 6), 1, 27);
    }

    public static double shopTaxPercent() {
        return clampD(safeGet(SHOP_TAX_PERCENT, 5.0D), 0.0D, 90.0D);
    }

    public static long shopMinPrice() {
        return Math.max(1L, safeGet(SHOP_MIN_PRICE, 1L));
    }

    public static long shopMaxPrice() {
        return Math.max(1L, safeGet(SHOP_MAX_PRICE, 100000L));
    }

    public static int shopMaxStock() {
        return clamp(safeGet(SHOP_MAX_STOCK, 9999), 1, 999999);
    }

    public static int shopBrowsePageSize() {
        return clamp(safeGet(SHOP_BROWSE_PAGE_SIZE, 6), 1, 9);
    }

    public static int shopNameMaxLen() {
        return clamp(safeGet(SHOP_NAME_MAX_LEN, 16), 2, 32);
    }

    public static boolean shopOfflinePayout() {
        return safeGet(SHOP_OFFLINE_PAYOUT, Boolean.TRUE);
    }

    // =============================== 官方市场 ===============================

    public static boolean marketBuyEnabled() {
        return safeGet(MARKET_BUY_ENABLED, Boolean.TRUE);
    }

    public static boolean marketSellEnabled() {
        return safeGet(MARKET_SELL_ENABLED, Boolean.TRUE);
    }

    public static boolean marketStockEnabled() {
        return safeGet(MARKET_STOCK_ENABLED, Boolean.TRUE);
    }

    public static int marketDefaultStock() {
        return clamp(safeGet(MARKET_DEFAULT_STOCK, 64), 1, 999999);
    }

    public static int marketRestockHours() {
        return clamp(safeGet(MARKET_RESTOCK_HOURS, 24), 0, 720);
    }

    public static int marketDailyBuyLimit() {
        return Math.max(0, safeGet(MARKET_DAILY_BUY_LIMIT, 0));
    }

    public static List<? extends String> marketEntries() {
        return safeGet(MARKET_ENTRIES, List.of());
    }

    public static List<? extends String> marketCategories() {
        List<? extends String> raw = safeGet(MARKET_CATEGORIES, List.of());
        return raw.isEmpty() ? List.of("方块", "矿物", "食物", "刷怪掉落", "农资", "杂物") : raw;
    }

    // =============================== 称号 ===============================

    public static List<? extends String> titleCatalog() {
        return safeGet(TITLE_CATALOG, List.of());
    }

    public static String titlePrefixFormat() {
        String s = safeGet(TITLE_PREFIX_FORMAT, "[%s]");
        return s == null ? "[%s]" : s;
    }

    public static boolean titleShowInChat() {
        return safeGet(TITLE_SHOW_IN_CHAT, Boolean.TRUE);
    }

    public static boolean titleShowOnJoin() {
        return safeGet(TITLE_SHOW_ON_JOIN, Boolean.TRUE);
    }

    public static int titleMaxOwned() {
        return clamp(safeGet(TITLE_MAX_OWNED, 30), 1, 200);
    }

    // =============================== 签到 ===============================

    public static int checkinCycleDays() {
        return clamp(safeGet(CHECKIN_CYCLE_DAYS, 7), 1, 31);
    }

    public static long[] checkinCoinRewards() {
        return parseLongList(safeGet(CHECKIN_COIN_REWARDS, "200,300,400,500,600,800,1200"), 31,
                new long[]{200, 300, 400, 500, 600, 800, 1200});
    }

    public static String checkinItemRewards() {
        String s = safeGet(CHECKIN_ITEM_REWARDS, "");
        return s == null ? "" : s.trim();
    }

    public static int checkinResetHour() {
        return clamp(safeGet(CHECKIN_RESET_HOUR, 0), 0, 23);
    }

    public static boolean checkinAllowMakeup() {
        return safeGet(CHECKIN_ALLOW_MAKEUP, Boolean.TRUE);
    }

    public static long checkinMakeupCost() {
        return Math.max(0L, safeGet(CHECKIN_MAKEUP_COST, 800L));
    }

    public static int checkinMakeupMax() {
        return clamp(safeGet(CHECKIN_MAKEUP_MAX, 2), 0, 31);
    }

    public static long checkinStreakBonus() {
        return Math.max(0L, safeGet(CHECKIN_STREAK_BONUS, 20L));
    }

    public static long checkinStreakBonusMax() {
        return Math.max(0L, safeGet(CHECKIN_STREAK_BONUS_MAX, 200L));
    }

    // =============================== 工具 ===============================

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clampD(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 解析 "1,2,3" 形式的数字列表，最多取 maxCount 个；解析失败时返回 fallback。 */
    public static long[] parseLongList(String raw, int maxCount, long[] fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String[] parts = raw.split("[,;]");
        long[] out = new long[Math.min(maxCount, Math.max(1, parts.length))];
        int n = 0;
        for (String p : parts) {
            try {
                out[n++] = Long.parseLong(p.trim());
            } catch (NumberFormatException ignored) {
                // 跳过坏项
            }
            if (n >= out.length) {
                break;
            }
        }
        if (n == 0) {
            return fallback;
        }
        long[] trimmed = new long[n];
        System.arraycopy(out, 0, trimmed, 0, n);
        return trimmed;
    }

    private static <T> T safeGet(ModConfigSpec.ConfigValue<T> value, T fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            T got = value.get();
            return got == null ? fallback : got;
        } catch (Throwable t) {
            return fallback;
        }
    }
}
