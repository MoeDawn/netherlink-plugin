# 更新日志

> 本文件用于 GitHub Release 的说明。格式参考 AstrBot 侧插件的 `CHANGELOG.md`。

## v0.1.2

NetherLink 的 MC 端 **Paper / Purpur / Folia** 插件——配套 AstrBot 上的 NetherLink 插件使用。

### 新增

- **本地菜单**：公屏发 `菜单` / `menu` / `メニュー`，私聊弹出一张可点击菜单，点按钮传送。
  **全程在本端完成，不经 AstrBot、不消耗好感、离线也能用**。
  两项：传送到玩家、传送到死亡点（都免费）。
- **确认卡渲染**：AstrBot 发来 `action_offer` 后由本端渲染卡片并执行
  —— 按钮用 Paper 原生点击回调，**不用计分板**（无多余回显、点了立即生效）。

### 变更

- 物品的 `check` / `clear` / `give` / `modify` **全部改用 Bukkit API**，
  不再下发 `/clear` `/give` 指令。顺带消掉 `/clear` 数量不够时「整摞清光」的行为。
- 执行前多一道**预校验**：材料够不够、组件造不造得出来、槽位空不空——
  任一不过就整体中止，**一个都不动**。

### 修复

- **改物品会把原有附魔、名字、说明全清掉**。`modify` 步骤原先是
  `buildItem(原物品 id, 原数量, 你要设的组件)` —— **整体替换**，
  于是「给手上这把剑改名」会把剑的锋利 V 与 lore 一起抹掉。
  改为**合并**：你没点名的组件一律保留玩家原有的（用 Paper 的
  `ItemStack.copyDataFrom` + `isDataOverridden`，后者区分
  「显式写上去的组件」与「物品类型自带的默认值」）。
  ⚠️ 例外：**附魔是整套替换**（写 `enchantments` 就换掉整组），与原版一致。
- **收材料时可能收走错的那一件**。`clear` 原先只按物品**类型**匹配，
  于是「把这把附魔剑升级一下」有可能收走背包里另一把同 id 的普通剑。
  改为：**给了组件就按组件精确匹配**（`ItemStack.isSimilar`，
  忽略数量、尊重组件），没给组件时保持只比类型的老行为。
  `countItem` 与 `takeItems` 共用同一判据。
- 菜单提示里的菱形符号显示成字面量（转义多写了一层）。
- 主菜单项没有悬浮说明。
- `/trigger` 用完会自锁，导致确认卡第二张点不动。

## v0.1.1

NetherLink 的 MC 端 **Paper / Purpur / Folia** 插件——配套 AstrBot 上的 NetherLink 插件使用。

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
- **NeoForge**：见 [NeoForge 版](https://github.com/MoeDawn/netherlink-neoforge)。
  **Fabric** 见 [Fabric 版](https://github.com/MoeDawn/netherlink-fabric)。
  三者与本节插件**协议完全相同**，接同一个 AstrBot 插件，服务端不用改配置。

## v0.0.2

- 指令执行改用官方 `Bukkit.createCommandSender`，修复原生指令（give/tp/list/time…）
  执行时抛 `Cannot make ... a vanilla command listener` 的问题。
- `command_result` 的 `ok` 如实上报，执行异常不再被伪装成成功。

## v0.0.1

- 首个版本：WebSocket 长连、聊天/进退服/死亡/成就上报、QQ→MC 消息广播、以控制台身份执行指令。
