package dev.mcmods.playersuite.bank;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 银行附件注册：{@code playersuite:bank}（本金 / 结息时间戳 / 流水 / 冻结标记）。
 *
 * <p>与 {@code registry/ModAttachments} 分开写，避免多个功能互相改同一个文件；
 * 模组构造方法里只需调用一次 {@link #register(IEventBus)}。
 */
public final class BankAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 银行账户数据。只在逻辑服务端读写，不下发客户端（界面通过容器附加数据同步）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<BankData>> BANK =
            ATTACHMENT_TYPES.register("bank", () -> AttachmentType
                    .builder(() -> new BankData())
                    .serialize(BankData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private BankAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
