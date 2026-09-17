package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.config.SuiteConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.component.DataComponents;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 称号目录：把统一配置 {@code SuiteConfig.titleCatalog()} 的字符串列表解析成条目。
 *
 * <p>单条格式（字段用竖线分隔，前后空白会被去掉）：
 * <pre>
 *   称号id|显示名|价格[|图标物品id][|颜色]
 * </pre>
 * 兼容配置注释里的 4 字段写法 {@code id|显示名|价格|颜色}：第 4 个字段含冒号按图标解析，
 * 否则按颜色解析；{@code none} 或留空表示无色。图标物品缺省用 {@code minecraft:paper}。
 *
 * <p>容错原则：单条解析失败只跳过该条并打 WARN，绝不抛异常；物品 id 不认识退化为纸张、
 * 颜色名不认识退化为无色（都带 WARN）。解析结果按原始列表缓存，配置改动后自动重解析。
 */
public final class TitleCatalog {

    /** 称号 id 允许的字符（同时用于授予/撤销输入的清洗）。 */
    public static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");
    private static final int NAME_MAX = 64;

    /** 目录里的图标物品解析不到（或没写）时使用的兜底图标。 */
    public static final Item DEFAULT_ICON = Items.PAPER;

    /** 一条称号定义。 */
    public record Entry(String id, String name, long price, Item icon, ChatFormatting color) {

        /** 带配置颜色的显示名（配置文字一律 literal，不伪装成 translatable）。 */
        public Component displayName() {
            MutableComponent c = Component.literal(name);
            return color == null ? c : c.withStyle(color);
        }

        /** 生成一份只读展示用图标堆栈（带悬停名，物品本身不新注册）。 */
        public ItemStack iconStack() {
            ItemStack stack = new ItemStack(icon);
            stack.set(DataComponents.CUSTOM_NAME, displayName());
            return stack;
        }
    }

    private static List<String> lastRaw = null;
    private static List<Entry> lastParsed = List.of();

    private TitleCatalog() {
    }

    /** 当前配置解析出的目录（顺序即界面顺序）。任何异常都被吞掉并退化为空目录。 */
    public static synchronized List<Entry> entries() {
        List<String> raw = new ArrayList<>();
        for (String s : SuiteConfig.titleCatalog()) {
            raw.add(s == null ? "" : s);
        }
        if (lastRaw != null && lastRaw.equals(raw)) {
            return lastParsed;
        }
        List<Entry> out = parse(raw);
        lastRaw = raw;
        lastParsed = Collections.unmodifiableList(out);
        return lastParsed;
    }

    /** 按 id 查找目录条目；不存在返回 null。 */
    public static Entry byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        for (Entry entry : entries()) {
            if (entry.id().equals(id)) {
                return entry;
            }
        }
        return null;
    }

    /** 目录里第 index 个条目；越界返回 null。 */
    public static Entry byIndex(int index) {
        List<Entry> list = entries();
        return index >= 0 && index < list.size() ? list.get(index) : null;
    }

    /** 某 id 是否在目录中。 */
    public static boolean knows(String id) {
        return byId(id) != null;
    }

    /** 真正的逐条解析（独立出来方便单测/复用）。 */
    static List<Entry> parse(List<String> raw) {
        List<Entry> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String line = raw.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", -1);
            if (parts.length < 3 || parts.length > 5) {
                PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条字段数不对（应为 3~5 段）：{}", i + 1, line);
                continue;
            }
            String id = parts[0].trim();
            String name = parts[1].trim();
            String priceText = parts[2].trim();
            if (!ID_PATTERN.matcher(id).matches()) {
                PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条称号 id 非法（只允许字母数字 _ . -，1-32 位）：{}",
                        i + 1, line);
                continue;
            }
            if (name.isEmpty() || name.codePointCount(0, name.length()) > NAME_MAX) {
                PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条显示名为空或过长（上限 {} 字符）：{}",
                        i + 1, NAME_MAX, line);
                continue;
            }
            long price;
            try {
                price = Long.parseLong(priceText);
            } catch (NumberFormatException e) {
                PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条价格不是整数：{}", i + 1, line);
                continue;
            }
            if (price < 0L) {
                PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条价格为负（0 表示免费领取）：{}", i + 1, line);
                continue;
            }
            if (!seen.add(id)) {
                PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条称号 id 重复，已忽略：{}", i + 1, line);
                continue;
            }
            Item icon = DEFAULT_ICON;
            ChatFormatting color = null;
            String iconText = null;
            String colorText = null;
            if (parts.length == 4) {
                String tail = parts[3].trim();
                if (tail.contains(":")) {
                    iconText = tail;
                } else {
                    colorText = tail;
                }
            } else if (parts.length == 5) {
                iconText = parts[3].trim();
                colorText = parts[4].trim();
            }
            if (iconText != null && !iconText.isEmpty()) {
                icon = resolveItem(iconText, i + 1, line);
            }
            if (colorText != null && !colorText.isEmpty() && !colorText.equalsIgnoreCase("none")) {
                color = ChatFormatting.getByName(colorText.toLowerCase(Locale.ROOT));
                if (color == null) {
                    PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条颜色名不认识，按无色处理（原值 {}）：{}",
                            i + 1, colorText, line);
                } else if (!color.isColor()) {
                    PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条不是颜色格式（按无色处理，原值 {}）：{}",
                            i + 1, colorText, line);
                    color = null;
                }
            }
            out.add(new Entry(id, name, price, icon, color));
        }
        return out;
    }

    private static Item resolveItem(String text, int lineNo, String line) {
        try {
            ResourceLocation rl = ResourceLocation.parse(text);
            Optional<Item> found = BuiltInRegistries.ITEM.getOptional(rl);
            if (found.isPresent() && found.get() != Items.AIR) {
                return found.get();
            }
            PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条图标物品不存在，改用纸张（原值 {}）：{}",
                    lineNo, text, line);
        } catch (RuntimeException e) {
            PlayerSuiteMod.LOGGER.warn("[title] 目录第 {} 条图标物品 id 非法，改用纸张（原值 {}）：{}",
                    lineNo, text, line);
        }
        return DEFAULT_ICON;
    }
}
