# shared/ — 公共 API + 事件 + UI

所有包的公共层，50+ 文件。无实现代码，只有接口/数据/事件/UI组件。新增 ColonyMetricsApi（实时全量指标聚合）和 ColonyMetricsSnapshot（统一指标数据 record）。

## api/ — 11 个模块接口

BuildingApi / ColonyApi / ColonyMetricsApi / ElementApi / HouseApi / NpcApi / RoadApi / TavernApi / TouristApi / WandApi / WarehouseApi。全部已实现，实现类在对应模块的 internal/ 下。ColonyMetricsApi 由 engine/service/ColonyMetricsService 实现。

## entity/ — 实体行为契约

`VillagerLike` 纯标记接口：实现者获得「原版敌对生物像对村民一样索敌」的待遇（NPC/游客实现），由 engine/HostileTargetingHandler 消费。

## registry/ — 全局注册

WandscapeApis（静态服务定位器，未注册时抛异常）/ WandscapeConstants（TOML 未覆盖时的硬编码 fallback）/ WandscapeDataRegistry（泛型数据查询接口）

## event/ — 12 个事件

| 事件 | 发布者 | 触发时机 |
|------|--------|---------|
| ColonyCreatedEvent | engine | 殖民地创建 |
| ColonyLevelUpEvent | engine | 殖民地升级 |
| DailySettlementEvent | building | 每日结算 |
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

## ui/ — UI 组件库

MINIMAL 风格：渐变玻璃面板 + 发光边框 + MedievalColors 调色板。组件用精灵图（按钮等）或代码绘制混合渲染。

| 子包 | 内容 |
|------|------|
| component/ | MedievalScreen(基类) / MedievalButton / TabBar / ScrollableList / ElementPanel / ProgressIndicator / TaskQueuePanel / Slider 等 |
| panel/ | WandscapePanelState + PanelController + PanelOverlay(顶部HUD栏+左侧侧边栏+STATS面板+警告浮层) + BuildingSelectionOverlay + AnomalyScreen |
| guidance/ | GuideSession + GuideStep + GuideRegistry + GuideRenderer (左下角四态新手引导弹窗：默认/选卡/射线瞄准/锁定微调，支持点击 ▼/▲ 折叠/展开) |
| util/ | BuildingPreviewRenderer + WandscapeHighlightRenderer + RenderUtil |
| (根) | ReplayScreenGuard(回放兼容：ReforgedPlay/ReplayMod 播放中取消所有 MedievalScreen 打开) |
| animation/ | MedievalAnimation |
| markdown/ | AST节点(Header/Text/Image/Quote/List) + MarkdownParser(纯Java解析) + MarkdownRenderWidget(MC渲染控件) + navigation(DocumentHistoryStack历史栈+DocumentLoader文档读取) + texture(MarkdownTextureManager全能图像管理) |
| skin/ | SkinSprite(精灵图坐标) / SkinRender(9-slice/按钮/关闭按钮/进度条/箭头图标) |
| theme/ | MedievalColors(金色/紫色/文本/功能色) + WandscapeTheme(V面板覆盖层用RTS绘制基元 + 元素图标) |
