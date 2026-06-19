# Wandscape 共享 API

文档编号：NEW-01
版本：1.0
状态：全模块共享接口、事件、数据类型定义
依赖：无

---

## 一、职责边界

本模块是 Wandscape 的唯一共享层。所有其他模块只依赖本模块。

**包含：**
- 接口定义（所有模块的对外 API 签名）
- 事件类定义（模块间通信的 Event）
- 公共数据类型（record / enum / 常量）

**不包含：**
- 任何实现代码
- 任何方块/物品/实体的注册
- 任何 GUI 代码

---

## 二、数据类型

### 2.1 行为标签

```java
// BehaviorType.java
public enum BehaviorType {
    BUILDING("building"),
    FARMING("farming"),
    MINING("mining"),
    LOGGING("logging"),
    CRAFTING("crafting"),
    GATHERING("gathering"),
    RITUAL("ritual"),
    ENTITY_INTERACTION("entity_interaction");

    private final String id;

    BehaviorType(String id) { this.id = id; }
    public String getId() { return id; }
    public static BehaviorType fromId(String id) { /* ... */ }
}
```

### 2.2 能力集

```java
// AbilitySet.java
// 不可变，由多个法杖的 behaviors 并集计算得出
public record AbilitySet(Map<BehaviorType, Integer> abilities) {
    public AbilitySet {
        abilities = Map.copyOf(abilities); // 紧凑构造器保证不可变
    }

    public static final AbilitySet EMPTY = new AbilitySet(Map.of());

    // 从多个法杖合并（取同行为最高等级）
    public static AbilitySet merge(List<WandBehaviorData> wands) { /* ... */ }

    // 是否满足某项能力需求
    public boolean satisfies(BehaviorType type, int requiredLevel) {
        return abilities.getOrDefault(type, 0) >= requiredLevel;
    }

    // 获得指定行为等级（未持有返回 0）
    public int getLevel(BehaviorType type) {
        return abilities.getOrDefault(type, 0);
    }
}
```

### 2.3 元素类型

```java
// ElementType.java
public enum ElementType {
    // 第一层
    EARTH("earth", 1),
    WOOD("wood", 1),
    WATER("water", 1),
    // 第二层
    FIRE("fire", 2),
    IRON("iron", 2),
    WIND("wind", 2),
    // 第三层
    GOLD("gold", 3),
    DIAMOND("diamond", 3),
    ENDER("ender", 3);

    private final String id;
    private final int tier; // 1=基础 2=进阶 3=稀有

    ElementType(String id, int tier) {
        this.id = id;
        this.tier = tier;
    }
    public String getId() { return id; }
    public int getTier() { return tier; }
}
```

### 2.4 元素储量

```java
// ElementStore.java (只读接口)
public interface ElementStore {
    long getAmount(ElementType type);
    default boolean has(ElementType type, long amount) {
        return getAmount(type) >= amount;
    }
    Map<ElementType, Long> getAll();
}
```

### 2.5 仓库条目

```java
// WarehouseEntry.java
public record WarehouseEntry(
    String itemId,      // 如 "minecraft:cobblestone"
    CompoundTag nbt,    // null 表示无 NBT 物品
    long count
) {}

// ItemKey.java (用于 HashMap 查找)
// 直接使用 CompoundTag 作为键——MC 的 CompoundTag 已正确实现 equals/hashCode
// record 自动生成的 equals 会逐字段比较，保证语义相同的 NBT 视为同一条目
public record ItemKey(String itemId, CompoundTag nbt) {
    public static ItemKey of(String itemId, CompoundTag nbt) {
        // copy() 防止外部后续修改影响 HashMap 查找
        return new ItemKey(itemId, nbt != null ? nbt.copy() : null);
    }
}
```

### 2.6 任务状态

```java
// TaskStatus.java
public enum TaskStatus {
    PENDING_APPROVAL,   // 待审批
    PENDING_ASSIGN,     // 待分配
    IN_PROGRESS,        // 进行中
    AWAITING_MATERIALS, // 物资等待（元素不足）
    INTERRUPTED,        // 中断
    COMPLETED           // 已完成
}
```

### 2.7 任务模板与记录

```java
// TaskTemplate.java (用于创建任务，各模块入队时构造)
public record TaskTemplate(
    BehaviorType requiredBehavior,
    int requiredLevel,
    List<AtomicStep> steps,
    int priority                         // 默认 0，数值越大优先级越高
) {}

// TaskData.java (不可变数据载体，运行时状态)
public interface TaskData {
    UUID getTaskId();
    TaskStatus getStatus();
    int getPriority();
    BehaviorType getRequiredBehavior();
    int getRequiredLevel();
    List<AtomicStep> getSteps();
    int getCurrentStepIndex();
    UUID getAssignedNpcId();        // null if unassigned
    UUID getOwnerBuildingId();      // 发布此任务的建筑
    List<InterruptRecord> getInterruptHistory();
}

// AtomicStep.java
public sealed interface AtomicStep {}
    public record OperationA(BlockPos target, BlockState source, BlockState result,
                              boolean produceDrops, Map<ElementType, Long> elementCost) implements AtomicStep {}
    public record OperationB(UUID buildingId, String action,
                             Map<String, Object> params) implements AtomicStep {}
    public record OperationC(UUID targetEntityId, String effectId, int intensity, int durationTicks) implements AtomicStep {}
    public record OperationD(UUID buildingId, String ritualId, int channelTicks, boolean needsAltar,
                             long manaCost, Map<ElementType, Long> elementCost) implements AtomicStep {}

// InterruptRecord.java
public record InterruptRecord(UUID npcId, long timestamp) {}
```

### 2.8 建筑数据

```java
// BuildingData.java
public interface BuildingData {
    UUID getBuildingId();
    String getBuildingTypeId();     // JSON 配置中的 ID
    String getCategory();           // basic / node / functional / wonder（小地图颜色映射用）
    BlockPos getPosition();
    boolean isShutdown();           // 是否关停
    int getComfort();               // 舒适值贡献
    int getMagic();                 // 魔法值贡献
    int getWonder();                // 奇观值贡献
    int getMaintenanceCost();       // 每周期木元素消耗
    int getQueueCapacity();         // 队列容量
}
```

### 2.9 招募候选人

```java
// RecruitmentCandidate.java
// 酒馆生成的可招募 NPC 预览数据。在管理面板展示，玩家三选一。
public record RecruitmentCandidate(
    int level,
    int maxHealth,
    int maxMana,
    int spellPower,
    int manaRegen,
    List<String> starterWandIds     // 法杖预设 ID 列表，空列表 = 无法杖
) {}
```

### 2.10 NPC 数据

```java
// NpcData.java
public interface NpcData {
    UUID getNpcId();
    String getName();
    int getMaxHealth();
    int getCurrentHealth();
    int getMaxMana();
    int getCurrentMana();
    int getSpellPower();
    int getManaRegenRate();
    AbilitySet getAbilities();      // 当前携带法杖的能力并集
    boolean isIdle();
    UUID getAssignedHouseId();      // null if unassigned
    UUID getCurrentTaskId();        // null if idle
    boolean isDead();
    UUID getGraveBlockEntityId();   // null if alive
}
```

---

## 三、核心接口

### 3.1 模块 API 接口

```java
// 每个模块对外暴露一个接口，定义在 shared-api 中，实现在各自模块中

// 法杖系统
public interface WandApi {
    AbilitySet computeAbilities(List<ItemStack> wands);
    WandBehaviorData getBehaviorData(ItemStack wand);
    int getBehaviorLevel(ItemStack wand, BehaviorType type);
    String getWandColor(ItemStack wand);
    float getManaCostMultiplier(ItemStack wand);
    int getRange(ItemStack wand);
}

// 元素系统
public interface ElementApi {
    ElementType fromId(String id);
    int getTier(ElementType type);
    List<ElementType> getByTier(int tier);
}

// 仓库系统
public interface WarehouseApi {
    long getElement(UUID colonyId, ElementType type);
    Map<ElementType, Long> getAllElements(UUID colonyId); // 面板轮询用
    boolean consumeElement(UUID colonyId, ElementType type, long amount);
    void addElement(UUID colonyId, ElementType type, long amount);
    long getItemCount(UUID colonyId, ItemKey key);
    boolean extractItem(UUID colonyId, ItemKey key, long count, Inventory target);
    void insertItems(UUID colonyId, List<ItemStack> items);
}

// 魔力池
public interface ManaPoolApi {
    long getMana(UUID colonyId);
    long getMaxMana(UUID colonyId);
    boolean consumeMana(UUID colonyId, long amount);    // 返回 false 表示不足
    boolean addMana(UUID colonyId, long amount);         // 返回 false 表示超上限
}

// 房屋
public interface HouseApi {
    UUID getAssignedNpc(UUID houseId);                  // null = 空置
    boolean isOccupied(UUID houseId);
    boolean assignNpc(UUID houseId, UUID npcId);        // 返回 false 表示已有人
    boolean unassignNpc(UUID houseId);
    List<UUID> getVacantHouses(UUID colonyId);
}

// 酒馆
public interface TavernApi {
    List<RecruitmentCandidate> getCandidates(UUID tavernId);
    boolean refreshCandidates(UUID tavernId);   // 消耗资源刷新 3 个候选人
    boolean recruitCandidate(UUID tavernId, int index); // 选择候选人入队招募任务
}

// 任务系统
public interface TaskApi {
    UUID publishTask(TaskTemplate template, UUID colonyId);
    boolean approveTask(UUID taskId);
    boolean cancelTask(UUID taskId);
    boolean suspendTask(UUID taskId);
    List<TaskData> getTasksByStatus(UUID colonyId, TaskStatus status);
    TaskData getTask(UUID taskId);
    // 建筑队列相关
    UUID enqueueBuildingTask(UUID buildingId, TaskTemplate template);
    List<UUID> getBuildingQueue(UUID buildingId);
    boolean reorderBuildingQueue(UUID buildingId, int fromIndex, int toIndex);
}

// NPC 系统
public interface NpcApi {
    List<NpcData> getColonyNpcs(UUID colonyId);
    List<NpcData> getIdleNpcs(UUID colonyId);
    NpcData getNpc(UUID npcId);
    boolean assignHouse(UUID npcId, UUID houseId);
    UUID spawnNpc(UUID colonyId, BlockPos pos, RecruitmentCandidate candidate);
}

// 建筑系统
public interface BuildingApi {
    BuildingData getBuilding(UUID buildingId);
    BuildingData getBuildingAt(BlockPos pos);
    List<BuildingData> getColonyBuildings(UUID colonyId);
    boolean shutdown(UUID buildingId);
    boolean restart(UUID buildingId);
    int getColonyComfort(UUID colonyId);
    int getColonyMagic(UUID colonyId);
    int getColonyWonder(UUID colonyId);
    boolean isBuildingOccupied(UUID buildingId); // 是否有进行中任务
}

// 原子操作执行器
public interface AtomicExecutor {
    CompletableFuture<ExecutionResult> executeA(OperationA op, UUID npcId);
    CompletableFuture<ExecutionResult> executeB(OperationB op, UUID npcId);
    CompletableFuture<ExecutionResult> executeC(OperationC op, UUID npcId);
    CompletableFuture<ExecutionResult> executeD(OperationD op, UUID npcId);
}

// 殖民地管理
public interface ColonyApi {
    UUID createColony(BlockPos townHallPos);
    UUID getColonyId(BlockPos pos);      // 根据坐标查找所属殖民地
    void deleteColony(UUID colonyId);
    boolean isColonyBlock(BlockPos pos);
}
```

### 3.2 API 注册与查询

```java
// WandscapeApis.java
// 各模块在初始化时注册自己的 API 实现。
// 使用静态注册表而非 DI 容器——MC 模组加载由 NeoForge 生命周期保证顺序，
// 简单直接，无运行时依赖注入开销。单元测试时直接 set mock 即可。
public class WandscapeApis {
    private static WandApi wandApi;
    private static ElementApi elementApi;
    private static WarehouseApi warehouseApi;
    private static TaskApi taskApi;
    private static NpcApi npcApi;
    private static BuildingApi buildingApi;
    private static HouseApi houseApi;
    private static TavernApi tavernApi;
    private static AtomicExecutor atomicExecutor;
    private static ColonyApi colonyApi;

    // getter/setter 方法...
    // 未注册时抛 IllegalStateException("Module X not loaded")
}
```

---

## 四、事件定义

### 4.1 殖民地事件

```java
public class ColonyCreatedEvent extends Event {
    private final UUID colonyId;
    private final BlockPos townHallPos;
    // constructor / getter
}
```

### 4.2 任务事件

```java
public class TaskPublishedEvent extends Event {
    private final UUID taskId;
    private final UUID colonyId;
}
public class TaskAssignedEvent extends Event {
    private final UUID taskId;
    private final UUID npcId;
}
public class TaskCompletedEvent extends Event {
    private final UUID taskId;
    private final UUID npcId;
}
public class TaskInterruptedEvent extends Event {
    private final UUID taskId;
    private final UUID npcId;
    private final String reason; // "mana_empty" / "damaged" / "recalled"
}
public class TaskAwaitingMaterialsEvent extends Event {
    private final UUID taskId;
    private final ElementType missingElement;
    private final long required;
    private final long available;
}
```

### 4.3 建筑事件

```java
public class BuildingPlacedEvent extends Event {
    private final UUID buildingId;
    private final UUID colonyId;
    private final String buildingTypeId;
}
public class BuildingShutdownEvent extends Event {
    private final UUID buildingId;
}
public class BuildingRestartedEvent extends Event {
    private final UUID buildingId;
}
public class MaintenanceTickEvent extends Event {
    private final UUID colonyId; // 殖民地级别的维护心跳
}
```

### 4.4 NPC 事件

```java
public class NpcDiedEvent extends Event {
    private final UUID npcId;
    private final BlockPos deathPos;
    private final CompoundTag graveData;
}
public class NpcResurrectedEvent extends Event {
    private final UUID npcId;
    private final UUID altarId;
}
public class NpcRecruitedEvent extends Event {
    private final UUID npcId;
    private final UUID tavernId;
}
```

### 4.5 元素事件

```java
public class ElementChangedEvent extends Event {
    private final UUID colonyId;
    private final ElementType type;
    private final long newAmount;
    private final long delta;
}
```

---

## 五、常量定义

```java
public class WandscapeConstants {
    // 调度器心跳间隔 (tick)
    public static final int SCHEDULER_HEARTBEAT_TICKS = 40; // 2秒

    // 维护结算周期 (tick)
    public static final int MAINTENANCE_INTERVAL_TICKS = 20 * 60 * 20; // 20分钟

    // 默认 NPC 属性
    public static final int DEFAULT_NPC_MAX_HEALTH = 40;
    public static final int DEFAULT_NPC_MAX_MANA = 100;
    public static final int DEFAULT_NPC_SPELL_POWER = 1;
    public static final int DEFAULT_NPC_MANA_REGEN = 2; // 每 tick

    // 房屋魔力恢复加成（乘数）
    public static final float HOUSE_MANA_REGEN_MULTIPLIER = 3.0f;

    // 默认法杖属性
    public static final float DEFAULT_MANA_COST_MULTIPLIER = 1.0f;
    public static final int DEFAULT_WAND_RANGE = 1;

    // 调度器：同建筑连续执行评分加成
    public static final double SAME_BUILDING_CONTINUATION_BONUS = 50.0;

    // 建筑队列容量
    public static final int QUEUE_TOWNHALL = 5;
    public static final int QUEUE_WORKSTATION = 60;
    public static final int QUEUE_CRAFTING = 60;
    public static final int QUEUE_POTION = 10;
    public static final int QUEUE_RITUAL_ALTAR = 10;
    public static final int QUEUE_NODE = 10;
    public static final int QUEUE_HOUSE = 5;
    public static final int QUEUE_MANA_POOL = 10;
    public static final int QUEUE_TAVERN = 5;

    // 默认工作站引导时间 (tick)
    public static final int WORKSTATION_CRAFT_TICKS = 1200;   // 60秒
    public static final int WORKSTATION_DECOMPOSE_TICKS = 1200;

    // 操作射程 (方块)。NPC 法杖操作的最大距离，超距自动传送
    public static final int BASE_OPERATION_RANGE = 16;
    public static final int PER_WAND_LEVEL_RANGE = 8;  // 每级法杖 range 额外距离

    // 殖民地默认半径 (方块)。小地图显示范围、NPC 活动范围参考
    public static final int DEFAULT_COLONY_RADIUS = 128;

    // NPC 步行阈值 (方块)。小于此距离尝试寻路步行，大于等于直接传送
    public static final int NPC_WALK_THRESHOLD = 64;

    // 卡死检测间隔 (tick)。每这么多 tick 检查一次 NPC 是否在寻路中有进展
    public static final int STUCK_CHECK_INTERVAL_TICKS = 60;  // 3秒
    // 卡死判定最小移动距离 (方块)。在此间隔内移动距离小于此值视为无进展
    public static final double STUCK_MIN_MOVE_DISTANCE = 2.0;
    // 卡死判定连续无进展次数。达到此次数后判定卡死 → 传送
    public static final int STUCK_MAX_RETRIES = 3;            // 3×3秒=约10秒
}
}
```

---

## 六、JSON 配置核心约束

所有 JSON 配置统一放在 `data/wandscape/` 下，使用 NeoForge 的 `JsonCodec` 注册。

```java
// 数据驱动注册接口
// 各模块的 JSON 配置通过此接口查询
public interface WandscapeDataRegistry<T> {
    T get(String id);
    Map<String, T> getAll();
    boolean contains(String id);
}
```

---

## 七、本模块交付物

- `shared/api/` 目录：所有接口、事件、枚举
- 无实现代码
- 无测试（纯接口定义，编译通过即可）
- jar 通过 `api` scope 暴露给所有子模块
