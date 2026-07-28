# shared/ — 公共 API + 事件 + UI

所有包的公共层，50+ 文件。无实现代码，只有接口/数据/事件/UI组件。

## api/ — 10 个模块接口

WandApi / ElementApi / BuildingApi / NpcApi / WarehouseApi / RoadApi / ColonyApi / TavernApi / TouristApi / StatsApi。全部已实现，实现类在对应模块的 internal/ 下。

## registry/ — 全局注册

WandscapeApis（静态服务定位器，未注册时抛异常）/ WandscapeConstants（TOML 未覆盖时的硬编码 fallback）/ WandscapeDataRegistry（泛型数据查询接口）

## event/ — 12 个 NeoForge 事件

| 事件 | 发布者 | 触发时机 |
|------|--------|---------|
| ColonyCreatedEvent | engine | 殖民地创建 |
| DailySettlementEvent | building | 每日维护结算 |
| MaintenanceForecastWarningEvent | building | 维护费预警 |
| ShopRestockedEvent | building | 商店补货 |
| TouristArrivedEvent | tourist | 游客到达 |
| TouristDepartedEvent | tourist | 游客离开 |
| WonderEffectChangedEvent | building | 奇观效果变化 |
| BuildingPlacedEvent | building | 建筑验证通过 |
| BuildingShutdownEvent | building | 建筑关停 |
| BuildingRestartedEvent | building | 建筑重启 |
| ResourceInsufficientEvent | warehouse | 资源不足(10s冷却) |
| ColonyEvaluationChangedEvent | building | 殖民地三值变化 |

## log/ — 日志工具

Log（封装 java.util.logging，debug/info/warn/error，支持 format 和 SLF4J 两种占位符）+ LogFilter（运行时标签白名单过滤）

## ui/ — UI 组件库（40+ 文件，8 子包）

中世纪魔法主题，CC0 精灵图 + 程序化渲染混合。

| 子包 | 内容 |
|------|------|
| component/ | MedievalScreen / MedievalButton / TabBar / ScrollableList / ElementPanel / ProgressIndicator / SearchBar / TaskQueuePanel / Slider 等 |
| panel/ | WandscapePanelState + PanelController + PanelOverlay(顶部全信息HUD栏+左侧模式侧边栏+停摆警告浮层) + BuildingSelectionOverlay |
| task/ | TaskEditorClientState + TaskEditorScreen |
| editor/ | UIEditorScreen / UILayoutManager / WidgetLayout |
| util/ | BuildingPreviewRenderer（独立 3D 等轴测缩略图） |
| animation/ | MedievalAnimation |
| skin/ | SkinSprite(精灵图坐标) / SkinRender(9-slice/按钮/p标签/滚动条) |
| theme/ | WandscapeTheme（RTS风格绘制基元 + 7元素图标映射 + 通用UI图标）+ MedievalColors（羊皮纸/金色/紫色系/功能色） |
