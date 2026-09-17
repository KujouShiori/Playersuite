package dev.mcmods.playersuite.mail;

import dev.mcmods.playersuite.PlayerSuiteMod;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 邮件功能的玩家附件注册（收件箱数据）。
 *
 * <p>与 {@code registry/ModAttachments} 分开注册，避免改共享文件；
 * 由 {@code PlayerSuiteMod} 构造方法调用 {@link #register(IEventBus)} 完成注册。
 */
public final class MailAttachments {

    public static final DeferredRegister<AttachmentType<?>> MAIL_ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, PlayerSuiteMod.MODID);

    /** 玩家邮箱（收件箱 + 冷却 + 公告游标）。不同步到客户端，内容经容器菜单下发。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<MailData>> MAIL =
            MAIL_ATTACHMENT_TYPES.register("mail", () -> AttachmentType
                    .builder(MailData::createDefault)
                    .serialize(MailData.SERIALIZER)
                    .copyOnDeath()
                    .build());

    private MailAttachments() {
    }

    public static void register(IEventBus modEventBus) {
        MAIL_ATTACHMENT_TYPES.register(modEventBus);
    }
}
