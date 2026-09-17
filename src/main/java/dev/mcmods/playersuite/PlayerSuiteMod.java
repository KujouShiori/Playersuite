package dev.mcmods.playersuite;

import com.mojang.logging.LogUtils;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.bank.BankAttachments;
import dev.mcmods.playersuite.bank.BankMenus;
import dev.mcmods.playersuite.checkin.CheckinAttachments;
import dev.mcmods.playersuite.checkin.CheckinMenus;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.mail.MailAttachments;
import dev.mcmods.playersuite.mail.MailMenus;
import dev.mcmods.playersuite.market.MarketMenus;
import dev.mcmods.playersuite.registry.ModAttachments;
import dev.mcmods.playersuite.shop.ShopAttachments;
import dev.mcmods.playersuite.shop.ShopMenus;
import dev.mcmods.playersuite.title.TitleAttachments;
import dev.mcmods.playersuite.title.TitleMenus;
import dev.mcmods.playersuite.registry.ModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

/**
 * 玩家服务套件（Player Suite）mod 入口：一个 jar 内集成七项玩家功能。
 *
 * <ul>
 *     <li>个人仓库：服务端持久化的私人存储空间，可花金币扩充；</li>
 *     <li>邮件：玩家私信 + 附件领取，支持全服公告；</li>
 *     <li>银行：存取款、每日利息、手续费与收支记录；</li>
 *     <li>玩家商店：玩家自助上架出售，成交扣税，离线收入待领取；</li>
 *     <li>官方市场：服务端定价的固定目录，买/卖双向、库存与补货（设计参考 TradeSquares）；</li>
 *     <li>玩家称号：金币购买称号，可装备并注入聊天前缀；</li>
 *     <li>每日签到：周期奖励、连续签到加成与补签。</li>
 * </ul>
 *
 * <p>统一入口：{@code /ps}（指令名可在配置里改）。所有设置集中在唯一配置文件
 * {@code config/playersuite-common.toml}；金钱统一走 {@link Economy}；
 * 玩家数据统一使用 NeoForge 数据附件（跟随 playerdata 持久化，死亡不掉）。
 *
 * <p>需要对接其它经济系统时，在下面的构造方法里调用
 * {@code Economy.bind(你的实现)} 即可，七个功能会一起切换。
 */
@Mod(PlayerSuiteMod.MODID)
public final class PlayerSuiteMod {
    public static final String MODID = "playersuite";
    public static final Logger LOGGER = LogUtils.getLogger();

    public PlayerSuiteMod(IEventBus modEventBus, ModContainer container) {
        // 统一配置文件（COMMON：指令名与功能开关必须在指令树构建前就绪）
        container.registerConfig(ModConfig.Type.COMMON, SuiteConfig.SPEC);

        // 注册表：经济钱包附件 + 仓库附件 + 容器菜单类型（各功能自带自己的注册文件）
        Economy.register(modEventBus);
        ModAttachments.register(modEventBus);
        ModMenus.register(modEventBus);
        MailAttachments.register(modEventBus);
        MailMenus.register(modEventBus);
        BankAttachments.register(modEventBus);
        BankMenus.register(modEventBus);
        ShopAttachments.register(modEventBus);
        ShopMenus.register(modEventBus);
        TitleAttachments.register(modEventBus);
        TitleMenus.register(modEventBus);
        CheckinAttachments.register(modEventBus);
        CheckinMenus.register(modEventBus);
        MarketMenus.register(modEventBus);

        LOGGER.info("{} 已加载；服务端启动完成后会输出实际生效的配置与指令名", MODID);
    }
}
