# Player Suite · 玩家服务套件

一个 jar 集齐七项玩家功能的 NeoForge 模组：**个人仓库、邮件、银行、玩家商店、官方市场、玩家称号、每日签到**。
所有功能由**一条指令 `/ps` 打开同一个总入口界面**，点击按钮进入子页面；自带经济系统（可一行代码换成外部经济）；
全部设置集中在**一个配置文件**里；每个页面都提供 OP 管理入口。

| 项目 | 值 |
|---|---|
| Minecraft | 1.21.1 |
| 加载器 | NeoForge 21.1.x（本项目以 `21.1.230` 构建验证） |
| Java | 21 |
| 客户端 / 服务端 | **两端都需要**安装同一个 jar |
| 模组 id | `playersuite` |
| 主指令 | `/ps`（默认别名 `suite`、`fw`） |
| 语言 | 简体中文 + English（跟随客户端语言，491 条键两侧对齐） |
| 许可 | MIT |

---

## 功能一览

| 功能 | 说明 | 配置段 |
|---|---|---|
| **个人仓库** | 服务端持久化的私人空间，花金币扩充容量；一键整理、分页浏览、死亡保留 | `[warehouse]` |
| **邮件** | 玩家私信、附件领取、全服公告；收件人离线时由服务端中转，上线自动投递 | `[mail]` |
| **银行** | 存取款、每日利息、取款手续费与冷却、收支流水、管理员冻结账户 | `[bank]` |
| **玩家商店** | 玩家自助上架（物品真实托管到服务端）、逛店购买、成交扣税、卖家离线时收入进「待领取」 | `[shop]` |
| **官方市场** | 服务端定价目录，**买/卖双向**、分类页、库存与定时补货、单人每日限购 | `[market]` |
| **玩家称号** | 金币购买称号，装备后自动注入聊天前缀；管理员可授予/撤销/强制装备 | `[title]` |
| **每日签到** | 周期奖励、连续签到加成、付费补签、金币 + 物品双奖励 | `[checkin]` |

界面全部用矩形绘制，**不依赖任何材质或资源包**，因此不会出现缺材质黑块，也不会和其它资源包冲突。

## 快速开始

1. 把 jar 放进服务端 `mods/`，同时发给玩家放进客户端 `mods/`。
2. 启动服务器，自动生成 `config/playersuite-common.toml`。
3. 玩家进服后输入 `/ps`（或点击进服提示里的链接）打开总入口。

### 玩家指令

| 指令 | 作用 |
|---|---|
| `/ps` | 打开功能总入口（余额、待领取收入、七个功能按钮） |
| `/ps warehouse\|mail\|bank\|shop\|market\|title\|checkin` | 直接进入某个功能页 |
| `/warehouse`（别名 `wh`、`pw`、`grc`） | 仓库指令族：`open`、`upgrade`、`sort`、`info`、`coins`、`level set` |
| `/market` | 打开官方市场；`/market reload`（OP3）重读目录；`/market stock <条目id> [数量]`（OP3）查看/设置库存 |
| `/mail` `/bank` `/shop` `/title` `/checkin` | 快捷指令，等价于 `/ps <功能>`；用 `registerShortcutCommands = false` 可全部关闭 |
| `/ps money query\|add\|take\|set <金额> [玩家]` | 统一钱包查询与调整（OP2） |

### 权限约定

| 等级 | 配置项（默认） | 能做什么 |
|---|---|---|
| OP2 | `managePermission = 2` | 查看/代开他人页面、执行页面内的管理按钮、调整他人余额 |
| OP3 | `adminPermission = 3` | 破坏性操作：清空收件箱、设定/清零他人本金、授予/撤销称号、重置签到、改市场目录数据 |

`allowManageOthers = false` 可以完全禁止代开他人页面。改指令名或功能开关需要**重启服务器**（指令树只在启动时构建）；指令名必须是 ASCII。

## 配置文件：`config/playersuite-common.toml`

只有这一个文件，九个段落（`[general] [economy] [warehouse] [bank] [mail] [shop] [market] [title] [checkin]`）。
类型是 COMMON，**以服务端为准**：页面按钮是否可用由服务端下发的位掩码决定，客户端本地配置不会造成功能差异。

常改的几项：

```toml
[general]
    root = "ps"
    aliases = ["suite", "fw"]
    enabledFeatures = ["warehouse", "mail", "bank", "shop", "market", "title", "checkin"]   # 删掉即关闭该功能

[economy]
    currencySymbol = "金"
    initialBalance = 500          # 首次进服赠送，0 = 不赠送
    maxBalance = 1000000000

[warehouse]
    initialRows = 2               # 容量 = 行数 × 9 格
    rowsPerUpgrade = 1
    maxRows = 6
    basePrice = 100
    priceGrowth = 1.6
    priceRounding = 5
    upgradePrices = ""            # 直接写死每级价格（逗号分隔），优先于上面的成长公式
```

### 官方市场目录 `market.entries`

每条一个字符串，`|` 分隔，支持 4/5/6/7 字段并**逐条容错**（写错只跳过该条并打 WARN，不影响其它条目、不会崩服）：

```toml
entries = [
    "minecraft:diamond|1|1500|1000|64|矿物",          # 物品|每组数量|买价|卖价|库存|分类
    "legendary_shard|minecraft:nether_star|1|99999|0|8|稀有",  # 条目id|物品|每组|买价|卖价|库存|分类
]
categories = ["方块", "矿物", "食物", "刷怪掉落", "农资", "杂物"]   # 顺序即界面分类按钮顺序
```

- 买价 = 玩家买入单价；卖价 = 市场收购单价（`0` = 不收）。
- 库存 `-1` = 不限；`stockEnabled = false` 时全部按不限处理；`restockHours` 为自动回满间隔；`dailyBuyLimit` 为单人单日限购件数（`0` = 不限）。
- 6 字段写法的条目 id 会自动取物品路径（`minecraft:diamond` → `diamond`），用于 `/market stock` 与补全。
- 改完执行 `/market reload` 或重启生效（注意 reload 会把界面里临时改的价格还原成配置值）。

### 称号目录 `title.catalog`

```toml
catalog = ["rich|富甲一方|5000|minecraft:gold_ingot|gold"]   # 称号id|显示名|价格[|图标物品][|颜色]
chatFormat = "[%s]"     # 支持 %s / %title%、%player%、%text%、%level%；填 none 表示不注入
```

价格为 `0` 表示免费领取；`chatFormat` 被改坏时自动退化为 `[称号] 玩家: 原文`。

### 签到奖励 `checkin.*`

```toml
cycleDays = 7
coinRewards = "200,300,400,500,600,800,1200"          # 按天取，天数不够时用最后一个值
itemRewards = "3:minecraft:diamond|1;7:minecraft:iron_ingot|8"   # 天:物品|数量;...
resetHour = 0                                          # 一天从几点开始
streakBonus = 20
streakBonusMax = 200                                   # 实际到手 = 当天基础 + min(连签天数 × streakBonus, streakBonusMax)
allowMakeup = true
makeupCost = 800
makeupMax = 2
```

物品奖励在背包放不下时**整项不发、当天保持未签**（金币也不入账），避免复制或丢失。

## 经济系统

- 内置钱包：金额一律 `long`，存在玩家数据附件里，带可配置的收支流水。
- 所有功能只通过 `economy/Economy.java` 读写金钱：`balance / withdraw / deposit / setBalance / transfer / payOffline / claimPending`。
- 离线收入：卖家离线时走 `payOffline`，进「待领取」，玩家上线后在总入口点「一键领取」入账。
- **对接外部经济**（EssentialsX 金币、Magic Coins 等）：实现 `CurrencyProvider` 并在模组构造方法里绑定一次，七个功能会一起切换：

```java
public final class MyCurrency implements CurrencyProvider {
    @Override public String id() { return "mymod:money"; }
    @Override public long balance(ServerPlayer p) { /* 读外部经济 */ }
    @Override public boolean withdraw(ServerPlayer p, long amount) { /* 扣款，失败返回 false */ }
    @Override public void deposit(ServerPlayer p, long amount) { /* 加款 */ }
    @Override public Component format(long amount) { /* 界面显示 */ }
}

// PlayerSuiteMod 构造方法中：
Economy.bind(new MyCurrency());
```

## 数据存在哪里

| 类型 | 位置 | 内容 |
|---|---|---|
| 玩家附件 | `world/players/<uuid>.dat` | `playersuite:wallet`、`playersuite:warehouse`、`playersuite:mail`、`playersuite:bank`、`playersuite:shop_data`、`playersuite:title_data`、`playersuite:checkin` |
| SavedData | `world/data/` | `playersuite_market.dat`、`playersuite_shop_storage.dat`、`playersuite_shop_browse.dat`、`playersuite_mail_relay.dat`、`playersuite_pending_payouts.dat`（按需生成） |

所有附件都声明了 `copyOnDeath`，玩家死亡不会丢仓库、钱包、商店、称号与签到进度。
卸载模组只会让这些键变成无人读取的数据，不会损坏存档。

> 早期开发版的 mod id 是 `playerwarehouse`。从那一版升级时附件键变为 `playersuite:warehouse`，
> 旧键不会被读取（可视为重置），旧的 `playerwarehouse-*.toml` 配置可以直接删除。

## 构建

环境：JDK 21、Gradle（仓库内含 wrapper，无需本机安装）。

```bash
./gradlew build            # 产物：build/libs/playersuite-1.21.1-<版本>.jar
```

依赖首次解析需要访问 Maven Central 与 `maven.neoforged.net`。国内或公司网络下载失败时：

- 在 `~/.gradle/gradle.properties` 里配置 HTTP/HTTPS 代理（`systemProp.https.proxyHost` / `systemProp.https.proxyPort`）；
  注意 Gradle wrapper 自身不读该文件，必要时用 `GRADLE_OPTS="-Dhttps.proxyHost=... -Dhttps.proxyPort=..."` 传给 wrapper。
- 若报 `PKIX path building failed`（JDK 自带 `cacerts` 缺少较新的根证书），可改用系统证书库：
  `GRADLE_OPTS="-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT"`（Windows）或对应平台的信任库设置。

版本固定在 `gradle.properties`：`neo_version`、`moddevgradle_version`、`java_version`。
换 `21.1.x` 的其它 NeoForge 版本只需改 `neo_version`——本项目没有使用 `21.1` 之外的新 API。

## 代码结构与约定

```
src/main/java/dev/mcmods/playersuite/
├── PlayerSuiteMod.java     模组入口：注册统一配置 + 各功能的附件/菜单注册表
├── config/                 SuiteConfig（唯一配置）、FeatureKeys、WarehouseConfig（纯算法）
├── economy/                CurrencyProvider、WalletData、WalletProvider、PendingPayouts、Economy
├── ui/                     PageMenu（分页/按钮协议/余额同步基类）、FeatureOpeners、DisplaySlots
├── menu/ client/           仓库页面 + 共用界面层（Theme、PageScreen、TextInputScreen）
├── hub/                    HubMenu：功能总入口
├── network/                TextInputPayload（全模组唯一的自定义包）、SuiteNet、InputRouter
├── warehouse/ mail/ bank/ shop/ market/ title/ checkin/    七个功能各自的包
├── command/                SuiteCommand(/ps 族)、WarehouseCommand、MarketCommand
├── event/ registry/        游戏事件与注册表
```

改代码前值得了解的设计底线：

- 页面一律是**服务端权威容器**：按钮点击走 `handleInventoryButtonClick → clickMenuButton`，
  余额/状态/选中行通过 `ContainerData` 单向同步；只有「文本输入」使用一个自定义 C2S 包，
  因此新增功能不需要再造包。
- 金额、权限、冷却、物品数量在服务端**全部重新校验一遍**，客户端输入完全不可信。
- 展示型物品槽统一用 `DisplaySlots`（只读，取出/放入都被拒绝），防止通过界面刷物品。
- 需要「先扣钱再发货」的地方一律：校验 → 扣款 → 发货；发货失败则整单回滚退款退库存。

## 已知限制

- 官方市场的「每日限购」以 06:00 为日界（代码常量）；签到的日界可用 `checkin.resetHour` 调整。
- `/market reload` 会把 OP 在界面里临时改的买/卖价还原为配置值（临时改的剩余库存同样按配置基准处理）。
- 玩家向官方市场出售不会增加市场库存——收购价就是服务器的资金出口，请根据自己的经济定价。
- 必顶双端安装：只装服务端时玩家打不开任何页面（菜单类型与界面类在客户端注册）。
- `∞`（无限库存）等符号在服务器控制台日志里可能显示为 `??`，游戏内显示正常。

## 致谢与代码来源

官方市场部分参考了 **[TradeSquares](https://github.com/nkkSong/TradeSquares)**（MIT License，作者 nkkSong）的设计思路。
本仓库**不包含任何上游源码**（发行 jar 内 `net/tradesquares/` 条目数为 0），也不以子模块、vendoring 或其它形式分发上游代码，仅在此作出设计上的致谢与来源说明。

- **未复制任何一行源码。** 以「去注释与空行、只比较长度 > 28 字符的实质代码行」的口径逐行比对：
  与上游完全相同的行去重后共 72 行（占本项目实质行 5.37%），其中 53 行是 `import` 语句、
  12 行是 Minecraft/NeoForge 的标准方法/字段签名、4 行是 Brigadier `Commands.literal(...)` 样板；
  最长连续相同片段只有 4 行，且只出现在界面注册类、自定义负载 record、`@Mod` 构造器这类骨架代码里；
  计税、库存回满、限购、分页、物品托管、补签、结息、投递等业务逻辑无一行相同。
- **借鉴的三点设计**：① 可替换的经济后端 SPI（上游接口为 `id/get/set/add`，本项目为
  `id/balance/withdraw/deposit/format/setBalance/supportsOfflinePayout`，并额外实现离线待领取）；
  ② 「服务端权威目录 + SavedData 存库存 + 配置变更后清理失效条目」的整体结构；
  ③ 「先全量校验再统一结算」的交易写法。
- **明确没有采用**：上游的 JSON/CSV 目录与迁移器（本项目改用统一配置里的 TOML 字符串列表）、
  购物车交互与客户端选择缓存（改为行内直接购买）、四个自定义网络包（本项目全模组只有一个文本输入包）、
  单向购买模型（本项目为买卖双向 + 每日限购 + 定时补货 + OP 改价改库存）、上游语言文件（本项目语言文件全部自写）。

## 许可

MIT，详见 [LICENSE](LICENSE)。上游 TradeSquares 同为 MIT，本仓库不含其源码，仅作设计参考并在此致谢。

## 版本

- **1.0.1** —— 修复切换页面/翻页时鼠标被弹回屏幕中心的问题：容器提供者不再触发客户端「先关后开」（少一发数据包、不闪屏），并在界面层增加鼠标位置恢复兜底。
- **1.0.0** —— 七功能合体首版：统一入口 `/ps`、单一配置文件、内置可替换经济、全页面 OP 管理、中英文文案。
