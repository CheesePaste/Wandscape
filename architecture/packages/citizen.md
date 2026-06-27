# citizen/ — 已移除

`citizen/` 包已完全移除。原有内容迁移如下：

| 旧位置 | 新位置 |
|--------|--------|
| `citizen/CitizenState.java` | `tourist/internal/TouristState.java` |
| `citizen/CitizenManager.java` | 已删除（由 `tourist/internal/TouristSpawnSystem` 替代） |
| `command/CitizenCommand.java` | `command/TouristCommand.java` |

详见 `architecture/packages/tourist.md`。
