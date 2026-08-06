# 数据格式 — 叙事 JSON

位置：`src/main/resources/data/wandscape/narratives/`

解析：`tourist/internal/NarrativeTemplates`（两级解析：建筑专属 → 全局类别 → Java 硬编码兜底）。

## buildings/<type>.json（建筑专属模板）

```json
{
  "building_id": "tavern",
  "templates": {
    "visit": [
      "……{name}……麦芽酒……{building}……",
      "……橡木门……{emotion_adj}……"
    ]
  }
}
```

现有：`buildings/tavern.json`、`buildings/service_hall.json`（均仅 visit 模板）。

## zh_cn.json（全局类别模板）

```json
{
  "locale": "zh_cn",
  "category_templates": {
    "shop":    {"visit": ["…"], "checkin": ["…"], "wakeup": ["…"]},
    "service": {"visit": ["…"]},
    "hotel":   {"visit": ["…"], "checkin": ["…"], "wakeup": ["…"]}
  },
  "generic": {
    "arrival_morning": ["…"], "arrival_afternoon": ["…"], "arrival_night": ["…"],
    "departure_delighted": ["…"], "departure_pleased": ["…"],
    "departure_neutral": ["…"], "departure_unsatisfied": ["…"],
    "satisfaction_milestone_50": ["…"], "satisfaction_milestone_70": ["…"],
    "satisfaction_milestone_100": ["…"]
  },
  "emotion_adjectives": {
    "DELIGHTED": ["…"], "PLEASED": ["…"], "SATISFIED": ["…"],
    "NEUTRAL": ["…"], "DISAPPOINTED": ["…"], "UPSET": ["…"]
  }
}
```

## 占位符

`{name}` 游客名、`{building}` 建筑名、`{item}` 商品、`{emotion_adj}` 情感形容词、`{visit_count}` 访问次数。

## 事件生成（NarrativeGenerator）

visit（购物/服务）、arrival、departure、hotel checkin/wakeup、satisfaction milestone。事件经 `NarrativeEventTriggered` 发到 world eventBus（`StatsService` 订阅，目前 TODO）。
