# 导入合成配方

> 文件放进数据包的 `data/<命名空间>/craft_recipes/`，`/reload` 之后重开合成站就生效。**文件名就是配方 id**，里面的 `id` 字段没人读，改文件名等于改 id。

合成站里的法杖与道具、魔法工坊里的卷轴，背后各是一份 `craft_recipes` 文件。这些配方不吃原版配方那套 `pattern`/`key`，它只写"要多少元素、出什么东西"，剩下的由法师在小镇上做。

## 文件放哪里

标准数据包路径 `data/<命名空间>/craft_recipes/<id>.json`，`/reload` 生效。

想覆盖模组自带的配方，文件必须放在 **`wandscape` 命名空间、用同一个文件名**：注册表的主键是剥掉命名空间后的文件名，跨命名空间重名时模组自己的那份优先，别的命名空间会被静默丢掉。

## 通用字段

| 键 | 说明 |
|---|---|
| type | `wand` / `spell` / `misc` / `potion`，缺省是 `wand` |
| output | 产物，至少要有 `item` |
| cost | 元素到整数的映射 |
| unlock_requirement | 只认 `min_colony_level`，默认 1 |
| craft_station | 只用来在 JEI 上标"在哪做" |
| display_name | **没有任何代码读它** |

## 写错了会怎样

`type` 写错一个字，这份文件会被**静默丢弃**——不报错、不警告。`cost` 里写了不存在的元素键，则整份文件解析失败并在日志里留一条警告。

## 界面上的名字

配方行显示的既不是 `display_name` 也不是文件名，而是语言键 `craft_recipe.wandscape.<配方 id>`；查不到这个键时退回**产物物品自己的名字**。所以一份没配语言键的法杖配方，在合成站里一律显示成"法杖"。

## 语言文件在哪

语言文件属于 `assets` 侧，**纯数据包提供不了**——要自定义名字，数据包得同时带上 `assets/<命名空间>/lang/zh_cn.json` 这类文件，写进那个键。

## 法杖

`output.item` 必须是 `wandscape:wand`，`wand_color` 写 `#RRGGBB`，属性写在 `attributes` 数组里，每项三个键：`type`、`operation`、`amount`。

## 属性怎么写

- `type` 取九项之一：`max_hp`、`move_speed`、`spell_power`、`work_speed`、`spell_speed`、`armor_value`、`max_mana`、`health_regen`、`mana_regen`。
- `operation` 取 `addition`（直接加绝对值）或 `multiply_base`（按基础值的百分比加）；`amount` 因此有时是 `40.0`，有时是 `0.2`，写负数也合法。

## 法杖的身份

法杖没有品阶或者职业字段，一支杖的"身份"就是这组属性加颜色。

## 容易踩的两点

属性**只对拿着这支杖的法师生效**，玩家自己拿在手里没有任何加成；而 `attributes` 里写错一个枚举值，掉的是创造模式物品栏里的预设变体，**配方本身照旧能合成**——结果是一支合得出来、却一条加成都没有的杖。

## 卷轴

`type` 写 `spell`，`output.item` 用 `wandscape:spell_scroll`，再用 `output.magic_id` 指向要绑定的魔法。默认的 `craft_station` 是 `magic_station`。

## 卷轴绑定的默认值

`output.magic_id` 不写的话，**默认取配方自己的文件名**，而不是某个魔法 id——一份叫 `scroll_heal.json` 的配方不写它，绑到的会是名为 `scroll_heal` 的魔法，而那多半不存在。装载时没有任何校验，写错的代价是一张点了没反应、提示"未知数据"的卷轴。

## 道具

`type` 写 `misc`，`output.item` 填任意物品 id，一次产出一件，数量由玩家在界面里自己调。这一类型没有 `count`、`nbt` 之类的字段可写。

另有一个 `potion` 类型，支持带 NBT 的产物与额外原料，模组自己目前一份都没用到。

## 成本会被倍率放大

写在 `cost` 里的是**单价**。合成时实付会乘上服务器配置的元素合成倍率（默认 1.0，可在设置中心调），然后向上取整；玩家在界面里调整产出数量，成本也按数量线性放大。调平衡时记着这一层，别以为 JSON 里的一千就是玩家付的一千。
