# NetherLink MC 端 · Paper / Purpur / Folia 版

Minecraft 服务端插件（Paper / Purpur / Folia），与 [AstrBot 侧的 NetherLink 插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)建立 WebSocket 长连接，实现服务器与 QQ 群的双向消息互通。

**必须先装好 AstrBot 侧插件**，本插件才能工作（它是客户端，主动连入 AstrBot）。

> 📌 另有 [Fabric 版](https://github.com/MoeDawn/netherlink-fabric) 与
> [NeoForge 版](https://github.com/MoeDawn/netherlink-neoforge)。
> 两个版本**协议完全相同**，接同一个 AstrBot 插件，服务端不用改配置。

---

## 功能

| 方向 | 说明 |
|---|---|
| 游戏 → QQ | 聊天 / 进服 / 退服 / 死亡推送到群；唤醒词开头的话作为对话交给 AI |
| 成就上报 | 玩家获得成就时通知 AI（已过滤配方解锁与根成就） |
| QQ → 游戏 | 群消息渲染 § 染色码后广播到公屏 |
| 指令执行 | 以控制台身份执行 AI 下发的指令，并把服务器**真实输出**回传给 AI |

---

## 功能

- 游戏内聊天 / 进服 / 退服 / 死亡 / 成就 → QQ 群
- QQ 群消息 → 游戏公屏（支持 `§` 染色码）
- 游戏内唤醒词与 AI 对话
- **本地菜单**：公屏发 `菜单` / `menu` / `メニュー`，弹出一张可点击菜单（不经 AstrBot、不消耗好感）：传送到玩家、传送死亡点、**展示手上的物品**（全服可见、带悬停详情）

## 环境要求

- Minecraft 服务端：**Paper 26.3 / Purpur 26.3 / Folia**
- **Java 25**
- 已装好并运行 [AstrBot 侧插件](https://github.com/MoeDawn/astrbot_plugin_netherlink)

| 核心 | 状态 | 说明 |
|---|---|---|
| **Paper** | ✅ 已验证 | 当前实机运行的就是它 |
| **Purpur** | ✅ 可运行 | Paper 的分支，API 与事件完全一致 |
| **Folia** | ⚠️ 已适配，未实机验证 | 已改用 Paper / Folia 共用的调度器，并声明 `folia-supported`。⚠️ 但没有在真 Folia 上跑过 |
| Spigot | ❌ 不支持 | 缺少 Paper 专有的扩展接口（用于捕获指令输出） |
| NeoForge | — | 见 [NeoForge 版](https://github.com/MoeDawn/netherlink-neoforge) |
| Fabric | — | 见 [Fabric 版](https://github.com/MoeDawn/netherlink-fabric) |

> ⚠️ 只支持 **Minecraft 26.3**，其他 MC 版本暂未适配。

---

## 安装

1. **下载 Release**
   从 [Releases](https://github.com/MoeDawn/netherlink-plugin/releases) 下载 `netherlink-plugin-0.1.2.jar`

2. **放入服务端**
   把 jar 放进服务器的 `plugins/` 目录，重启服务器

3. **填配置**
   首次启动会生成 `plugins/NetherLink/config.yml`：

   ```yaml
   host: "AstrBot机器的IP"
   port: 8765
   token: "与 AstrBot 侧 auth_token 完全一致"
   server-name: "survival"    # 本服务器的标识：AstrBot 侧用它区分不同服务器
   wake-prefixes: "ai,助手"    # 唤醒词，须与 AstrBot 侧 mc_wake_prefixes 一致
   ```

4. **重启服务器**使配置生效

连接成功后，AstrBot 日志会显示握手成功，游戏内事件即开始推送到群。

---

## 配置项

| 配置项 | 说明 |
|---|---|
| `host` / `port` | AstrBot 侧监听的 WebSocket 地址与端口 |
| `token` | 握手鉴权密钥，两侧必须一致 |
| `server-name` | 本服务器的**身份**，握手时上报。AstrBot 侧用它区分各台服务器。它**不是**显示名 |
| `wake-prefixes` | 游戏内唤醒词（逗号分隔），**须与 AstrBot 侧 `mc_wake_prefixes` 一致**否则唤不醒 AI |
| `menu-enable` | 本地菜单总开关。关掉后触发词不生效，那条话照常作为聊天上报 |
| `menu-triggers` | 菜单触发词（逗号分隔）。**整条消息**等于其中之一才触发（唤醒词是前缀匹配，两者不同）|
| `menu-title` | 菜单抬头 |
| `menu-item-teleport-player` / `menu-item-teleport-death` | 两个菜单项的名字 |
| `menu-teleport-cooldown` | 传送冷却，秒，防连点。0 = 无冷却 |
| `menu-objective` | AstrBot 确认卡用的计分板名。**须与 AstrBot 侧 `mc_menu_objective` 一致** |

> 显示名不在这里控制——QQ 群前缀、`{server}` 占位符、AI 上下文里的服务器名
> 统一由 AstrBot 侧的 `ws_ports` 决定，取冒号前那段（没配则显示 `MC`）。

**除菜单那几项外，键名与默认值与 Fabric 端的 `config/netherlink.json` 逐字对齐**——
换端时前面几项可以照搬。⚠️ **本地菜单目前只有本端有**（Fabric / NeoForge 尚未移植），
那几项搬过去不会生效。

---

## 可靠性

- 断线**自动重连**，指数退避（3 秒起，最多 60 秒）
- **15 秒心跳**保活，同时清理超时未回执的指令
- 重连与心跳都在异步线程执行，**不阻塞服务器主线程**

---

## 状态

| 功能 | 状态 |
|---|---|
| WebSocket 连接 / 握手 / 重连 / 心跳 | ✅ 已验证 |
| 指令执行 + 输出捕获 | ✅ 已验证（Paper 与 Folia 各跑一遍，5 条指令一致） |
| 聊天 / 进服 / 退服 / 死亡上报 | ✅ 已验证（含**真人玩家**） |
| 成就上报 | ✅ 已验证 |
| QQ → 游戏广播（§ 染色码渲染） | ✅ 已验证 |
| **游戏内机器人对话（唤醒词）** | ✅ 已验证（含**真人玩家**） |
| **本地菜单（公屏发触发词 → 点击传送）** | ⚠️ 已实现，**未真人验证**（点击无法自动化）|
| **Folia 核心** | ✅ **已实机验证**（Folia 26.2） |
| Purpur | ✅ 可运行（Paper 分支，API 与事件一致） |

---

## 从源码构建

需要 **JDK 25** 与 **Gradle 9.x**。

```bash
cd netherlink-plugin
./build.cmd          # Windows
```

产物：`build/libs/netherlink-plugin-0.1.2.jar`

---

## 许可

[MIT License](LICENSE)
