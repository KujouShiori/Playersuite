package dev.mcmods.playersuite.market;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 官方市场目录：把统一配置 {@code SuiteConfig.marketEntries()} 解析成内存条目（服务端权威、只读）。
 *
 * <p><b>条目格式（每行一条，字段用 "|" 分隔；解析失败只 WARN 并跳过，绝不抛异常）</b>：
 * <pre>
 * 7 字段：条目id|物品id|每份数量|每份买价|每份卖价|库存|分类
 * 6 字段：物品id|每份数量|每份买价|每份卖价|库存|分类      （条目id 自动取物品路径名）
 * 5 字段：物品id|每份数量|每份买价|每份卖价|分类          （库存取 marketDefaultStock()）
 * 4 字段：物品id|每份数量|每份买价|每份卖价              （库存取默认、分类留空）
 * </pre>
 * 语义约定（写进 README）：
 * <ul>
 *     <li><b>份（件）</b>：一条商品的最小交易单位，一 = {@code 每份数量} 个物品；
 *         界面上的「买 1 / 买 16 / 买 64」都是<em>份数</em>；</li>
 *     <li><b>每份买价</b>：玩家向市场买一份的价格；{@code <=0} 表示该条目不出售；</li>
 *     <li><b>每份卖价</b>市场收购一份的价格；{@code <=0} 表示不回收；</li>
 *     <li><b>库存</b>：以「份」计数，{@code -1} = 不限；缺省时取 {@code marketDefaultStock()}；</li>
 *     <li><b>分类</b>：留空归入 {@link #UNCATEGORIZED}；分类按钮顺序 = {@code marketCategories()} 顺序，
 *         配置里没有但条目里出现的分类按字母序追加在后面。</li>
 * </ul>
 * 条目 id 要求 ASCII（供 {@code /market stock <条目id>} 使用），字符集 {@code [A-Za-z0-9_.-]{1,32}}。
 *
 * <p>缓存以「配置列表内容是否变化」为失效条件，改完 toml 后 {@link #invalidate()} 或
 * {@link MarketService#reload} 立即生效。
 */
public final class MarketCatalog {
    /** 分类留空时使用的分类名。 */
    public static final String UNCATEGORIZED = "未分类";
    /** 「全部」按钮的名字（界面上的第 0 个分类）。 */
    public static final String CATEGORY_ALL = "*all*";
    /** 条目 id 合法字符集。 */
    public static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");

    /** 单条商品（不可变快照）。 */
    public record Entry(int index, String id, ResourceLocation itemId, Item item, int unit,
                        long buyPrice, long sellPrice, int stock, String category) {

        /** 库存是否不限（{@code -1}）。 */
        public boolean unlimitedStock() {
            return stock < 0;
        }

        /** 是否允许玩家购买。 */
        public boolean buyable() {
            return buyPrice > 0L;
        }

        /** 是否允许玩家出售给市场。 */
        public boolean sellable() {
            return sellPrice > 0L;
        }
    }

    /** 价格/数量的硬上限，防止 long 乘法溢出（买 999 份也不会溢出）。 */
    static final long PRICE_CAP = Long.MAX_VALUE / 4096L;

    private static final Object LOCK = new Object();

    private static volatile List<Entry> cacheEntries = List.of();
    private static volatile List<String> cacheCategories = List.of();
    private static volatile List<String> sourceSnapshot = null;
    private static volatile List<String> categorySnapshot = null;
    private static final Map<String, Entry> BY_ID = new LinkedHashMap<>();

    private MarketCatalog() {
    }

    // ---------------------------------------------------------------- 读取

    /** 全部条目（目录顺序）。配置改坏时自动重建。 */
    public static List<Entry> entries() {
        ensure();
        return cacheEntries;
    }

    /** 分类顺序（含「未分类」等实际出现但配置里没写的分类）。 */
    public static List<String> categories() {
        ensure();
        return cacheCategories;
    }

    /** 按条目 id 查（不存在返回 null）。 */
    public static Entry byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        ensure();
        return BY_ID.get(id);
    }

    /** 第 globalIndex 条（越界返回 null）。 */
    public static Entry at(int globalIndex) {
        List<Entry> list = entries();
        return globalIndex < 0 || globalIndex >= list.size() ? null : list.get(globalIndex);
    }

    /** 某分类下的条目（{@code null} 或 {@link #CATEGORY_ALL} = 全部）。 */
    public static List<Entry> inCategory(String category) {
        List<Entry> all = entries();
        if (category == null || category.isEmpty() || CATEGORY_ALL.equals(category)) {
            return all;
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : all) {
            if (category.equals(e.category())) {
                out.add(e);
            }
        }
        return out;
    }

    /** 全部有效条目 id（给 {@link MarketState#purgeStaleState} 用）。 */
    public static Set<String> validIds() {
        ensure();
        return new LinkedHashSet<>(BY_ID.keySet());
    }

    /** 强制下次读取时重新解析（改配置 / /market reload）。 */
    public static void invalidate() {
        synchronized (LOCK) {
            sourceSnapshot = null;
            categorySnapshot = null;
        }
    }

    // ---------------------------------------------------------------- 解析

    private static void ensure() {
        List<? extends String> rawEntries = readConfig(SuiteConfig::marketEntries);
        List<? extends String> rawCategories = readConfig(SuiteConfig::marketCategories);
        if (sourceSnapshot != null && categorySnapshot != null
                && sourceSnapshot.equals(copyOf(rawEntries)) && categorySnapshot.equals(copyOf(rawCategories))) {
            return;
        }
        synchronized (LOCK) {
            List<String> entriesCopy = copyOf(readConfig(SuiteConfig::marketEntries));
            List<String> categoriesCopy = copyOf(readConfig(SuiteConfig::marketCategories));
            if (entriesCopy.equals(sourceSnapshot) && categoriesCopy.equals(categorySnapshot)) {
                return;
            }
            rebuild(entriesCopy, categoriesCopy);
            sourceSnapshot = entriesCopy;
            categorySnapshot = categoriesCopy;
        }
    }

    private static List<? extends String> readConfig(java.util.function.Supplier<List<? extends String>> getter) {
        try {
            List<? extends String> raw = getter.get();
            return raw == null ? List.of() : raw;
        } catch (Throwable t) {
            PlayerSuiteMod.LOGGER.warn("[market] 读取市场配置失败，本次按空目录处理：{}", t.toString());
            return List.of();
        }
    }

    private static List<String> copyOf(List<? extends String> raw) {
        List<String> out = new ArrayList<>(raw.size());
        for (String s : raw) {
            out.add(s == null ? "" : s);
        }
        return out;
    }

    private static void rebuild(List<String> rawEntries, List<String> rawCategories) {
        List<Entry> entries = new ArrayList<>();
        Map<String, Entry> byId = new LinkedHashMap<>();
        Set<String> usedIds = new LinkedHashSet<>();
        int skipped = 0;
        for (int i = 0; i < rawEntries.size(); i++) {
            String line = rawEntries.get(i);
            Entry entry = parse(i + 1, line, usedIds);
            if (entry == null) {
                skipped++;
                continue;
            }
            if (byId.containsKey(entry.id())) {
                PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行条目 id「{}」重复，已跳过", i + 1, entry.id());
                skipped++;
                continue;
            }
            entries.add(entry);
            byId.put(entry.id(), entry);
        }

        // 分类顺序：配置顺序优先，条目里出现但配置没写的追加在后面（未分类固定最后）
        Set<String> present = new LinkedHashSet<>();
        for (Entry e : entries) {
            present.add(e.category());
        }
        List<String> categories = new ArrayList<>();
        for (String configured : rawCategories) {
            String name = configured == null ? "" : configured.trim();
            if (name.isEmpty() || name.equals(UNCATEGORIZED)) {
                continue;
            }
            if (present.remove(name) && !categories.contains(name)) {
                categories.add(name);
            }
        }
        List<String> extra = new ArrayList<>(present);
        extra.remove(UNCATEGORIZED);
        extra.sort(String::compareTo);
        categories.addAll(extra);
        if (present.contains(UNCATEGORIZED)) {
            categories.add(UNCATEGORIZED);
        }

        synchronized (LOCK) {
            cacheEntries = List.copyOf(entries);
            cacheCategories = List.copyOf(categories);
            BY_ID.clear();
            BY_ID.putAll(byId);
        }
        PlayerSuiteMod.LOGGER.info("[market] 目录解析完成：{} 条有效，{} 条被跳过，{} 个分类",
                entries.size(), skipped, categories.size());
    }

    /** 逐条容错解析；不合法返回 null（已 WARN）。 */
    private static Entry parse(int lineNo, String raw, Set<String> usedIds) {
        if (raw == null) {
            return null;
        }
        String line = raw.trim();
        if (line.isEmpty() || line.startsWith("#")) {
            return null;
        }
        String[] parts = line.split("\\|", -1);
        String id;
        String itemField;
        String unitField;
        String buyField;
        String sellField;
        String stockField;
        String categoryField;
        switch (parts.length) {
            case 7 -> {
                id = parts[0].trim();
                itemField = parts[1].trim();
                unitField = parts[2].trim();
                buyField = parts[3].trim();
                sellField = parts[4].trim();
                stockField = parts[5].trim();
                categoryField = parts[6].trim();
            }
            case 6 -> {
                itemField = parts[0].trim();
                unitField = parts[1].trim();
                buyField = parts[2].trim();
                sellField = parts[3].trim();
                stockField = parts[4].trim();
                categoryField = parts[5].trim();
                id = deriveId(itemField);
            }
            case 5 -> {
                itemField = parts[0].trim();
                unitField = parts[1].trim();
                buyField = parts[2].trim();
                sellField = parts[3].trim();
                categoryField = parts[4].trim();
                stockField = "";
                id = deriveId(itemField);
            }
            case 4 -> {
                itemField = parts[0].trim();
                unitField = parts[1].trim();
                buyField = parts[2].trim();
                sellField = parts[3].trim();
                categoryField = "";
                stockField = "";
                id = deriveId(itemField);
            }
            default -> {
                PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行字段数 {} 不是 4/5/6/7，已跳过：{}",
                        lineNo, parts.length, line);
                return null;
            }
        }

        if (id.isEmpty() || !ID_PATTERN.matcher(id).matches()) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行条目 id「{}」不合法（需 {}），已跳过",
                    lineNo, id, ID_PATTERN.pattern());
            return null;
        }
        if (ResourceLocation.tryParse(itemField) == null) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行物品 id「{}」不是合法 ResourceLocation，已跳过", lineNo, itemField);
            return null;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(itemField);
        if (itemId == null) {
            return null;
        }
        Optional<Item> found;
        try {
            found = BuiltInRegistries.ITEM.getOptional(itemId);
        } catch (RuntimeException e) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行查询物品 {} 失败：{}", lineNo, itemId, e.toString());
            return null;
        }
        if (found.isEmpty() || found.get() == Items.AIR) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行物品 {} 不存在或为空气，已跳过", lineNo, itemId);
            return null;
        }
        Item item = found.get();

        Integer unit = parseInt(unitField, 1, 9999);
        if (unit == null) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行「每份数量」=「{}」不在 1~9999，已跳过", lineNo, unitField);
            return null;
        }
        Long buy = parsePrice(buyField);
        Long sell = parsePrice(sellField);
        if (buy == null || sell == null) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行买价/卖价「{}」/「{}」不是合法非负整数，已跳过",
                    lineNo, buyField, sellField);
            return null;
        }
        int stock;
        if (stockField.isEmpty()) {
            stock = defaultStock();
        } else {
            Integer parsed = parseInt(stockField, -1, 999999);
            if (parsed == null) {
                PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行库存「{}」不在 -1~999999，已跳过", lineNo, stockField);
                return null;
            }
            stock = parsed;
        }
        String category = categoryField.isEmpty() ? UNCATEGORIZED : categoryField;
        if (usedIds.contains(id)) {
            PlayerSuiteMod.LOGGER.warn("[market] 第 {} 行条目 id「{}」与前面的行重复，已跳过", lineNo, id);
            return null;
        }
        usedIds.add(id);
        return new Entry(0, id, itemId, item, unit, buy, sell, stock, category);
    }

    /** 6/5/4 字段格式下从物品 id 推条目 id（取路径名，冒号换成点）。 */
    private static String deriveId(String itemField) {
        String s = itemField.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        String path = colon >= 0 ? s.substring(colon + 1) : s;
        String cleaned = path.replaceAll("[^A-Za-z0-9_.\\-]", "_");
        if (cleaned.isEmpty()) {
            return "";
        }
        return cleaned.length() > 32 ? cleaned.substring(0, 32) : cleaned;
    }

    private static int defaultStock() {
        try {
            return SuiteConfig.marketDefaultStock();
        } catch (Throwable t) {
            return 64;
        }
    }

    private static Long parsePrice(String field) {
        Integer intVal = null;
        String s = field == null ? "" : field.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            long v = Long.parseLong(s);
            if (v < 0L) {
                return null;
            }
            return Math.min(v, PRICE_CAP);
        } catch (NumberFormatException e) {
            // 允许写成 1.0 这种手误：按 long 截断
            try {
                double d = Double.parseDouble(s);
                if (d < 0D || !Double.isFinite(d)) {
                    return null;
                }
                return (long) Math.min(d, (double) PRICE_CAP);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    private static Integer parseInt(String field, int min, int max) {
        String s = field == null ? "" : field.trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            int v = Integer.parseInt(s);
            if (v < min || v > max) {
                return null;
            }
            return v;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
