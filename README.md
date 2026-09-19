# Wandscape

<p align="center"><img src="minecraft_title.png" alt="Wandscape — 魔法小镇" width="70%"></p>

**中文** | [English](README.en.md)

Minecraft NeoForge 1.21.1 模组 — **《魔法小镇》**（Wandscape）。**服务端与客户端都需安装。**

> **你是市长，法师干活。**

## 简介

**魔法小镇**（Wandscape）是一款融合了魔法，城镇建设与模拟经营的大型模组。玩家扮演市长，派遣法师建造建筑，抵御敌人，从空地上建起一座繁盛的魔法小镇。

## 玩法线

**模拟经营：** 招待游客，赚取利润，提升人气

**冒险探索：** 制作法杖，编排魔法，外出探索

## 特色内容

- **庞大建筑体系：** 10大类50+建筑，支持导入自定义建筑。
- **七元素经济系统：** 降低建设与经营难度。
- **统一管理面板：** 建造、铺路、交互一站式完成，自带飞行模式。
- **离线收益：** 服务器不关，挂机也能有收益。
- **轻量化体验：** 稀有建材可合成，法师可复活，新手友好。
- **战斗编排：** 八种魔法，大量战斗道具，支持精确到人的策略编排。

## 开发环境

```bash
./gradlew runClient            # 启动测试客户端（首次或报错时先执行 neoForgeIdeSync）
./gradlew build                # 编译（项目不维护单元测试，build 即全量验证）
./gradlew neoForgeIdeSync      # 首次运行前 / runClient 报 clientRunVmArgs.txt 缺失时执行
```

- **MC 版本**：1.21.1
- **NeoForge**：21.1.233（最低 21.1.1）
- **Mappings**：Parchment 2024.11.17
- **JDK**：21+

## 项目结构

```
src/main/java/com/wsteam/wandscape/   # 源码：content/<13 个功能域>、foundation/ 基建、api/ 契约、compat/ 集成、impl/ 装配
src/main/resources/data/wandscape/    # JSON 配置（建筑/配方/元素映射/蓝图/魔法/叙事/标签）
src/main/resources/assets/wandscape/  # 资源（语言 en_us/zh_cn、模型、纹理、音效）
docs/                                 # 开发者文档与数据格式（导航见 docs/README.md，以代码为准）
```

## 设计原则

1. **高兼容性** — 不修改原版行为，纯新增内容与机制；JSON 数据驱动，方块映射用标签，`/reload` 热重载
2. **按功能聚合，直接协作** — 一个功能域一个包；跨域直接调用业务类，不建搭桥层、不滥用事件解耦
3. **稳定优先** — 所有失败路径有兜底，不崩溃、不卡死；关键设施（市政厅/仓库/工作站）拆到只剩最后一座时受保护
4. **纯逻辑与 MC 解耦** — 核心算法、蓝图解析、任务评分不依赖 Minecraft 类，保持可移植
5. **不折磨玩家** — 远程管理面板，NPC 自动干活，一切可恢复
6. **性能优化** — 物流用单实体视觉合并 + 气泡计数，避免大量实体掉落掉帧

## 许可证

MIT License — 详见 [LICENSE](LICENSE)
