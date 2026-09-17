package dev.mcmods.playersuite.registry;

import dev.mcmods.playersuite.PlayerSuiteMod;
import dev.mcmods.playersuite.warehouse.WarehouseData;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 玩家数据附件（Data Attachment）注册：个人仓库内容。
 *
 * <p>金币钱包在 {@code economy/WalletProvider}，其余功能（邮件/银行/商店/称号/签到）
 * 各自注册自己的附件，避免相互干扰。
 */
public final class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 个人仓库本体（等级 + 物品）。不同步到客户端，物品通过容器菜单正常同步。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<WarehouseData>> WAREHOUSE =
            ATTACHMENT_TYPES.register("warehouse", () -> AttachmentType
                    .builder(WarehouseData::createDefault)
                    .serialize(WarehouseData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private ModAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
