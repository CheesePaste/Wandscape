# 本目录不可删（向下兼容载荷）

这些建筑 JSON 是**旧版本法的向下兼容载荷**，不是死数据。

- 代码仍按内层 `id` 字段注册/查找（如 `tavern`）：`WandscapeDataLoader` 用 MC `listResources` 递归扫 `data/wandscape/buildings/*.json`，`deprecated/` 子目录会被扫到并注册，故旧档/隐藏建筑仍能按 id 解析。
- `ProjectionNetwork` 隐藏 deprecated 建筑但仍按 id 解析参与查找。删掉即断开旧档加载。
- 本目录**历史上多次被误删**，故立此说明。

**禁止删除或改名本目录内文件。** 改动（新增/移除某建筑）须同步 `newplan/packages.md`。判据以 `packages.md` + 本说明为准；被误删时优先恢复 git 历史。

> 注：`WandscapeDataLoader` 只加载 `*.json`，本 `README.md` 不会被当作数据解析，放在此目录安全。
