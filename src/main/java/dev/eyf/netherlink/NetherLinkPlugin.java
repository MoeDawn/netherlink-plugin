package dev.eyf.netherlink;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerAdvancementDoneEvent;
import org.bukkit.plugin.java.JavaPlugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.papermc.paper.event.player.AsyncChatEvent;

/**
 * NetherLink MC 端（Paper 插件）。
 *
 * 职责：
 *   1. 维护与 AstrBot 的 WebSocket 连接（见 AstrBotWsClient）
 *   2. MC -> QQ：聊天/进服/退服/死亡事件上报；
 *      唤醒词开头的聊天作为 bot_chat 上报（由 AstrBot 侧 LLM 处理）
 *   3. QQ -> MC：收到 chat/bot_reply 下行消息（AstrBot 已按模板渲染好含
 *      § 染色码的整行文本），用 LegacyComponentSerializer 渲染后广播到公屏；
 *      收到 command 以控制台身份执行并回传输出
 *   4. 防循环由 AstrBot 侧通过 OneBot self_id 识别机器人自身消息，
 *      MC 侧不做任何文本匹配判断
 */
public final class NetherLinkPlugin extends JavaPlugin implements Listener {

    private static final net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer LEGACY =
            net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacySection();
    private static final net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer PLAIN =
            net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText();

    private AstrBotWsClient wsClient;
    private final Gson gson = new Gson();
    private String serverName = "mc";

    // 游戏内机器人唤醒词（与 AstrBot 侧配置一致，逗号分隔）
    private final java.util.List<String> wakePrefixes = new java.util.ArrayList<>();

    // **确认卡**用的计分板 objective（本地菜单已改走点击回调，不用它）。
    // 确认卡由 AstrBot 下发 tellraw，里面只能跑 0 权限的
    // `trigger <它> set <token>`（click_event 里的指令以玩家身份解析，
    // 只有 trigger 能过）。本插件轮询它、把 token 上报 AstrBot。
    //
    // ⚠️ **只服务 AstrBot 的确认卡**（要回传一个整数 token，塞不进点击回调）。
    //    本地菜单**不用**它 —— 见下方菜单一节。
    private String menuObjective = "netherlink_menu";

    // ---- 本地菜单（不经 AstrBot，全在 MC 端完成）----
    // 菜单总开关。关掉后触发词不生效，那条话照常作为聊天上报。
    private boolean menuEnable = true;

    // 触发词（逗号分隔）。**整条消息**等于其中之一才触发 —— 与唤醒词的
    // 「前缀匹配」语义不同，见 onChat 里的说明。
    private final java.util.List<String> menuTriggers = new java.util.ArrayList<>();

    private String menuTitle = "\u2726 \u83dc\u5355 \u2726";
    private String menuItemTeleportPlayer = "\u4f20\u9001\u5230\u73a9\u5bb6";
    private String menuItemTeleportDeath = "\u4f20\u9001\u6b7b\u4ea1\u70b9";

    // 传送冷却（秒），防连点。0 = 无冷却。
    private long menuTeleportCooldownSec = 3L;

    // 玩家 UUID -> 上次传送的毫秒时间戳。只在主线程访问（点击回调里切了
    // global region scheduler），所以不需要并发容器。
    private final java.util.Map<java.util.UUID, Long> menuLastTeleport =
            new java.util.HashMap<>();

    // 确认卡轮询间隔（毫秒）。0 = 关闭轮询（关掉则确认卡点了没反应）。
    // ⚠️ 只有**确认卡**走这条路；本地菜单走点击回调，不用轮询。
    private long menuPollIntervalMs = 1000L;

    // AstrBot 确认卡 token 的起点。计分板里小于它的值一律**不归本端处理**
    // （本地菜单已经不用计分板了，见下方菜单一节）。
    // ⚠️ **必须与 AstrBot 的 `actions.TOKEN_BASE` 相等** —— 守卫
    //    `test_menu_code_space_does_not_collide_with_confirm_tokens` 钉着。
    private static final int CONFIRM_TOKEN_BASE = 10000;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadSettings();

        String host = getConfig().getString("host", "127.0.0.1");
        int port = getConfig().getInt("port", 8765);
        String token = getConfig().getString("token", "change-me");

        wsClient = new AstrBotWsClient(this, host, port, token);
        wsClient.connect();

        Bukkit.getPluginManager().registerEvents(this, this);
        // ⚠️ 下面这组**只服务 AstrBot 的确认卡**（要回传一个整数 token，
        //    塞不进点击回调）。本地菜单走点击回调，不需要它们 —— 见下方菜单一节。
        ensureMenuObjective();
        // 解锁**当前已在线**的玩家：只在 onJoin 做的话，重载插件时已经在线的
        // 人拿不到 trigger 权限（实测症状：点确认报「你尚无法触发这个记分项」）。
        for (Player online : Bukkit.getOnlinePlayers()) {
            enableMenuTrigger(online);
        }
        startMenuPolling();
        getLogger().info("NetherLink MC 端已启用，目标 AstrBot: " + host + ":" + port);
    }

    /**
     * 启动确认卡轮询（每秒读一次计分板）。`menu-poll-interval: 0` 可关掉。
     *
     * 🔴 **为什么确认卡不用点击回调**：回调的载荷是 SNBT，要塞进 tellraw 的
     *    JSON 文本里，而确认卡是 **AstrBot 用 Python 拼的 JSON 字符串**下发的
     *    —— 让它去生成 `custom` 点击的 SNBT 载荷得不偿失。计分板只需要一个
     *    整数，是这里最省事也最稳的回传通道。
     */
    private void startMenuPolling() {
        if (menuPollIntervalMs <= 0) {
            getLogger().info("NetherLink: 确认卡轮询已关闭（menu-poll-interval=0）");
            return;
        }
        // ⚠️ 读计分板必须回主线程；`runAtFixedRate` 的回调在异步线程。
        Bukkit.getAsyncScheduler().runAtFixedRate(this, t ->
                Bukkit.getGlobalRegionScheduler().execute(this, this::pollMenu),
                menuPollIntervalMs, menuPollIntervalMs, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    /**
     * 读计分板，把**确认卡**的点击上报 AstrBot。
     *
     * ⚠️ 小于 `CONFIRM_TOKEN_BASE` 的值一律**忽略**：那本该是旧版本地菜单的码，
     *    现在菜单不走计分板了，但旧 jar 可能还在写 —— 上报上去只会让 AstrBot
     *    收到它没发过的 token。
     */
    private void pollMenu() {
        try {
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            var obj = board.getObjective(menuObjective);
            if (obj == null) {
                return;
            }
            for (Player p : Bukkit.getOnlinePlayers()) {
                var score = obj.getScore(p.getName());
                int v = score.getScore();
                if (v <= 0) {
                    continue;   // 0 = 空闲/取消
                }
                // ⚠️ **先归零再上报**：不这么做，WS 断线时这个值会留存，
                //    等重连后又被当成一次「新的点击」重复上报（陈旧点击）。
                score.setScore(0);
                // 🔴 **用完必须重新解锁**：`/trigger` 每成功一次就把该分数
                //    `lock()` 掉（反编译 `TriggerCommand.getScore()` 证实）。
                //    只靠进服/启用时那一次 `enable` 的话，**第二张确认卡就点不动**，
                //    症状就是「你尚无法触发这个记分项」（2026-10-11 真机暴露）。
                //    ⚠️ 顺序要紧：先 setScore(0) 再 unlock —— `unlock()` 只改锁，
                //    不会把刚清掉的值带回来。
                score.setTriggerable(true);
                if (v < CONFIRM_TOKEN_BASE) {
                    continue;   // 旧版菜单码残留，不归本端管
                }
                JsonObject o = new JsonObject();
                o.addProperty("type", "action_confirm");
                o.addProperty("player", p.getName());
                o.addProperty("token", v);
                report(o);
                getLogger().info("NetherLink: 收到确认点击 player=" + p.getName()
                        + " token=" + v);
            }
        } catch (Exception e) {
            // 轮询出问题**不能**刷屏：它每秒都跑。只在异常时记一条。
            getLogger().warning("NetherLink: 确认卡轮询失败（忽略本次）: " + e);
        }
    }

    /**
     * 保证菜单那个 trigger 型 objective 存在（**只服务 AstrBot 的确认卡**）。
     *
     * ⚠️ **幂等**：重复建同名 objective 在原版指令里会报
     * `An objective already exists by that name`（实测），所以先查再建。
     */
    private void ensureMenuObjective() {
        try {
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            if (board.getObjective(menuObjective) == null) {
                // ⚠️ 用取 `Component` 的那个重载 —— 取 `String` 的显示名版本
                //    Paper 已标 `@Deprecated`（-Xlint:deprecation 会指出行号）。
                board.registerNewObjective(menuObjective,
                        org.bukkit.scoreboard.Criteria.TRIGGER,
                        net.kyori.adventure.text.Component.text(menuObjective));
                getLogger().info("NetherLink: 已创建菜单计分板 " + menuObjective);
            }
        } catch (Exception e) {
            // 建不出来不该连累插件加载；但必须有日志（否则按钮点了没反应且无从查）
            getLogger().warning("NetherLink: 创建菜单计分板失败（菜单/确认按钮将不可用）: " + e);
        }
    }

    /**
     * 解锁某个玩家的 trigger 权限。
     *
     * 为什么要在玩家进服时做：`/trigger` 的硬前提是「该分数已被解锁」
     * （原版 `/scoreboard players enable`）。用 Java API 的
     * `Score.setTriggerable(true)` 而不是发 `players enable` 指令 ——
     * 后者会经我们的 command 通道产生一条 `command_result` 回执，
     * 那条回执的去向无从预料（多余的噪声）。
     *
     * 🔴 **`/trigger` 用过之后会自锁**（反编译 `TriggerCommand.getScore()`：
     *    成功路径末尾调 `ScoreAccess.lock()`）。所以「一次 enable 长期有效」
     *    是**错的** —— 每次消费掉点击后都要在 `pollMenu` 里重新解锁。
     */
    private void enableMenuTrigger(Player player) {
        try {
            var board = Bukkit.getScoreboardManager().getMainScoreboard();
            var obj = board.getObjective(menuObjective);
            if (obj == null) {
                return;
            }
            obj.getScore(player.getName()).setTriggerable(true);
        } catch (Exception e) {
            getLogger().warning("NetherLink: 解锁菜单计分板失败（" + player.getName()
                    + " 的按钮将不可用）: " + e);
        }
    }

    @Override
    public void onDisable() {
        menuLastTeleport.clear();
        if (wsClient != null) {
            wsClient.shutdown();
        }
        getLogger().info("NetherLink MC 端已卸载");
    }

    private void loadSettings() {
        serverName = getConfig().getString("server-name", "mc");
        // ⚠️ 必须与 AstrBot 侧的 mc_menu_objective 一致（确认按钮靠它回传 token）
        menuObjective = getConfig().getString("menu-objective", "netherlink_menu");
        menuEnable = getConfig().getBoolean("menu-enable", true);
        menuTitle = getConfig().getString("menu-title", "\u2726 \u83dc\u5355 \u2726");
        menuItemTeleportPlayer = getConfig().getString(
                "menu-item-teleport-player", "\u4f20\u9001\u5230\u73a9\u5bb6");
        menuItemTeleportDeath = getConfig().getString(
                "menu-item-teleport-death", "\u4f20\u9001\u6b7b\u4ea1\u70b9");
        menuTeleportCooldownSec = getConfig().getLong("menu-teleport-cooldown", 3L);
        menuPollIntervalMs = getConfig().getLong("menu-poll-interval", 1000L);
        menuTriggers.clear();
        for (String m : getConfig().getString("menu-triggers", "\u83dc\u5355,menu,\u30e1\u30cb\u30e5\u30fc").split(",")) {
            if (!m.isBlank()) {
                menuTriggers.add(m.strip());
            }
        }
        wakePrefixes.clear();
        for (String p : getConfig().getString("wake-prefixes", "ai,助手").split(",")) {
            if (!p.isBlank()) {
                wakePrefixes.add(p.strip());
            }
        }
    }

    @Override
    public void saveDefaultConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            getDataFolder().mkdirs();
            try {
                Files.writeString(file.toPath(), """
                        # NetherLink MC 端配置
                        # AstrBot 插件监听的 WebSocket 地址
                        host: "127.0.0.1"
                        port: 8765
                        # 必须与 AstrBot 插件配置的 auth_token 一致
                        token: "change-me"
                        # 本服务器的标识（握手时上报，供 AstrBot 区分服务器）
                        # 注意：显示名不在本端控制 —— QQ 群前缀、模板 {server}、
                        # AI 上下文里的服务器名统一由 AstrBot 插件的 ws_ports
                        # （冒号前那段）决定
                        server-name: "mc"
                        # 游戏内机器人唤醒词（逗号分隔，与 AstrBot 侧 mc_wake_prefixes 一致）
                        wake-prefixes: "ai,助手"
                        # 菜单/确认按钮用的计分板 objective
                        # 必须与 AstrBot 侧 mc_menu_objective 一致
                        menu-objective: "netherlink_menu"
                        # ---- 本地菜单（不经 AstrBot，全在 MC 端完成）----
                        # 总开关。关掉后触发词不生效，那条话照常作为聊天上报
                        menu-enable: true
                        # 触发词（逗号分隔）。**整条消息**等于其中之一才触发
                        # （唤醒词是「前缀匹配」，两者语义不同）
                        menu-triggers: "\u83dc\u5355,menu,\u30e1\u30cb\u30e5\u30fc"
                        # 菜单抬头与两个菜单项的名字
                        menu-title: "\u2726 \u83dc\u5355 \u2726"
                        menu-item-teleport-player: "\u4f20\u9001\u5230\u73a9\u5bb6"
                        menu-item-teleport-death: "\u4f20\u9001\u6b7b\u4ea1\u70b9"
                        # 传送冷却（秒），防连点。0 = 无冷却
                        menu-teleport-cooldown: 3
                        # 确认卡轮询间隔（毫秒）。0 = 关闭（关掉则确认卡点了没反应）
                        # ⚠️ 只服务来自 AstrBot 的确认卡；本地菜单用点击回调
                        menu-poll-interval: 1000
                        """);
            } catch (Exception e) {
                getLogger().warning("写入默认配置失败: " + e.getMessage());
            }
        }
    }

    public String serverName() {
        return serverName;
    }

    // ------------------------------------------------------------------
    // MC -> QQ 事件上报（异步线程执行网络发送）
    // ------------------------------------------------------------------
    private void report(JsonObject payload) {
        Bukkit.getAsyncScheduler().runNow(this, t -> wsClient.send(payload.toString()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        String plain = PLAIN.serialize(event.message());

        // ---- 本地菜单优先 ----
        // 🔴 **整条相等**，不是前缀：唤醒词是「以它开头」（后面还要跟内容，
        //    如「ai 帮我挖钻石」），而菜单触发词必须是**整句**，否则
        //    「菜单怎么做」这种正常聊天会被吃掉。
        // 必须排在唤醒词之前：两者可能配成同一个词，而整条相等更具体。
        // 触发后**就地消费，不上报** —— 这正是「菜单不经 LLM」的意义。
        if (menuEnable && menuTriggers.contains(plain.strip())) {
            Player p = event.getPlayer();
            Bukkit.getGlobalRegionScheduler().execute(this, () -> sendMainMenu(p));
            return;
        }

        // 唤醒词开头 → 机器人对话（bot_chat），AstrBot 侧走 LLM
        for (String prefix : wakePrefixes) {
            if (plain.startsWith(prefix)) {
                JsonObject bot = new JsonObject();
                bot.addProperty("type", "bot_chat");
                bot.addProperty("player", event.getPlayer().getName());
                bot.addProperty("text", plain);
                report(bot);
                return;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("type", "chat");
        o.addProperty("player", event.getPlayer().getName());
        o.addProperty("text", plain);
        report(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // 顺手解锁他的 trigger 权限（否则菜单/确认按钮点了没反应）
        enableMenuTrigger(event.getPlayer());
        JsonObject o = new JsonObject();
        o.addProperty("type", "join");
        o.addProperty("player", event.getPlayer().getName());
        report(o);
    }

    // ------------------------------------------------------------------
    // 本地菜单（全部在 MC 端完成，不经 AstrBot、不消耗好感）
    //
    // 🔴 **用 Paper 的点击回调（`ClickEvent.callback`），不用 `/trigger` 计分板**。
    //    计分板那条路有两个死穴（2026-10-11 实机暴露，玩家截图里全看得见）：
    //      · `/trigger` 会回显**自己的**成功/失败消息，玩家看得见且无法抑制
    //        （`commands.trigger.set.success` = 「已触发[...]（数值已设为 N）」、
    //         `commands.trigger.failed.unprimed` = 「你尚无法触发这个记分项」）
    //      · `/trigger` 的硬前提是「该分数已被 `players enable` 解锁」，
    //        而解锁只在 `onJoin` 做 ⇒ **更新插件时已经在线的玩家不会被解锁**
    //    回调则由客户端直接回传一个自定义点击包（Paper 26.3 已实现：
    //    `ServerboundCustomClickActionPacket` + `ClickCallbackProviderImpl`），
    //    **无指令、无计分板、无回显、无需解锁**。
    //
    //    ⚠️ 来自 AstrBot 的**确认卡仍然走计分板** —— 那要回传一个整数 token，
    //       塞不进点击回调。两条路互不相干，`menuObjective` 现在只服务确认卡。
    // ------------------------------------------------------------------

    /** 在线的其他玩家（按名字排序）。 */
    private java.util.List<Player> menuTargets(Player viewer) {
        java.util.List<Player> list = new java.util.ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!p.getUniqueId().equals(viewer.getUniqueId())) {
                list.add(p);
            }
        }
        list.sort(java.util.Comparator.comparing(Player::getName));
        return list;
    }

    /**
     * 一个可点击的菜单项。点了在主线程执行 `action`。
     *
     * ⚠️ **`hover` 两个主菜单项也必须给** —— 2026-10-11 实机暴露：只给玩家列表
     * 的按钮加了悬浮说明，主菜单那两项没加，玩家悬浮上去什么都没有。
     */
    private net.kyori.adventure.text.Component menuButton(
            String label, net.kyori.adventure.text.format.TextColor color,
            net.kyori.adventure.text.Component hover,
            java.util.function.Consumer<Player> action) {
        return net.kyori.adventure.text.Component.text()
                .append(net.kyori.adventure.text.Component.text("[ " + label + " ]")
                        .decorate(net.kyori.adventure.text.format.TextDecoration.BOLD))
                .color(color)
                .hoverEvent(net.kyori.adventure.text.event.HoverEvent.showText(hover))
                .clickEvent(net.kyori.adventure.text.event.ClickEvent.callback(
                        (net.kyori.adventure.audience.Audience audience) -> {
                            if (!(audience instanceof Player clicker)) {
                                return;
                            }
                            // 回调不保证在主线程（Paper 从网络包处理时调）⇒ 切回去
                            Bukkit.getGlobalRegionScheduler().execute(
                                    this, () -> action.accept(clicker));
                        },
                        opts -> opts
                                .uses(net.kyori.adventure.text.event.ClickCallback.UNLIMITED_USES)
                                .lifetime(java.time.Duration.ofHours(1))))
                .build();
    }

    /** 分隔线（16 个 ✦）。 */
    private net.kyori.adventure.text.Component menuRule() {
        return net.kyori.adventure.text.Component.text(
                " ✦✦✦✦✦✦✦✦"
                        + "✦✦✦✦✦✦✦✦",
                net.kyori.adventure.text.format.NamedTextColor.DARK_GRAY);
    }

    /** 主菜单：私聊给玩家，两个按钮（都带悬浮说明）。 */
    private void sendMainMenu(Player player) {
        try {
            player.sendMessage(net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.text(menuTitle,
                            net.kyori.adventure.text.format.NamedTextColor.GOLD,
                            net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(menuRule())
                    .build());
            player.sendMessage(net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.space())
                    .append(menuButton(menuItemTeleportPlayer,
                            net.kyori.adventure.text.format.NamedTextColor.AQUA,
                            net.kyori.adventure.text.Component.text("列出在线玩家，点击传送"),
                            this::sendPlayerMenu))
                    .append(net.kyori.adventure.text.Component.space())
                    .append(menuButton(menuItemTeleportDeath,
                            net.kyori.adventure.text.format.NamedTextColor.LIGHT_PURPLE,
                            net.kyori.adventure.text.Component.text("传送到上次死亡位置"),
                            this::teleportToDeath))
                    .build());
        } catch (Exception e) {
            getLogger().warning("NetherLink: 发送菜单失败（" + player.getName() + "）: " + e);
        }
    }

    /** 第二层：列出在线玩家（每项一个按钮）+ 返回。 */
    private void sendPlayerMenu(Player player) {
        try {
            java.util.List<Player> targets = menuTargets(player);
            player.sendMessage(net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.text(
                            menuItemTeleportPlayer,
                            net.kyori.adventure.text.format.NamedTextColor.AQUA,
                            net.kyori.adventure.text.format.TextDecoration.BOLD))
                    .append(menuRule())
                    .build());
            if (targets.isEmpty()) {
                player.sendMessage(net.kyori.adventure.text.Component.text(
                        "  当前没有其他在线玩家。",
                        net.kyori.adventure.text.format.NamedTextColor.GRAY));
            }
            for (Player target : targets) {
                // 🔴 **把目标名字捕获进闭包**，不编码序号 —— 序号会因玩家进出而
                //    错位，名字才是身份。执行时按名字**重新查**（spec §7.5 裁决 2）。
                final String name = target.getName();
                player.sendMessage(net.kyori.adventure.text.Component.text()
                        .append(net.kyori.adventure.text.Component.space())
                        .append(menuButton(name,
                                net.kyori.adventure.text.format.NamedTextColor.GREEN,
                                net.kyori.adventure.text.Component.text("点击传送到 " + name),
                                p -> teleportToPlayerByName(p, name)))
                        .build());
            }
            player.sendMessage(net.kyori.adventure.text.Component.text()
                    .append(net.kyori.adventure.text.Component.space())
                    .append(menuButton("返回",
                            net.kyori.adventure.text.format.NamedTextColor.GRAY,
                            net.kyori.adventure.text.Component.text("回到主菜单"),
                            this::sendMainMenu))
                    .build());
        } catch (Exception e) {
            getLogger().warning("NetherLink: 发送玩家列表失败（" + player.getName() + "）: " + e);
        }
    }

    /** 冷却检查。返回 true 表示这次点击可以执行。 */
    private boolean menuCooldownOk(Player player) {
        if (menuTeleportCooldownSec <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long last = menuLastTeleport.get(player.getUniqueId());
        if (last != null && now - last < menuTeleportCooldownSec * 1000L) {
            return false;
        }
        menuLastTeleport.put(player.getUniqueId(), now);
        return true;
    }

    /**
     * 传送到某个玩家（按**名字**重新查；查不到就是已经下线）。
     *
     * ✅ 传送**成功公屏广播**（用户 2026-10-11 要求：让所有人都能看见谁传到哪去了）；
     * 失败**只私聊**。
     */
    private void teleportToPlayerByName(Player player, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !target.isOnline()) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    targetName + " 已不在线。",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            sendPlayerMenu(player);   // 名单变了，顺手重开一份
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            return;
        }
        if (!menuCooldownOk(player)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "传送太频繁，请稍候。",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        player.teleportAsync(target.getLocation(),
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        Bukkit.getServer().broadcast(net.kyori.adventure.text.Component.text()
                .append(net.kyori.adventure.text.Component.text(player.getName(),
                        net.kyori.adventure.text.format.NamedTextColor.AQUA))
                .append(net.kyori.adventure.text.Component.text(" 已传送到 ",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                .append(net.kyori.adventure.text.Component.text(target.getName(),
                        net.kyori.adventure.text.format.NamedTextColor.AQUA))
                .build());
    }

    /**
     * 传送到上次死亡点。
     *
     * 坐标读 `Player.getLastDeathLocation()`（继承自 `HumanEntity`，`OfflinePlayer`
     * 上也有）—— **不给死亡事件加坐标**，那会把玩家位置流进推群模板与 AI
     * 上下文（claude.md 坑 11）。
     */
    private void teleportToDeath(Player player) {
        org.bukkit.Location loc = player.getLastDeathLocation();
        // ✅ 「没有死亡点记录」只私聊 —— 这是私事
        if (loc == null) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "没有死亡点记录。",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        // ⚠️ Location 的 world 可能为 null（那份维度没被加载）—— 用**世界名**
        //    重新解析，别指望直接 teleport 一个 world 为 null 的位置。
        org.bukkit.World world = loc.getWorld();
        org.bukkit.World resolved = world == null ? null : Bukkit.getWorld(world.getName());
        if (resolved == null) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "死亡点所在世界已不可用。",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        if (!menuCooldownOk(player)) {
            player.sendMessage(net.kyori.adventure.text.Component.text(
                    "传送太频繁，请稍候。",
                    net.kyori.adventure.text.format.NamedTextColor.RED));
            return;
        }
        org.bukkit.Location dest = new org.bukkit.Location(resolved,
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        player.teleportAsync(dest,
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        Bukkit.getServer().broadcast(net.kyori.adventure.text.Component.text()
                .append(net.kyori.adventure.text.Component.text(player.getName(),
                        net.kyori.adventure.text.format.NamedTextColor.AQUA))
                .append(net.kyori.adventure.text.Component.text(" 已传送到死亡点",
                        net.kyori.adventure.text.format.NamedTextColor.YELLOW))
                .build());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        menuLastTeleport.remove(event.getPlayer().getUniqueId());
        JsonObject o = new JsonObject();
        o.addProperty("type", "leave");
        o.addProperty("player", event.getPlayer().getName());
        report(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "death");
        o.addProperty("player", event.getPlayer().getName());
        String deathMessage = event.deathMessage() == null ? ""
                : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                        .plainText().serialize(event.deathMessage());
        o.addProperty("message", deathMessage);
        report(o);
    }

    /**
     * 玩家获得成就 → 上报给 AstrBot，由那边组装提示词交给 AI 处理好感与回复。
     *
     * 只上报「有展示信息的」成就：配方解锁（minecraft:recipes/*）与根成就
     * 也会触发本事件，但那些不是玩家眼里的"获得成就"，
     * 报上去会让 AI 频繁无意义地加好感。
     *
     * ⚠️ 根成就的判据是「**有没有父成就**」，**不是**「display 是否为 null」。
     * 实测（2026-09-28，NeoForge 端跑真服验证）：`minecraft:story/root`
     * **是有 display 的**——那正是成就页签的图标与标题。所以 `display == null`
     * 根本过滤不掉它，NeoForge 端实测收到了 `key='minecraft:story/root'`。
     * Paper 的 {@code getDisplay()} 与 Fabric 的 {@code value().display()} 是同一个值。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAdvancement(PlayerAdvancementDoneEvent event) {
        var advancement = event.getAdvancement();
        // 根成就：没有父成就（是某个分类的页签标题）
        if (advancement.getParent() == null) {
            return;
        }
        var display = advancement.getDisplay();
        if (display == null) {
            return;  // 没有展示信息，玩家看不到，不该上报
        }
        String key = advancement.getKey().getKey();   // 如 "story/mine_diamond"
        if (key.startsWith("recipes/")) {
            return;  // 配方解锁，不算成就
        }
        net.kyori.adventure.text.Component titleComp = display.title();
        String title = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(titleComp);

        JsonObject o = new JsonObject();
        o.addProperty("type", "advancement");
        o.addProperty("player", event.getPlayer().getName());
        o.addProperty("advancement", title);
        o.addProperty("advancement_key", key);
        report(o);
    }

    // ------------------------------------------------------------------
    // QQ -> MC（来自 AstrBot 的下行消息，异步线程调用）
    // ------------------------------------------------------------------
    public void onWsMessage(String raw) {
        try {
            JsonObject data = gson.fromJson(raw, JsonObject.class);
            String type = data.has("type") ? data.get("type").getAsString() : "";
            switch (type) {
                case "chat", "bot_reply" -> handleLineDown(data);
                case "command" -> handleCommandDown(data);
                default -> {
                    // hello 应答/未知类型忽略
                }
            }
        } catch (Exception e) {
            getLogger().warning("处理 WS 消息失败: " + e.getMessage());
        }
    }

    /**
     * AstrBot 下行的 chat / bot_reply：line 字段已是模板渲染完成的整行文本，
     * 可含 § 染色码。渲染成 Adventure 组件后广播到公屏。
     * 防循环完全由 AstrBot 侧负责（OneBot self_id 识别机器人自身消息），
     * MC 侧不做文本匹配——避免误伤复读机器人消息的真人玩家。
     */
    private void handleLineDown(JsonObject data) {
        String line = data.has("line") ? data.get("line").getAsString() : "";
        if (line.isEmpty()) {
            return;
        }
        net.kyori.adventure.text.Component rendered = LEGACY.deserialize(line);
        // getServer().broadcast(...) 是 Server 接口上的 default 方法，Folia 可用
        // （Bukkit.broadcast 这个静态门面则不一定）。
        Bukkit.getGlobalRegionScheduler().execute(this, () -> getServer().broadcast(rendered));
    }

    private void handleCommandDown(JsonObject data) {
        String id = data.has("id") ? data.get("id").getAsString() : UUID.randomUUID().toString();
        String rawCmd = data.has("cmd") ? data.get("cmd").getAsString() : "";
        final String cmd = rawCmd.startsWith("/") ? rawCmd.substring(1) : rawCmd;

        // 控制台身份执行：权限等同 OP，切主线程
        Bukkit.getGlobalRegionScheduler().execute(this, () -> {
            StringBuilder output = new StringBuilder();
            // 必须用 Bukkit.createCommandSender 造发送器，**不能**自己用 Proxy 包控制台。
            // Paper 的 VanillaCommandWrapper.getListener 只认 6 种具体类型
            // （CraftEntity / BlockCommandSender / RemoteConsoleCommandSender /
            //   ConsoleCommandSender / ProxiedCommandSender / FeedbackForwardingSender），
            // 动态 Proxy 一个都不沾，于是任何原生指令（give/tp/list/time …）在解析之前
            // 就直接抛 IllegalArgumentException: Cannot make ... a vanilla command listener。
            // 该工厂返回的正是 FeedbackForwardingSender：权限等同控制台
            // （isOp=true、hasPermission("*")=true），并且把两类反馈统一成 Component
            // 送进这个 lambda —— 原生指令的反馈，以及 Bukkit 插件指令经
            // sendMessage(String) 输出的文本（实测两者都能收到；真实控制台则一条都收不到）。
            CommandSender console = Bukkit.createCommandSender(
                    (net.kyori.adventure.text.Component component) ->
                            output.append(PLAIN.serialize(component)).append('\n'));
            boolean ok = false;
            try {
                ok = Bukkit.dispatchCommand(console, cmd);
                if (output.length() == 0) {
                    output.append(ok
                            ? "（指令已执行，无返回输出）"
                            : "指令执行失败（服务器返回 false）");
                }
                sendCommandResult(id, ok, output.toString());
            } catch (Exception e) {
                // 抛异常 = 明确失败。必须如实上报，否则 Python 侧会当成功照扣好感
                sendCommandResult(id, false, "执行异常: " + e.getMessage());
            }
        });
    }

    private void sendCommandResult(String id, boolean ok, String output) {
        JsonObject o = new JsonObject();
        o.addProperty("type", "command_result");
        o.addProperty("id", id);
        // ok 是 Python 侧判断「是否退费」的唯一依据：false = 这次执行明确失败，
        // 必须退费。早先这里无条件写 true，而 Python 侧又只读 output 不读 ok，
        // 两边一起把「执行异常」伪装成了成功——玩家白扣好感还被告知「指令已执行」。
        o.addProperty("ok", ok);
        o.addProperty("output", output);
        Bukkit.getAsyncScheduler().runNow(this, t -> wsClient.send(o.toString()));
    }
}
