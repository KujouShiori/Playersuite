package dev.mcmods.playersuite.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.mcmods.playersuite.config.SuiteConfig;
import dev.mcmods.playersuite.config.WarehouseConfig;
import dev.mcmods.playersuite.economy.Economy;
import dev.mcmods.playersuite.warehouse.WarehouseService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * /warehouse 指令族（主指令名与别名可在配置中修改）。
 *
 * <pre>
 * /warehouse open    [玩家]                        打开个人仓库（代开需 OP 2）
 * /warehouse upgrade [玩家]                        花费余额扩充仓库（代充需 OP 2）
 * /warehouse sort    [玩家]                        一键整理仓库
 * /warehouse info    [玩家]                        查看等级 / 容量 / 价格 / 余额
 * /warehouse coins   query|add|take|set [玩家]     内置金币钱包（add/take/set 需 OP 2）
 * /warehouse level   set &lt;等级&gt; [玩家]              强制设置仓库等级（需 OP 3）
 * </pre>
 */
public final class WarehouseCommand {
    /** 金币操作类型。 */
    private enum CoinAction {
        ADD, TAKE, SET
    }

    private static final SuggestionProvider<CommandSourceStack> SUGGEST_PLAYERS =
            (source, builder) -> SharedSuggestionProvider.suggest(
                    source.getSource().getServer().getPlayerList().getPlayers().stream()
                            .map(player -> player.getGameProfile().getName())
                            .toList(),
                    builder);

    /**
     * 等级参数的解析上限。真正的有效范围由配置决定，在指令执行时由
     * {@link #setLevel} 校验并给出中文提示（COMMAND 配置未加载时也不能让指令注册失败）。
     */
    private static final int LEVEL_PARSE_MAX = 64;

    private WarehouseCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        for (String name : commandNames()) {
            dispatcher.register(build(name));
        }
    }

    /** 本次会注册的指令名（含别名），已去重。 */
    public static List<String> commandNames() {
        List<String> names = new ArrayList<>();
        names.add(SuiteConfig.commandRoot());
        names.addAll(SuiteConfig.commandAliases());
        return names.stream().distinct().toList();
    }

    private static LiteralArgumentBuilder<CommandSourceStack> build(String name) {
        return Commands.literal(name)
                .executes(WarehouseCommand::help)

                .then(Commands.literal("open")
                        .executes(ctx -> open(ctx, senderOrThrow(ctx)))
                        .then(playerArgument().executes(ctx -> open(ctx, EntityArgument.getPlayer(ctx, "player")))))

                .then(Commands.literal("upgrade")
                        .executes(ctx -> upgrade(ctx, senderOrThrow(ctx)))
                        .then(playerArgument().executes(ctx -> upgrade(ctx, EntityArgument.getPlayer(ctx, "player")))))

                .then(Commands.literal("sort")
                        .executes(ctx -> sort(ctx, senderOrThrow(ctx)))
                        .then(playerArgument().executes(ctx -> sort(ctx, EntityArgument.getPlayer(ctx, "player")))))

                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx, senderOrThrow(ctx)))
                        .then(playerArgument().executes(ctx -> info(ctx, EntityArgument.getPlayer(ctx, "player")))))

                .then(Commands.literal("coins")
                        .then(Commands.literal("query")
                                .executes(ctx -> queryCoins(ctx, senderOrThrow(ctx)))
                                .then(playerArgument().executes(ctx -> queryCoins(ctx, EntityArgument.getPlayer(ctx, "player")))))
                        .then(coinCommand(CoinAction.ADD))
                        .then(coinCommand(CoinAction.TAKE))
                        .then(coinCommand(CoinAction.SET)))

                .then(Commands.literal("level")
                        .requires(src -> src.hasPermission(Commands.LEVEL_ADMINS))
                        .then(Commands.literal("set")
                                .then(Commands.argument("level", IntegerArgumentType.integer(0, LEVEL_PARSE_MAX))
                                        .executes(ctx -> setLevel(ctx, senderOrThrow(ctx)))
                                        .then(playerArgument()
                                                .executes(ctx -> setLevel(ctx, EntityArgument.getPlayer(ctx, "player")))))));
    }

    /** {@code [player]} 可选参数：只有 OP 2 能指定他人。 */
    private static RequiredArgumentBuilder<CommandSourceStack, ?> playerArgument() {
        return Commands.argument("player", EntityArgument.players())
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .suggests(SUGGEST_PLAYERS);
    }

    /** {@code coins add|take|set <amount> [player]}。 */
    private static LiteralArgumentBuilder<CommandSourceStack> coinCommand(CoinAction action) {
        return Commands.literal(action.name().toLowerCase(java.util.Locale.ROOT))
                .requires(src -> src.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.argument("amount", LongArgumentType.longArg(0L, Long.MAX_VALUE))
                        .executes(ctx -> coins(ctx, senderOrThrow(ctx), action))
                        .then(playerArgument()
                                .executes(ctx -> coins(ctx, EntityArgument.getPlayer(ctx, "player"), action))));
    }

    // ================================================================ 具体动作

    private static int help(CommandContext<CommandSourceStack> ctx) {
        String root = SuiteConfig.commandRoot();
        ctx.getSource().sendSuccess(() -> Component.translatable("playersuite.help.header"), false);
        usage(ctx, "/" + root + " open [player]", "playersuite.help.open");
        usage(ctx, "/" + root + " upgrade [player]", "playersuite.help.upgrade");
        usage(ctx, "/" + root + " sort [player]", "playersuite.help.sort");
        usage(ctx, "/" + root + " info [player]", "playersuite.help.info");
        usage(ctx, "/" + root + " coins query|add|take|set [amount] [player]", "playersuite.help.coins");
        usage(ctx, "/" + root + " level set <level> [player]", "playersuite.help.level");
        return 1;
    }

    private static void usage(CommandContext<CommandSourceStack> ctx, String syntax, String descriptionKey) {
        ctx.getSource().sendSuccess(() -> Component.literal(syntax + "  -  ")
                .append(Component.translatable(descriptionKey)), false);
    }

    private static int open(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ServerPlayer viewer = sender(ctx);
        if (viewer == null) {
            return 0;
        }
        if (!WarehouseService.isSamePlayer(viewer, target) && !viewer.hasPermissions(Commands.LEVEL_GAMEMASTERS)) {
            fail(ctx, Component.translatable("playersuite.msg.noPermission", Commands.LEVEL_GAMEMASTERS));
            return 0;
        }
        WarehouseService.open(viewer, target, 0);
        if (!WarehouseService.isSamePlayer(viewer, target)) {
            // 提醒仓库主人：有人正在查看他的仓库
            target.displayClientMessage(WarehouseService.infoLine(viewer, target), true);
        }
        return 1;
    }

    private static int upgrade(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ServerPlayer sender = sender(ctx);
        if (sender == null) {
            return 0;
        }
        // 由仓库主人 own 支付（管理员代办时同样扣目标余额，界面提示发给目标）
        boolean ok = WarehouseService.upgrade(target, target, 0);
        if (!WarehouseService.isSamePlayer(sender, target)) {
            ctx.getSource().sendSuccess(() -> WarehouseService.infoLine(sender, target), false);
        }
        return ok ? 1 : 0;
    }

    private static int sort(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ServerPlayer sender = sender(ctx);
        if (sender == null) {
            return 0;
        }
        WarehouseService.sort(sender, target);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ServerPlayer sender = sender(ctx);
        if (sender == null) {
            return 0;
        }
        ctx.getSource().sendSuccess(() -> WarehouseService.infoLine(sender, target), false);
        ctx.getSource().sendSuccess(WarehouseService::openLink, false);
        return 1;
    }

    private static int queryCoins(CommandContext<CommandSourceStack> ctx, ServerPlayer target) {
        ServerPlayer sender = sender(ctx);
        boolean self = sender != null && WarehouseService.isSamePlayer(sender, target);
        ctx.getSource().sendSuccess(() -> self
                        ? Component.translatable("playersuite.coins.query.self", Economy.label(Economy.balance(target)))
                        : Component.translatable("playersuite.coins.query.other",
                        target.getGameProfile().getName(), Economy.label(Economy.balance(target))),
                false);
        return 1;
    }

    private static int coins(CommandContext<CommandSourceStack> ctx, ServerPlayer target, CoinAction action) {
        long amount = LongArgumentType.getLong(ctx, "amount");
        long before = Economy.balance(target);
        long after;
        switch (action) {
            case ADD -> {
                Economy.deposit(target, amount, "admin_add");
                after = Economy.balance(target);
            }
            case TAKE -> {
                if (!Economy.withdraw(target, amount, "admin_take")) {
                    fail(ctx, Component.translatable("playersuite.coins.insufficient", Economy.label(before)));
                    return 0;
                }
                after = Economy.balance(target);
            }
            default -> {
                Economy.setBalance(target, amount);
                after = Economy.balance(target);
            }
        }
        long shownBefore = before;
        long shownAfter = after;
        ctx.getSource().sendSuccess(() -> switch (action) {
            case ADD -> Component.translatable("playersuite.coins.added", Economy.label(amount), Economy.label(shownAfter));
            case TAKE -> Component.translatable("playersuite.coins.taken", Economy.label(amount), Economy.label(shownAfter));
            default -> Component.translatable("playersuite.coins.set", Economy.label(shownAfter));
        }, true);
        if (shownBefore != shownAfter) {
            target.displayClientMessage(Component.translatable("playersuite.coins.query.self",
                    Economy.label(shownAfter)), true);
        }
        return 1;
    }

    private static int setLevel(CommandContext<CommandSourceStack> ctx, ServerPlayer target)
            throws CommandSyntaxException {
        int max = Math.max(0, WarehouseConfig.maxLevel());
        int level = IntegerArgumentType.getInteger(ctx, "level");
        if (level < 0 || level > max) {
            fail(ctx, Component.translatable("playersuite.level.invalid", 0, max));
            return 0;
        }
        WarehouseService.setLevel(target, level);
        return 1;
    }

    // ================================================================ 工具

    private static ServerPlayer sender(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player == null) {
            fail(ctx, Component.translatable("playersuite.error.playerOnly"));
        }
        return player;
    }

    /** 用于 {@code [player]} 缺省时取发起者，非玩家来源直接报错。 */
    private static ServerPlayer senderOrThrow(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return ctx.getSource().getPlayerOrException();
    }

    private static void fail(CommandContext<CommandSourceStack> ctx, Component message) {
        ctx.getSource().sendFailure(message);
    }
}
