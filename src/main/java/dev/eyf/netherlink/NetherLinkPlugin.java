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
        getLogger().info("NetherLink MC 端已启用，目标 AstrBot: " + host + ":" + port);
    }

    @Override
    public void onDisable() {
        if (wsClient != null) {
            wsClient.shutdown();
        }
        getLogger().info("NetherLink MC 端已卸载");
    }

    private void loadSettings() {
        serverName = getConfig().getString("server-name", "mc");
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
                        # AI 上下文里的服务器名统一由 AstrBot 插件配置的
                        # server_display_names 决定
                        server-name: "mc"
                        # 游戏内机器人唤醒词（逗号分隔，与 AstrBot 侧 mc_wake_prefixes 一致）
                        wake-prefixes: "ai,助手"
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
        JsonObject o = new JsonObject();
        o.addProperty("type", "join");
        o.addProperty("player", event.getPlayer().getName());
        report(o);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
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
