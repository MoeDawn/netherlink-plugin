# 更新日志

> 本文件用于 GitHub Release 的说明。格式参考 AstrBot 侧插件的 `CHANGELOG.md`。

## 未发布

> 下面这项**已修复但尚未发版**——等确定版本号时把本小节标题改成正式版本号即可。

### 修复

- **根成就被误判成「玩家获得的成就」上报给 AI**。过滤条件写的是
  「`display` 为空」，但**实测（在 NeoForge 端跑真服验证）**：

  > `minecraft:story/root` **是有 display 的**——那正是成就页签的图标与标题。
  > 所以那条判据**根本过滤不掉它**：玩家一进服就触发的根成就被当成
  > 「获得成就」上报，让 AI 无意义地加好感，还会在群里刷一条成就播报。

  判据改为「**有没有父成就**」；原来的 `display` 检查**保留**，
  它挡的是另一类（有父但没有展示信息的成就）。

## v0.1.0

### 新增

- **支持 Folia**：改用 Paper/Folia 共用的调度器（`GlobalRegionScheduler` /
  `AsyncScheduler`），并在 `paper-plugin.yml` 声明 `folia-supported: true`。
  官方文档明确这两套调度器在普通 Paper 上会被内部接管、行为一致，
  所以**同一份 jar 三种服务端通用**，无需分支判断。
- **支持 Purpur**：它是 Paper 的分支，API 与事件完全一致。

### 修复

- **多台服务器在线时执行指令会误报「超时」**：服务器端侧不受影响，但这是
  指令链路里最关键的一环——详见 AstrBot 侧插件的说明。

### 变更

- 产物名由 `netherlink-server-0.1.0.jar` 改为 **`netherlink-plugin-0.1.0.jar`**
  （与仓库名一致）。
  ⚠️ 插件标识 `name: netherlink` **不变**，已装服的配置无需改动。

### 环境要求

- Minecraft **26.3**，服务端为 **Paper / Purpur / Folia**
- **Java 25**
- 已装好并运行 AstrBot 上的 NetherLink 插件

### 不支持

- **Spigot**：缺少 Paper 专有的扩展接口（用于捕获指令输出），这不是调度器层面
  能解决的。
- **NeoForge**：尚未移植。**Fabric** 有单独的实现。

## v0.0.2

- 指令执行改用官方 `Bukkit.createCommandSender`，修复原生指令（give/tp/list/time…）
  执行时抛 `Cannot make ... a vanilla command listener` 的问题。
- `command_result` 的 `ok` 如实上报，执行异常不再被伪装成成功。

## v0.0.1

- 首个版本：WebSocket 长连、聊天/进退服/死亡/成就上报、QQ→MC 消息广播、以控制台身份执行指令。
