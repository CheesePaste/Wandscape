# 建筑 3D 预览渲染器 — 调研与实现笔记

面向在 MC 1.21.1 NeoForge GUI 中渲染建筑 3D 微型图的开发者。

## 目标

在 HUD overlay（`RenderGuiEvent.Post`）或 `Screen` 内渲染建筑的 3D 缩略图，替代原来只显示单个方块 icon 的方案。渲染器必须与具体 UI 解耦，接受 `GuiGraphics` + `BuildingConfig` + screen rect 即可工作。

## 实现方案：临时透视投影 + BlockRenderDispatcher

**核心思路**：在 2D GUI 渲染管线中临时插入 3D 透视投影，利用 `BlockRenderDispatcher.renderSingleBlock()` 逐个渲染建筑的方块，渲染完成后恢复 GUI 投影。

### 步骤

1. `RenderSystem.backupProjectionMatrix()` — 保存当前 GUI 正交投影
2. `RenderSystem.setProjectionMatrix(perspectiveMatrix, VertexSorting.DISTANCE_TO_ORIGIN)` — 设置 3D 透视
3. `RenderSystem.enableDepthTest()` — 开启深度测试保证遮挡正确
4. `RenderSystem.viewport(...)` — 将视口限定到目标矩形区域
5. `PoseStack.setIdentity()` → 构建相机（距离、旋转、倾斜、缩放、居中）
6. 方块按深度排序（back-to-front）
7. 逐个调用 `BlockRenderDispatcher.renderSingleBlock(state, pose, bufferSource, FULL_BRIGHT, NO_OVERLAY)`
8. `bufferSource.endBatch()` 三种 RenderType（cutout / translucentCull / translucentItem）
9. `PoseStack.popPose()` → `restoreProjectionMatrix()` → `disableDepthTest()` → 恢复 viewport

### MC 1.21.1 API 要点

| API | 位置 | 说明 |
|-----|------|------|
| `RenderSystem.setProjectionMatrix` | `com.mojang.blaze3d.systems` | **签名变了**：需要两个参数 `(Matrix4f, VertexSorting)`，不是 1.20 的单个参数 |
| `VertexSorting.DISTANCE_TO_ORIGIN` | `com.mojang.blaze3d.vertex` | 按到原点距离排序，用于透视投影 |
| `VertexSorting.ORTHOGRAPHIC_Z` | 同上 | 按 Z 深度排序，GUI 正交投影用 |
| `RenderSystem.backupProjectionMatrix()` | 同上 | 同时保存 projection matrix 和 vertexSorting |
| `RenderSystem.restoreProjectionMatrix()` | 同上 | 同时恢复两者 |
| `LightTexture.FULL_BRIGHT` | `net.minecraft.client.renderer` | 值仍为 `0xF000F0 (15728880)`，未变 |
| `PoseStack.mulPose(Quaternionf)` | `com.mojang.blaze3d.vertex` | 接受 JOML `Quaternionf`，但 `Axis` 类已移除，需直接用 `new Quaternionf().rotateY(angle)` |

### 关键数学

- **FOV**: 25° — 小 FOV 减少透视畸变，类似正交但又保留立体感
- **相机距离**: `extent * 4 + 2` — 确保建筑完整在视野内
- **缩放**: `min(w, h) * 0.55 / extent` — 建筑占预览区约 55%
- **倾斜角**: 0.55 rad (~31.5°) — 俯视角度，能看到顶部和正面
- **自动旋转**: `System.currentTimeMillis() % 8000 → 0..2π` — 8 秒一圈

## 性能风险

**当前方案每帧为每个可见 cell 调用 N 次 `renderSingleBlock`**（N = 建筑方块数）。

| 场景 | 方块渲染调用/帧 | 风险 |
|------|----------------|------|
| 10 cell × 30 方块建筑 | 300 | 可接受 |
| 15 cell × 100 方块建筑 | 1500 | **可能掉帧** |

### 优化路径（按优先级）

1. **FBO 缓存**（推荐）：首次遇到建筑 → 渲染到 `MinecraftFramebuffer` → 取 `TextureAtlasSprite` → 后续帧直接 blit 纹理。内存开销极小（每个建筑类型一张小纹理）。
2. **降级渲染**：方块数 > N 的建筑只渲染边界框线框（不渲染每个方块）。
3. **异步渲染**：利用 `CompletableFuture` 异步生成 FBO 纹理，生成期间显示占位符。
4. **限制可见 cell 数**：scissor 已经做了，但可以进一步只渲染 scissor 范围内的 cell。

**MVP 阶段建议**：先上直接渲染方案，用真实建筑数据测试帧率。如 < 30fps 再加 FBO 缓存。

## 集成方式

`BuildingPreviewRenderer` 是无状态的静态工具类，放在 `shared/ui/util/`：

```java
BuildingPreviewRenderer.renderPreview(g, config, x, y, w, h);
```

调用方（如 `BuildingSelectionOverlay`）不需要知道内部渲染细节。后续其他 UI（建筑图鉴、奇观展示等）也可以直接调用。

## 已知限制

1. **无环境光照**：使用 `FULL_BRIGHT`，方块没有阴影/环境光遮蔽，视觉偏平坦
2. **无半透明排序**：半透明方块（玻璃等）的渲染顺序可能不正确
3. **renderSingleBlock 无 ModelData**：不渲染方块实体（箱子、告示牌等）的特殊模型
4. **无法旋转交互**：旋转是自动的，不支持鼠标拖拽旋转（需要额外输入处理）
5. **PoseStack.setIdentity() 可能 deprecated**：MC 1.21.1 中 `PoseStack` 经历了重构，部分方法可能在未来版本移除

## 文件清单

| 文件 | 状态 |
|------|------|
| `shared/ui/util/BuildingPreviewRenderer.java` | ✅ 已创建，编译通过 |
| `shared/ui/panel/BuildingSelectionOverlay.java` | ⏳ 待集成（替换 resolveIcon） |
| `shared/ui/util/RenderUtil.java` | 已有（可添加 FBO 缓存工具方法） |

## 后续工作

- [ ] 在建筑选择栏中集成 `BuildingPreviewRenderer` 替换单方块 icon
- [ ] 跑分测试：100 方块建筑 × 10 cell 实际帧率
- [ ] 如帧率不足 → 实现 FBO 缓存
- [ ] 支持半透明方块的正确渲染顺序
- [ ] 评估 `PoseStack` deprecated API 的替换方案
