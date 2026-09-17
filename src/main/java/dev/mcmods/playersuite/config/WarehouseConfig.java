package dev.mcmods.playersuite.config;

import java.util.ArrayList;
import java.util.List;

/**
 * 个人仓库的容量与价格算法。
 *
 * <p>配置项本身在 {@link SuiteConfig}（全模组唯一配置文件）里，这里只负责按配置算数：
 * 等级 -> 行数/格子数、等级 -> 下一级价格。所有读取都带兜底，配置坏掉也不会炸服。
 */
public final class WarehouseConfig {
    private WarehouseConfig() {
    }

    public static int initialRows() {
        return SuiteConfig.warehouseInitialRows();
    }

    public static int rowsPerUpgrade() {
        return SuiteConfig.warehouseRowsPerUpgrade();
    }

    public static int maxRows() {
        return Math.max(initialRows(), SuiteConfig.warehouseMaxRows());
    }

    public static int rowsPerPage() {
        return SuiteConfig.warehouseRowsPerPage();
    }

    /** 满级等级（从初始行数涨到最大行数需要的扩充次数）。 */
    public static int maxLevel() {
        int span = rowsPerUpgrade();
        return Math.max(0, (maxRows() - initialRows()) / span);
    }

    /** 某一等级对应的行数（自动夹在 [initialRows, maxRows]）。 */
    public static int rowsForLevel(int level) {
        int lvl = Math.max(0, level);
        long rows = (long) initialRows() + (long) lvl * rowsPerUpgrade();
        return (int) Math.min(maxRows(), Math.max(initialRows(), rows));
    }

    public static int capacityForLevel(int level) {
        return rowsForLevel(level) * 9;
    }

    public static int initialCapacity() {
        return capacityForLevel(0);
    }

    public static int maxCapacity() {
        return maxRows() * 9;
    }

    public static boolean isMaxLevel(int level) {
        return level >= maxLevel();
    }

    /** 从 level 扩充到 level+1 的价格；已满级返回 -1。 */
    public static int priceForLevel(int level) {
        if (isMaxLevel(level)) {
            return -1;
        }
        List<Long> table = priceTable();
        long price;
        if (!table.isEmpty()) {
            int index = (int) Math.min(Math.max(0, level), table.size() - 1L);
            price = table.get(index);
        } else {
            price = Math.round((double) SuiteConfig.warehouseBasePrice()
                    * Math.pow(SuiteConfig.warehousePriceGrowth(), Math.max(0, level)));
        }
        price = Math.min(SuiteConfig.warehouseMaxPrice(), price);
        int rounding = SuiteConfig.warehousePriceRounding();
        if (rounding > 1) {
            price = Math.round(price / (double) rounding) * (long) rounding;
        }
        if (price < 1L) {
            price = 1L;
        }
        return (int) Math.min((long) Integer.MAX_VALUE, price);
    }

    /** 解析手写价格表；空表表示使用公式。 */
    private static List<Long> priceTable() {
        List<Long> out = new ArrayList<>();
        for (long v : SuiteConfig.parseLongList(SuiteConfig.warehouseUpgradePrices(), 64, new long[0])) {
            out.add(Math.max(0L, v));
        }
        return out;
    }
}
