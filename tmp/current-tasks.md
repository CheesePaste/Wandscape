# Current Tasks

## 已完成

### Phase 1: 方块崩溃修复
- **问题**：`BuildingScanner` 方块注册时没有对应的 `BlockItem`，打开背包时 NeoForge 的 creative tab 校验崩溃（`IllegalArgumentException: stack count must be 1`）
- **修复**：在 `Wandscape.java` 添加 `BUILDING_SCANNER_ITEM`（BlockItem）注册，creative tab 改用 item
- **文件**：`Wandscape.java:259-260, 271`

### Phase 2: 建筑扫描器 UI/渲染重写
- **问题**：三个投诉 — (1) boundary 只有一个点，(2) 退出 GUI 后线框消失，(3) 模式标签页不对
- **修复**：
  - `BuildingScannerScreen.java` 完全重写（~380行）：单页面全字段编辑 + Category 按钮 + 二角 boundary + 动态 zone 列表
  - `BuildingScannerRenderer.java` 从事件驱动改为 BER：所有加载的 scanner BE 持续渲染线框
  - `WandscapeClient.java` 中注册 BER
  - 材质改为结构方块样式
- **文件**：`BuildingScannerScreen.java`, `BuildingScannerRenderer.java`, `WandscapeClient.java`, `building_scanner.json`

### Phase 3a: decompile-neoforge.sh 改进（部分）
- 添加 `find_in_project_src()`：直接读 `src/main/java/` 下的项目源码
- 添加 `CACHE_DIR` + LRU 缓存：`cache_key()` (MD5) / `cache_get()` / `cache_put()`，最大 50 文件
- 添加 `suggest_similar_classes()`：`unzip -p` 失败时从 jar 中 `grep -i` 候选类名
- 项目源码预检 + 缓存层已实现
- 模糊类名匹配已实现

### Task A: decompile-neoforge.sh 全量类名不工作 ✅ (2026-07-28)
- **根因**：`set -e` 在 `var=$(func_returning_1)` 时静默退出（无 stdout/stderr）
- **修复**：
  1. `$()` 赋值添加 `|| true` — `find_in_project_src` / `pick_primary_jar` / `find_file_in_jars` / `cache_get` 等 6 处
  2. `class_in_jar` 内临时禁用 `pipefail` — 避免 `unzip | grep` SIGPIPE (141) 掩盖匹配成功
  3. `grep -E | cut | tr` 管道的赋值加 `|| true` — 避免 grep 无匹配时 pipefail 传播
  4. `find | head -1` 加 `|| true` — 避免 find SIGPIPE
  5. `echo | grep -q` 改为 bash `[[ == *pattern* ]]` — 避免 grep -q SIGPIPE
  6. error handler 中 `local` 改为普通变量 — `local` 不能在顶层作用域使用
  7. `class_to_path` 支持 `.Inner` 点标记法 — `ItemStack.Builder` → `ItemStack.java`
- **文件**：`.claude/skills/minecraft-source/scripts/decompile-neoforge.sh`

### Task C: decompile-neoforge.sh 测试 ✅ (2026-07-28)
- [x] 缓存命中：同文件两次查询，缓存文件创建在 `cache/` 目录（MD5 命名）
- [x] 模糊匹配：不存在类名（`com.example.ItemStac`）显示 10 个候选类名
- [x] 项目自身类：`com.wsteam.wandscape.Wandscape` 和短名 `Config` 均可从 `src/` 读取
- [x] 内类：`ItemStack.Builder` 和 `ItemStack$Builder` 均正确解析为 `ItemStack.java`
- [x] NeoForge 类：`net.neoforged.bus.api.Event` 正确路由到 `bus-8.0.5-sources.jar`
- [x] 图谱重新索引：22,649 节点，52,833 边（2026-07-28）

## 待办 / 有问题

### Task B: codebase-memory-mcp 改进（暂停）
两个子任务尚未开始，需要先评估 codebase-memory-mcp 源码规模：

**B1. `get_code_snippet` 增加 `full_file` 模式**
- 新增参数 `context: "snippet" | "full_file"`
- `"full_file"` 返回包含该符号的完整 `.java` 文件
- 利用图谱中已存 `file_path` 直接读文件

**B2. 图谱过时检测**
- 索引时写入 `last_indexed_at` 到图谱元数据节点
- 查询时对比 `src/` 下最大 mtime
- 如果源文件比索引新 >5 分钟，追加警告

### Task D: decompile-fabric.sh
如果 Fabric 脚本也有类似模式，可以同步改进，但优先级低。
