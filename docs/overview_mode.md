# 俯瞰概述模式设计方案

## 用户需求

在现有 V 键面板的基础上，增加一个**俯瞰模式（Overview Mode）**，作为面板的第二操作模式：

| 需求 | 说明 |
|------|------|
| 触发方式 | **V 打开面板默认进入俯瞰模式**；面板打开时按 **G** 键切换地面模式 ↔ 俯瞰模式 |
| 摄像机 | 自由飞行，摄像机与玩家实体完全解耦（玩家原地不动，摄像机独立移动） |
| 移动 | WASD 前后左右平移（水平面，沿摄像机朝向） |
| 视角 | 鼠标拖动旋转，与正常游戏一致 |
| 缩放 | 滚轮改变 FOV |
| 交互 | 右键：无建筑选中 → 射线交互建筑；有建筑选中 → 放置建筑 |
| 左键 | 无效果（不攻击/不破坏） |
| 物品栏 | 不渲染（热键栏、手中物品均隐藏） |
| C 键 | 与现有行为一致：抬升鼠标到面板层，可点按钮/选建筑 |
| ESC | **不**退出俯瞰模式 |
| G | **仅面板打开时有效**，切换地面/俯瞰模式 |
| 建筑高亮 | 渲染建筑包围盒全部 12 条边（白色 #FFFFFFFF 半透明） |

### V 键行为总结

```
V (面板关闭时) → 打开面板 + 默认进入俯瞰模式
V (面板打开时) → 关闭面板 + 退出俯瞰模式

G (面板打开+俯瞰模式) → 切换到地面模式 (BUILD_PROJECTION)
G (面板打开+地面模式)  → 切换到俯瞰模式 (OVERVIEW)
G (面板关闭)          → 无效果

C (面板打开) → 抬升/释放鼠标到面板层（现有行为不变）
```

---

## 实现方案

### 核心原则

**纯客户端俯瞰 + 网络包触发服务端交互**。俯瞰模式下：
- 玩家实体**原地不动**（不切换旁观者模式，不移动玩家）
- 摄像机位置/旋转通过**反射**覆盖（`Camera.setPosition` / `setRotation`，均为 `protected` 方法）
- 玩家输入被屏蔽（`MovementInputUpdateEvent` 清零）
- 物品栏/热键栏不渲染（由 V 面板打开时已有的逻辑处理）
- 右键交互：有建筑选中 → 走 `ProjectionPlacePacket` 放置；无选中 → 走 `OverviewInteractPacket` 触发 `BuildingInteractHandler`

### 为什么选反射而非 Mixin

| 方式 | 本项目是否可用 | 理由 |
|------|---------------|------|
| Mixin | ❌ | 项目无 Mixin 依赖和构建配置，引入成本高 |
| 旁观者模式 | ❌ | 需服务端切换模式+同步，玩家模型暴露 |
| Attribute 覆写 | ❌ | `Camera.setPosition/setRotation` 均为 `protected`，不可覆写 |
| **反射** | ✅ | `ObfuscationReflectionHelper` 原生支持；Mojang 映射下方法名稳定 |

---

## 新模块：`overview/` 包

在 `projection/` 同级新建：

```
overview/
├── client/
│   ├── OverviewClientState.java       # 静态状态：位置/旋转/FOV/激活标志/目标建筑
│   ├── OverviewFlightController.java   # 核心：每tick物理+摄像机override+输入仲裁+射线检测
│   └── OverviewRenderer.java          # 世界空间渲染：建筑高标杆（全包围盒白色线框）
├── network/
│   └── OverviewInteractPacket.java    # C→S：请求与建筑交互的包
```

---

## 详细设计

### 1. OverviewClientState

```java
public final class OverviewClientState {
    private static volatile boolean active = false;   // 是否处于俯瞰模式
    private static double camX, camY, camZ;            // 自由摄像机位置
    private static float camYaw, camPitch;             // 自由摄像机旋转
    private static double fov = 1.0;                   // FOV 缩放倍率（1.0=正常）
    private static BlockPos targetBlockPos;            // 准心命中的方块位置
    private static UUID targetBuildingId;              // 命中的建筑ID（null=无命中）
    // 进入前保存的玩家位置/旋转（退出时重置摄像机参考位置）
    private static double prevX, prevY, prevZ;
    private static float prevYaw, prevPitch;
}
```

方法：
- `enterOverview()` — 初始化位置在玩家头顶 +20，俯角 90°
- `exitOverview()` — 清状态，不移动玩家

### 2. OverviewFlightController（核心）

注册以下事件：

| 事件 | 用途 |
|------|------|
| `ClientTickEvent.Post` | 物理更新 + 射线检测（摄像机覆写在 RenderLevelStageEvent 中） |
| `RenderLevelStageEvent.AFTER_SKY` | 摄像机覆写（Camera.setup() 已执行完毕） |
| `MovementInputUpdateEvent` | 清零玩家输入（阻止实体移动） |
| `InputEvent.MouseScrollingEvent` | FOV 缩放 |
| `InputEvent.MouseButton.Pre` | 捕获右键 |
| `ViewportEvent.ComputeFov` | 应用 FOV 缩放 |

#### 2.1 激活/停用

```java
public static void enterOverview() {
    Minecraft mc = Minecraft.getInstance();
    // 保存玩家当前位置
    prevX = mc.player.getX(); prevY = mc.player.getY(); prevZ = mc.player.getZ();
    prevYaw = mc.player.getYRot(); prevPitch = mc.player.getXRot();
    // 摄像机从玩家头顶 +20 开始，俯视
    camX = prevX; camY = prevY + 20; camZ = prevZ;
    camPitch = 90; camYaw = prevYaw;
    fov = 1.0;
    active = true;
}

public static void exitOverview() {
    if (!active) return;
    active = false;
    camYaw = camPitch = 0;
    // 不移动玩家，不移除任何状态
}
```

#### 2.2 每 tick 物理更新

在 `ClientTickEvent.Post` 中：

```java
if (!active) return;

// Step 1: 读取 WASD（直接从 GLFW，绕过 player.input）
long window = mc.getWindow().getWindow();
float forward = 0, strafe = 0;
if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_W) == GLFW.GLFW_PRESS) forward += 1;
if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_S) == GLFW.GLFW_PRESS) forward -= 1;
if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_A) == GLFW.GLFW_PRESS) strafe -= 1;
if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_D) == GLFW.GLFW_PRESS) strafe += 1;

// Step 2: 鼠标旋转 — 计算 cursor delta
// 保存上一帧鼠标位置，每 tick 算 offset → camYaw/camPitch
// camPitch = Mth.clamp(camPitch, -90, 90)

// Step 3: 水平移动
Vec3 forwardVec = Vec3.directionFromRotation(0, camYaw); // 纯水平前向
Vec3 rightVec = forwardVec.cross(new Vec3(0, 1, 0)).normalize();
float speed = OVERVIEW_SPEED;
camX += (forwardVec.x * forward + rightVec.x * strafe) * speed;
camZ += (forwardVec.z * forward + rightVec.z * strafe) * speed;

// Step 4: 射线检测
raycastBuilding(mc);
```

**鼠标旋转实现**：用 `GLFW.glfwGetCursorPos()` 获取当前帧和上一帧的差值，转换为角度变化。

#### 2.3 摄像机覆写

```java
// RenderLevelStageEvent.AFTER_SKY 中
if (!active) return;

Camera camera = mc.gameRenderer.getMainCamera();
try {
    Method setPos = Camera.class.getDeclaredMethod("setPosition", double.class, double.class, double.class);
    setPos.setAccessible(true);
    setPos.invoke(camera, camX, camY, camZ);

    Method setRot = Camera.class.getDeclaredMethod("setRotation", float.class, float.class);
    setRot.setAccessible(true);
    setRot.invoke(camera, camYaw, camPitch);
} catch (Exception e) {
    Log.warn(TAG, "Failed to override camera", e);
}
```

使用 `RenderLevelStageEvent.AFTER_SKY` 而非 `ClientTickEvent.Post`，因为 `Camera.setup()` 在渲染循环早期执行，`AFTER_SKY` 在 setup 之后、实体/方块渲染之前。

#### 2.4 FOV 缩放

```java
// InputEvent.MouseScrollingEvent
if (!active) return;
double delta = event.getScrollDeltaY();
fov = Mth.clamp(fov - delta * 0.1, 0.1, 5.0);
event.setCanceled(true);

// ViewportEvent.ComputeFov
if (!active) return;
event.setFov((float)(event.getFov() / fov)); // fov 缩小 = zoom in
```

#### 2.5 射线检测 + 右键

```java
// ClientTickEvent.Post 中每 tick 执行
private static void raycastBuilding(Minecraft mc) {
    Camera camera = mc.gameRenderer.getMainCamera();
    Vec3 origin = camera.getPosition();
    Vec3 lookVec = camera.getLookVector();

    ClipContext ctx = new ClipContext(origin, origin.add(lookVec.scale(64)),
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player);
    BlockHitResult hit = mc.level.clip(ctx);

    if (hit.getType() == HitResult.Type.BLOCK) {
        targetBlockPos = hit.getBlockPos();
        // 客户端查建筑ID（用于高亮）
        targetBuildingId = lookupBuildingId(mc.level, targetBlockPos);
    } else {
        targetBlockPos = null;
        targetBuildingId = null;
    }
}

// InputEvent.MouseButton.Pre — 右键
if (!active) return;
if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && event.getAction() == GLFW.GLFW_PRESS) {
    // 分支 1: 有建筑选中（从 Build 栏双击选择的）→ 放置建筑
    if (ProjectionClientState.isProjecting()) {
        // 复用 ProjectionFlightController 的 handlePlace 逻辑
        handlePlace(mc);
    }
    // 分支 2: 无建筑选中，射线命中建筑 → 建筑交互
    else if (targetBuildingId != null) {
        PacketDistributor.sendToServer(new OverviewInteractPacket(targetBlockPos));
    }
    event.setCanceled(true);
}
```

#### 2.6 输入仲裁

所有事件中的处理：

| 事件 | 地面模式 | 俯瞰模式 |
|------|---------|---------|
| `MovementInputUpdateEvent` | 仅光标抬起时清零 | **总是清零** |
| `InputEvent.MouseScrollingEvent` | 仅当 bar 打开时拦截 | **总是拦截** → FOV 缩放 |
| `InputEvent.MouseButton.Pre` | 面板处理 | **拦截全部**，仅处理右键 |
| `ClientTickEvent.Post` | WASD → 俯览物理，消耗攻击/使用 | WASD → 俯览物理 |

零玩家所有 vanilla 输入（攻击、使用、跳跃、潜行、丢物品、背包、冲刺）：

```java
// overview 模式激活时每一 tick 消耗
while (mc.options.keyAttack.consumeClick()) {}
while (mc.options.keyUse.consumeClick()) {}
while (mc.options.keyJump.consumeClick()) {}
while (mc.options.keyShift.consumeClick()) {}
while (mc.options.keyInventory.consumeClick()) {}
while (mc.options.keyDrop.consumeClick()) {}
while (mc.options.keySprint.consumeClick()) {}
```

### 3. OverviewInteractPacket (C→S)

```java
public record OverviewInteractPacket(BlockPos buildingBlockPos) implements CustomPacketPayload {
    // 标准 StreamCodec 和 Type

    public static void handleServer(OverviewInteractPacket packet, ServerPlayer player) {
        Level level = player.serverLevel();
        BuildingSavedData data = BuildingSavedData.get(level);
        UUID buildingId = data.getBuildingIdAt(packet.buildingBlockPos());
        if (buildingId == null) {
            buildingId = data.getBuildingIdInInteractionZone(packet.buildingBlockPos());
        }
        if (buildingId == null) return;

        BuildingState state = data.getBuilding(buildingId);
        if (state == null) return;

        // 复用 BuildingInteractHandler 的交互分类逻辑
        // 提取公共静态方法：BuildingInteractHandler.handleForCategory(player, level, pos, state)
    }
}
```

需要从 `BuildingInteractHandler.onRightClickBlock` 中提取一个公共方法，让两部分共享逻辑。

### 4. OverviewRenderer

渲染时机：`RenderLevelStageEvent.AFTER_TRIPWIRE_BLOCKS`

渲染内容：当 `targetBuildingId != null` 时，获取建筑的 `BuildingConfig.boundary()`，绘制：

**全包围盒白色线框**：建筑包围盒全部 12 条边（白色 #FFFFFFFF）。

```
底面四边形 (y=minY)：4 条边 + 顶面四边形 (y=maxY)：4 条边
+ 4 个角竖线连接底面和顶面 = 共 12 条边
```

颜色：白色 `#FFFFFFFF`（无颜色语义，仅为建筑标识）

### 5. 集成到现有面板

#### 5.1 V 键行为变更

当前 `V` 键逻辑（`WandscapeClient.onClientTick`）：

```java
while (PROJECTION_TOGGLE.consumeClick()) {
    if (WandscapePanelState.isPanelOpen()) {
        WandscapePanelState.closePanel();     // 包含 exitOverview
    } else {
        WandscapePanelState.openPanel();       // 打开面板
        OverviewFlightController.enterOverview(); // 默认进入俯瞰模式
        WandscapePanelState.setSubMode(SubMode.OVERVIEW);
    }
}
```

#### 5.2 G 键处理

在 `WandscapePanelController.onKey()` 中新增：

```java
// G key: 切换地面模式 ↔ 俯瞰模式
if (key == GLFW.GLFW_KEY_G && WandscapePanelState.isPanelOpen()) {
    if (OverviewFlightController.isActive()) {
        // 俯瞰 → 地面
        OverviewFlightController.exitOverview();
        WandscapePanelState.setSubMode(SubMode.BUILD_PROJECTION);
        // 如果还没有投影模式，需进入
        if (!ProjectionClientState.isProjecting()) {
            WandscapePanelState.enterSubMode(SubMode.BUILD_PROJECTION);
        }
    } else {
        // 地面 → 俯瞰
        if (WandscapePanelState.getActiveSubMode() != SubMode.NONE) {
            WandscapePanelState.exitCurrentSubMode();
        }
        OverviewFlightController.enterOverview();
        WandscapePanelState.setSubMode(SubMode.OVERVIEW);
    }
}
```

**注意**：G 键仅在面板打开时生效（`WandscapePanelState.isPanelOpen()`）。

#### 5.3 ESC 处理

**不拦截 ESC** — ESC 由原版或 `WandscapePanelController.onScreenOpen` 处理。俯瞰模式下 ESC 不退出模式，仅关闭面板。

#### 5.4 WandscapePanelOverlay 变更

俯瞰模式下底栏显示状态文字：

```
[俯瞰模式] | WASD 移动 | 滚轮缩放 | C 选建筑 | G 切地面
```

若有 `targetBuildingId`，在顶端显示建筑名称。

#### 5.5 C 键

C 键现有行为不变（`PANEL_CURSOR_TOGGLE`），在面板打开时：
- 俯瞰模式 + 无 BUILD_PROJECTION：C → 抬升鼠标，点击 Build 标签页进入 BUILD_PROJECTION
- BUILD_PROJECTION + C → 打开/关闭建筑选择栏（现有行为）

---

## 修改文件清单

| 文件 | 变更类型 | 改动内容 |
|------|---------|---------|
| `overview/client/OverviewClientState.java` | **新建** | 静态状态持有者 |
| `overview/client/OverviewFlightController.java` | **新建** | 核心：物理/摄像机/输入仲裁/射线 |
| `overview/client/OverviewRenderer.java` | **新建** | 建筑高标杆渲染 |
| `overview/network/OverviewInteractPacket.java` | **新建** | C→S 建筑交互包 |
| `shared/ui/panel/WandscapePanelState.java` | 修改 | 新增 `SubMode.OVERVIEW`，openPanel/closePanel 联动 |
| `shared/ui/panel/WandscapePanelController.java` | 修改 | G 键切换处理 |
| `shared/ui/panel/WandscapePanelOverlay.java` | 修改 | 俯瞰模式 HUD |
| `WandscapeClient.java` | 修改 | V 键默认进入俯瞰，注册 Overview* 监听器，注册 G 键 |
| `Wandscape.java` | 修改 | 注册 OverviewInteractPacket 网络 handler |
| `building/internal/BuildingInteractHandler.java` | 修改 | 提取公共交互方法供包复用 |

## 其他

- 使用本项目的 Log 工具类：`com.wsteam.wandscape.shared.log.Log`
- 包路径约定：`com.wsteam.wandscape.overview.client.*` / `com.wsteam.wandscape.overview.network.*`
