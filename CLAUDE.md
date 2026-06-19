# Wandscape — CLAUDE.md

Minecraft NeoForge 1.21.1 模组。殖民地自动化管理：NPC 法师通过法杖执行原子操作，建造建筑、采集元素、合成物品。

## 构建命令

```bash
./gradlew build          # 编译
./gradlew runClient      # 启动测试客户端
./gradlew runServer      # 启动测试服务端
./gradlew test           # 运行单元测试
```

## 架构

- **两层依赖**：所有模块只依赖 `01-shared-api`（接口层）和/或 `08-building-core`（建筑基类）。模块间通过 EventBus 事件和 API 接口通信，不直接引用。
- **数据驱动**：建筑、法杖、配方、元素映射全部 JSON 定义（`data/wandscape/`）。
- **接口先行**：每个模块对外暴露接口（在 01 中定义），实现类 internal 不对外暴露。

### 模块地图

| 编号 | 模块 | 一句话 |
|------|------|--------|
| 01 | shared-api | 共享接口、事件、数据类型 |
| 02 | wand-system | 法杖物品 + NBT + 能力并集 |
| 03 | element-system | 三层 9 种元素 + 方块→元素映射 |
| 04 | warehouse-system | 元素 + 物品统一存储 + GUI |
| 05 | atomic-operations | 四种原子操作 A/B/C/D |
| 06 | task-system | 全局任务池 + 调度器(2s) + 私有池 |
| 07 | npc-system | NPC 实体 + 魔力 + 死亡/复活 |
| 08 | building-core | 建筑 JSON 注册 + 三数值 + 维护 + 队列 |
| 09 | node-building | 节点建筑 → 发布采集任务产出元素 |
| 10 | production-stations | 制作站 + 万能工作站 + 魔药站 |
| 11 | housing-mana-pool | 房屋分配 + 魔力池充能/抽取 |
| 12 | tavern-recruitment | 酒馆 + NPC 招募(舒适值限制) |
| 13 | ritual-altar | 多方块祭坛 + 复活仪式 |
| 14 | management-panel | 远程管理面板 + 小地图 + 远程建造 |
| 15 | colony-lifecycle | 殖民地创建/删除 + 开局引导 |
| 16 | data-driven-config | 全部 JSON 配置格式规范 |

### 关键设计决策

- **全 NPC 默认 ritual:1**：与装备无关，复活后仍生效。保证所有 NPC 可执行物资传送等基础物流操作。
- **维护成本负数→自动关停**：元素储量扣至负数后建筑自动关停（使用时间加倍、产出减半），避免经济崩溃。
- **NPC 移动**：<64 方块尝试寻路步行（卡死检测兜底），≥64 / 寻路失败 / 卡死 → 入队私有 self_teleport 任务（Operation D，消耗由仪式 JSON 定义）。魔力不足原地等恢复（不中断任务）。
- **操作射程**：`16 + wand.range × 8` 方块。超距自动传送后执行。
- **连续执行优化**：NPC 完成建筑任务后可直接接同建筑下一个任务，跳过全局池匹配。但会在接取前预判魔力是否充足。
- **三数值首次建造永久标记**：每种建筑类型首次建造贡献数值，拆除后重建不重复贡献。

### 文档

完整设计文档在 `docs/new/`，编号 00 到 18。99 为审查问题汇总与解决状态。

## 代码约定

- 包名 `com.wsteam.wandscape`
- 模块内实现类为 `internal` 可见性
- NeoForge `DeferredRegister` 注册物品/方块/实体/BE/菜单
- `/reload` 支持热重载所有 JSON 配置
- 绝对不洗 NBT：完全使用 Minecraft 原生 NBT 序列化
