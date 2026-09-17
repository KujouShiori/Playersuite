package dev.mcmods.playersuite.title;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 称号功能的玩家附件注册（拥有的称号 + 当前佩戴）。
 *
 * <p>与 {@code registry/ModAttachments} 分开注册，避免改共享文件；
 * 由 {@code PlayerSuiteMod} 构造方法调用 {@link #register(IEventBus)} 完成注册。
 */
public final class TitleAttachments {

    public static final DeferredRegister<AttachmentType<?>> TITLE_ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 玩家称号数据（不同步到客户端，界面内容经容器菜单下发）。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<TitleData>> TITLE =
            TITLE_ATTACHMENT_TYPES.register("title_data", () -> AttachmentType
                    .builder(TitleData::createDefault)
                    .serialize(TitleData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private TitleAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        TITLE_ATTACHMENT_TYPES.register(modEventBus);
    }
}
