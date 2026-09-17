package dev.mcmods.playersuite.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.market.MarketService;
import dev.mcmods.playersuite.ui.FeatureOpeners;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * 官方市场指令（本功能自带，独立文件，不改别人的指令类）：
 *
 * <pre>
 * /market                        打开官方市场界面
 * /market open                   同上
 * /market reload                 重读配置目录并清理失效条目状态（需 adminPermission，默认 3）
 * /market stock &lt;条目id&gt;          查看某条目库存（需 adminPermission）
 * /market stock &lt;条目id&gt; &lt;数量&gt;   设置某条目剩余库存，-1 = 不限（需 adminPermission）
 * </pre>
 *
 * <p>指令名固定为 {@code market}：{@code SuiteConfig} 里没有市场指令名键（不改共享配置，
 * 后续可新增 {@code marketCommandRoot} 配置项）。
 *
 * <p>与 {@code SuiteCommand} 的 {@code /market} 快捷指令共存：若根节点 {@code /market}
 * 已存在，只把 {@code reload}/{@code stock} 这些<b>还没有的同名子节点</b>搬进去
 * （已存在的 {@code open} 保持原样，行为等价），避免 Brigadier 同名节点冲突异常。
 */
public final class MarketCommand {
    /** 固定指令名（后续可把 marketCommandRoot 加进 SuiteConfig 改成可配置）。 */
    public static final String ROOT = "market";

    private static final SimpleCommandExceptionType MISSING_ENTRY = new SimpleCommandExceptionType(
            Component.translatable("playersuite.market.command.noEntry"));
    private static final SimpleCommandExceptionType FEATURE_OFF = new SimpleCommandExceptionType(
            Component.translatable("playersuite.market.command.featureOff"));
    /** 库存下限：-1 = 不限。 */
    private static final int STOCK_MIN = -1;
    private static final int STOCK_MAX = 999999;

    private MarketCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(ROOT)
                .executes(MarketCommand::open)
                .then(Commands.literal("open")
                        .executes(MarketCommand::open))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(SuiteConfig.adminPermission()))
                        .executes(MarketCommand::reload))
                .then(Commands.literal("stock")
                        .requires(src -> src.hasPermission(SuiteConfig.adminPermission()))
                        // 必须用原版已注册的参数类型：自定义 ArgumentType 如果没有向 ArgumentSerializer
                        // 注册，服务端在同步指令树（例如 /op 之后）时会抛
                        // “Unrecognized argument type”，导致该玩家彻底失去指令补全。
                        .then(Commands.argument("entry", StringArgumentType.string())
                                .suggests((source, builder) ->
                                        SharedSuggestionProvider.suggest(MarketService.entryIds(), builder))
                                .executes(MarketCommand::showStock)
                                .then(Commands.argument("amount",
                                                IntegerArgumentType.integer(STOCK_MIN, STOCK_MAX))
                                        .executes(MarketCommand::setStock))));

        CommandNode<CommandSourceStack> existing = dispatcher.getRoot().getChild(ROOT);
        if (existing == null) {
            dispatcher.register(root);
            return;
        }
        // SuiteCommand 已经注册过 /market 快捷指令（registerShortcutCommands=true）：
        // 先把我的子树建到一个临时 dispatcher 上，再把「还不存在」的子节点搬进真实树，
        // 这样既保留原有 open/<玩家> 子节点，也不会触发 Brigadier 的同名节点冲突异常。
        CommandDispatcher<CommandSourceStack> temp = new CommandDispatcher<>();
        CommandNode<CommandSourceStack> built = temp.register(root);
        for (CommandNode<CommandSourceStack> child : built.getChildren()) {
            if (existing.getChild(child.getName()) == null) {
                existing.addChild(child);
            }
        }
    }

    // ---------------------------------------------------------------- 执行

    private static int open(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!SuiteConfig.featureEnabled(FeatureOpeners.MARKET)) {
            throw FEATURE_OFF.create();
        }
        MarketService.open(player, player, 0);
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        if (!ctx.getSource().hasPermission(SuiteConfig.adminPermission())) {
            ctx.getSource().sendFailure(Component.translatable("playersuite.msg.noPermission",
                    SuiteConfig.adminPermission()));
            return 0;
        }
        int removed = MarketService.reload(ctx.getSource().getServer());
        int total = MarketService.entryIds().size();
        ctx.getSource().sendSuccess(() -> Component.translatable("playersuite.market.command.reload",
                total, removed), true);
        return 1;
    }

    private static int showStock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!ctx.getSource().hasPermission(SuiteConfig.adminPermission())) {
            ctx.getSource().sendFailure(Component.translatable("playersuite.msg.noPermission",
                    SuiteConfig.adminPermission()));
            return 0;
        }
        String id = ctx.getArgument("entry", String.class);
        int[] now = MarketService.stockOf(ctx.getSource().getServer(), id);
        if (now == null) {
            throw MISSING_ENTRY.create();
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("playersuite.market.command.stockShow",
                id, now[0] < 0 ? "∞" : String.valueOf(now[0]), now[1] < 0 ? "∞" : String.valueOf(now[1])), false);
        return 1;
    }

    private static int setStock(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        if (!ctx.getSource().hasPermission(SuiteConfig.adminPermission())) {
            ctx.getSource().sendFailure(Component.translatable("playersuite.msg.noPermission",
                    SuiteConfig.adminPermission()));
            return 0;
        }
        String id = ctx.getArgument("entry", String.class);
        int amount = IntegerArgumentType.getInteger(ctx, "amount");
        Integer result = MarketService.setStockByCommand(ctx.getSource().getServer(), id, amount);
        if (result == null) {
            throw MISSING_ENTRY.create();
        }
        boolean broadcast = ctx.getSource().getPlayer() == null;
        ctx.getSource().sendSuccess(() -> Component.translatable("playersuite.market.command.stockSet",
                id, result < 0 ? "∞" : String.valueOf(result)), broadcast);
        return 1;
    }
}
