# 语言文件（lang_src → lang）生成管线

> 信息截至 2026-09-13 | Minecraft NeoForge 1.21.1

- **【何时读】**：加/改任何上屏文案、新增界面、或想动 `lang/` 下那两个 JSON 时。
- **【不包含什么】**：具体某条文案该写什么（那是内容和文风的事，见 `guidebook-patchouli.md` 的文风约定）。

---

## 一、为什么要这层

一份文案源，编译成游戏真正加载的两个文件：

```
内容唯一来源（作者只改这里）
    lang_src/**/*.json        键 → {"zh_cn": …, "en_us": …}
                    │
                    │  gen_lang.py（本机跑，生成物提交进仓库）
                    ▼
    src/main/resources/assets/wandscape/lang/zh_cn.json
    src/main/resources/assets/wandscape/lang/en_us.json
```

**硬约束：不要手改 `lang/*.json`**——它们是产物，下次编译整体覆盖。
（`python gen_lang.py --check` 会报「产物与源不一致」，能发现手改。）

换来的两件事：

1. **中英写进同一条目**（`{"zh_cn": …, "en_us": …}`），不是两个平行目录。漏翻在结构上不可能发生，
   review 时两种语言的措辞并排可见。原先 en 缺 `gui.wandscape.panel.npc_count` 这种漏，编译期就拦住了。
2. **按域拆成几十个小文件**。改任务大厅只动 `lang_src/gui/task.json`，不再是在两千多行的
   `zh_cn.json` 里翻找；并行改动几乎不会撞车。

---

## 二、生成管线

```bash
python gen_lang.py            # lang_src → lang/*.json（校验不过就不写盘）
python gen_lang.py --check    # 只校验：键对齐、占位符、产物是否落后于源
python gen_lang.py --split    # 仅首次：把现有 lang/*.json 拆成 lang_src/（已有内容则拒绝）
```

- 只依赖 Python 标准库，不进构建流程，产物提交进仓库可审计。
- 输出按键名排序，diff 稳定。

### 源文件怎么分（`gen_lang.py` 的 `target_file`，规则集中在这一个函数里）

| 键 | 落到 |
|---|---|
| `wandscape.character_name.*` | `lang_src/text/names.json`（法师名字池） |
| `bubble.*` | `lang_src/text/bubbles.json`（气泡语料） |
| `gui.wandscape.<界面>.*` | `lang_src/gui/<界面>.json`，一个界面一个文件 |
| 其余 | `lang_src/content/<首段>.json` |

### 编译期校验什么

- **中英齐全**：值写成 `null` = 待补，直接报错挡写盘；值写成 `""` = **合法的「没有文案」**
  （如 `character_name.sep_zh` 在中文里就是空串，中式姓名不分隔），两者不能混为一谈。
- **占位符对齐**：把 `%s %s` 与 `%1$s %2$s` 归一成「第几个参数」再比，换语序的译文不误报。
- **产物漂移**：`lang/*.json` 是否还等于 `lang_src` 编译出来的结果。
- **只统计不报错**：中英完全相同的条目（专有名词、`HP`/`MP`、`zzz…` 本来就该相同）。

---

## 三、为什么不用别的拆法（决策留档）

现状：**单文件 152 KB / 2272 键**。查过 `_refs` 里的同类模组，按体积我们是中游
（Botania 376 KB、Create 279 KB、Goety 226 KB、铁魔法 109 KB），**单文件 lang 是主流**，
所以「拆」不是为了体积。真正要解决的是并行改动的冲突面和 review 成本——那由生成器解决，
不需要动运行时。

试过/想过的三条别的路，都不选：

| 方案 | 为什么不用 |
|---|---|
| 按**命名空间**拆（`assets/wandscape/` + `assets/wandscape_extra/` 各放一份 `lang/<locale>.json`） | 原版 `ClientLanguage.loadFrom` 对每个语言码遍历**所有命名空间**并合并，所以这条路零代码可行（Botania 的 `gardenofglass`、Goety 的 `goety_apotheosis` 就是各自带 lang 的命名空间）。但为拆而拆会凭空多出命名空间，且它是「按模块」的语义，不是「按界面」。 |
| 同命名空间内拆多个文件（MineColonies 的 `default.json` / `quests.json` 那样） | 原版只读 `lang/<locale>.json` **一个文件**，走这条路必须自己写加载器往 Language map 里灌（MineColonies 靠兄弟库 Structurize 的 `LanguageHandler.loadLangPath`）。多一处运行时兼容面，为不存在的体积问题不值。 |
| 上 datagen（Create / AE2 那样） | 生成内容的收益我们已经在拿（`gen_lang.py`），而 datagen 要把整个工具链接进构建，成本远大于收益。 |

---

## 四、尚未做

| 项 | 现状 | 说明 |
|---|---|---|
| **内容池仍在 lang 里** | `text/names.json` 659 键 + `text/bubbles.json` 206 键，占全部 2272 键的 38% | 它们是语料/名单而不是 UI 文案。已从「手写巨物」中拆出来独立成文件，若以后要改由数据驱动（像 `magic_spells/*.json` 的 description 那样），改动面已收敛到这两个文件 + 各自的消费点（`CharacterNames`、`AmbientTextPools`）。 |
| **只在提交前手动跑** | `--check` 不进构建 | 目前和 `gen_patchouli.py` 一样靠自觉；要强制的话可挂进 gradle 的 `check` 任务。 |
