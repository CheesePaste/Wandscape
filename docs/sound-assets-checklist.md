# 音频素材清单（待找）

自定义音效的待办清单。找到后把 `.ogg` 放到对应路径，**不用改代码**——`sounds.json` 的引用路径我会在收到文件后更新（设计见 [sounds.md](sounds.md)）。

## 音频规格

- 格式：`.ogg Vorbis`（原版唯一支持的格式），16-bit 44.1kHz
- 声道：**mono** 推荐（利于 3D 定位）；左右声道差异小的也可以 stereo
- 时长：短反馈 0.5–2s；swoosh 1–1.5s；环境/和声 2–4s
- 响度：峰值留 -6 ~ -12 dB 余量（代码里还会乘 volume）
- 路径：`src/main/resources/assets/wandscape/sounds/<分类>/<名>.ogg`，全小写下划线
- 命名必须是下表"目标路径"栏的名称，否则要改 `sounds.json`

## P0 玩家直接操作（优先找这 7 个）

- [ ] `sounds/magic/cast.ogg` — 法杖/命令施法起手。幽森钟鸣 + 上升滑音，约 1.5s
- [ ] `sounds/magic/beam.ogg` — 法阵动画结束、光束发射。能量嗡鸣爆射瞬态，约 1s
- [ ] `sounds/building/place.ogg` — 投影确认放置建筑蓝图（任务已提交）。短促确认音，约 0.5s
- [ ] `sounds/projection/enter.ogg` — 进入投影（灵魂出窍）模式。传送 swoosh，约 1.2s
- [ ] `sounds/projection/exit.ogg` — 退出投影模式。可与 enter 同源变调
- [ ] `sounds/overview/enter.ogg` — 进入俯瞰视角。扬升 swoosh，约 1.2s
- [ ] `sounds/warehouse/transfer.ogg` — 仓库元素存取。金属叮/铃，约 0.5s

## P1 NPC / 自动行为

- [ ] `sounds/npc/cast.ogg` — NPC 施法放置每块方块。法师挥杖短施法音，约 0.8s
- [ ] `sounds/task/publish.ogg` — 玩家手动创建任务（铺路/填充/投影建筑）。放卷轴/纸卷声，约 0.8s
- [ ] `sounds/guard/fire.ogg` — 守卫开火。能量脉冲，约 0.8s
- [ ] `sounds/building/placed.ogg` — NPC 施工整栋建成。沉稳确认/钟鸣，约 1.5s
- [ ] `sounds/building/demolished.ogg` — 建筑拆除。崩塌，约 1.5s
- [ ] `sounds/tourist/arrive.ogg` — 游客到达。轻快入城音，约 0.8s
- [ ] `sounds/tourist/depart.ogg` — 游客离开。渐弱，约 0.8s
- [ ] `sounds/shop/restock.ogg` — 商店补货。金币/货架声，约 0.8s

## P2 模拟经营 / 全局（低频）

- [ ] `sounds/building/shutdown.ogg` — 维护费不足建筑关停。低沉闷响，约 1.5s
- [ ] `sounds/building/restart.ogg` — 建筑恢复运行。上升启动音，约 1.5s
- [ ] `sounds/colony/level_up.ogg` — 殖民地升级。庄严升级音，约 2s
- [ ] `sounds/building/wonder.ogg` — 奇观效果应用/移除。神圣和声，约 2.5s

## 不用找的（直接用原版）

- GUI 按钮点击 → 原版 `UI_BUTTON_CLICK`
- 方块放置 → 方块自身原版放置音（`SoundType.getPlaceSound`）
- 法杖右键敲钟 → 已用原版 `AMETHYST_BLOCK_CHIME`

## 放入流程

1. 把 `.ogg` 放到上表路径（没有子目录就新建）。
2. 告诉我放好了，或直接把 `sounds/` 目录提交。
3. 我更新 `sounds.json` 把占位引用（现在指向 `minecraft:` 原版音）换成你的文件。
4. 跑 `./gradlew runClient` 逐个试听，响度/时长不合适再调。
