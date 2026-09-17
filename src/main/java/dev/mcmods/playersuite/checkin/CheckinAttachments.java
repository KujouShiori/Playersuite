package dev.mcmods.playersuite.checkin;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 签到附件注册：{@code playersuite:checkin}（最后签到日 / 连签天数 / 本周期已领掩码 /
 * 本周期补签次数 / 周期起始日）。
 *
 * <p>和 {@code bank/BankAttachments}、{@code mail/MailAttachments} 一样单独开一个注册类，
 * 避免多个功能互相改 {@code registry/ModAttachments.java}。
 * 模组构造方法里只需调用一次 {@link #register(IEventBus)}。
 *
 * <p>附件<b>不同步到客户端</b>：界面上的每一天状态由 {@link CheckinMenu} 的容器附加数据下发，
 * 物品图标走只读展示槽，因此不存在「客户端能改签到状态」的口子。
 */
public final class CheckinAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 玩家签到数据（只在逻辑服务端读写；死亡保留，跨服换档由 copyOnDeath 保证不丢连签）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<CheckinData>> CHECKIN =
            ATTACHMENT_TYPES.register("checkin", () -> AttachmentType
                    .builder(() -> new CheckinData())
                    .serialize(CheckinData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private CheckinAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
