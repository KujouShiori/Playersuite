package dev.mcmods.playersuite.shop;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 玩家商店的附件注册：店名 + 货架快照 + 收入统计。
 *
 * <p>与 {@code registry/ModAttachments} 同款写法，但独立注册，避免修改共享文件。
 * 真实托管物品在 SavedData（{@link ShopStorage}），附件丢失也不会丢物品。
 */
public final class ShopAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 玩家商店数据（不同步到客户端，物品通过容器菜单正常同步）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ShopData>> SHOP_DATA =
            ATTACHMENT_TYPES.register("shop_data", () -> AttachmentType
                    .builder(() -> new ShopData())   // 必须写 lambda，避免与 Class<M> 版本的重载歧义
                    .serialize(ShopData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private ShopAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        ATTACHMENT_TYPES.register(modEventBus);
    }
}
