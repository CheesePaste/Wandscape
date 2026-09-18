# 导入建筑包

> 建筑包就是一个目录。把 `data/<命名空间>/buildings/<包名>/` 建出来，往里面丢建筑 JSON，它们就归这个包；目录里再放一份 `package.json`，包才有名字和图标。

建筑一多，平铺的一张列表就没法看了。包是给建筑分组、成套分发、并且让不同作者的同名建筑互不打架的单位——同一个包里的建筑，`id` 只要在包内不重名就行。

## 目录即包

- `buildings/cottage.json` — 直接放在 `buildings/` 下的建筑归 default 包
- `buildings/medieval/inn.json` — 目录名 `medieval` 就是包名
- `buildings/medieval/package.json` — 这个包的元数据
- `buildings/medieval/tower/watchtower.json` — 再往深写只是文件整理，照样归 medieval 包
- `buildings/oriental/tea_house.json` — 另一个包

只有 `buildings/` 下的**第一层**子目录名算包名，再往深写只是文件整理，不影响归属。

## 单独指定归属

建筑 JSON 自己也能写一个 `package_id` 字段，**它的优先级高于目录**。不过写成 `default` 等于没写，会退回按目录推导——想让某个文件脱离目录单独归包时才用它。

## 包的元数据

`package.json` 放在包目录下，键都可以省：

| 键 | 缺省 |
|---|---|
| id | 当前目录名 |
| name | 包 id |
| description | 空 |
| author | 空 |
| version | `1.0.0` |
| icon | `minecraft:stone_bricks` |
| priority | 100 |

## 名称与排序

`name` 既可以写一句直接的文字，也可以写语言键——两者走的是同一套"查得到就用查到的、查不到就用原文"的逻辑，所以写中文名是合法的，不必非得起一个 `wandscape.pack.*` 的键。

`icon` 接受物品 id。`priority` 越小排得越靠前，模组自带的核心包是 0。

## 默认包

模组内置的建筑全在根目录，归一个叫 `default` 的包，显示名是"核心建筑包"。

**任何命名空间下的 `buildings/package.json` 都会被当成这个默认包的元数据**——要写自己包的 `package.json`，务必放进包目录里，在根目录放一份是改默认包的名字。

## 目录名与 id 不一致

没有 `package.json` 的目录不会报错，会自动补一个占位包，名字就是目录名。写 `package.json` 时把 `id` 写成和目录名不一样的值，会出现"包列表里叫一个名字、建筑挂在另一个名字下"的错位，两边都对不上。

## 玩家能不能关掉

能。设置中心有「建筑包库」一页，可以把整个包停用。停用只影响建造栏里的显示，世界里已经建好的建筑照常运转、照常结算——它是给玩家清理列表用的，不是给包作者关功能用的。

## 扫描器怎么配合

创造建筑扫描器的界面上有「目标建筑包」一栏，填一个还没用过的名字，导出时会自动建出那个目录并写一份 `package.json` 骨架，作者字段记成你。包名会被转成小写，所以 `Medieval` 与 `medieval` 是同一个包。

扫描器导出走的是存档自己的数据包，落点在 `data/wandscape/buildings/<包名>/`——包和命名空间是两件事，导出的文件永远在 `wandscape` 命名空间里。
