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
   从 [Releases](https://github.com/MoeDawn/netherlink-plugin/releases) 下载 `netherlink-plugin-0.1.1.jar`

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
| `server-name` | 本服务器的**身份**，握手时上报。AstrBot 侧的 `ws_ports` / `server_display_names` 用它区分与命名各台服务器（不影响显示名） |
| `wake-prefixes` | 游戏内唤醒词（逗号分隔），**须与 AstrBot 侧 `mc_wake_prefixes` 一致**否则唤不醒 AI |

> `server-name` 是身份，**显示名不在这里控制**——QQ 群前缀、`{server}` 占位符、
> AI 上下文里的服务器名统一由 AstrBot 侧的 `server_display_names` 决定（没配则显示 `MC`）。

**键名与默认值与 Fabric 端的 `config/netherlink.json` 逐字对齐**——换端时配置可以直接照搬。

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
| 聊天 / 进服 / 退服 / 死亡上报 | ✅ 已验证 |
| 成就上报 | ✅ 已验证 |
| QQ → 游戏广播（§ 染色码渲染） | ✅ 已验证 |
| **Folia 核心** | ✅ **已实机验证**（Folia 26.2） |
| Purpur | ✅ 可运行（Paper 分支，API 与事件一致） |

---

## 从源码构建

需要 **JDK 25** 与 **Gradle 9.x**。

```bash
cd netherlink-plugin
./build.cmd          # Windows
```

产物：`build/libs/netherlink-plugin-0.1.1.jar`

---

## 许可

[MIT License](LICENSE)
