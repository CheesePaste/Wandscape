#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""gen_patchouli.py — 把 md 指南编译成帕秋莉手册（Patchouli）数据。

单一内容源
    src/main/resources/assets/wandscape/guidebook/<md_lang>/*.md
同一份 md 两处渲染：装 Patchouli 的玩家读到编译出的手册，没装的玩家
仍由游戏内 GuidebookScreen + MarkdownParser 直接读 md。改完 md 重跑本脚本即可。

生成物（提交进仓库）
    data/wandscape/patchouli_books/guide/book.json
    assets/wandscape/patchouli_books/guide/<patchouli_lang>/{categories,entries}/**.json
    assets/wandscape/guidebook/runtime/<md_lang>.json     （兜底阅读器读的运行期清单）
    assets/wandscape/textures/gui/guidebook/book.png      （textures 子命令：书皮 + 按钮图集）

运行期清单是给「没装 Patchouli」的兜底阅读器（GuidebookScreen）用的：分类、条目、条目名、
《…》标题表与着陆文案都按语言各发一份，两套渲染因此看到同一本手册，页面跳转也一致。
它是生成物，**不要手改**；结构只在下面的 CATEGORIES / ENTRIES / TITLE_TO_DOC 三张表里。

md 语言目录 → Patchouli 语言目录：en → en_us，zh_cn → zh_cn。
Patchouli 以 en_us 目录为枚举索引，因此两套目录都必须完整生成。

用法
    python gen_patchouli.py                # 编译手册 JSON + 运行期清单
    python gen_patchouli.py --check        # 只校验：已提交的生成物是否与当前 md/结构表一致
    python gen_patchouli.py textures       # 生成书皮图集（已存在则不覆盖）
    python gen_patchouli.py textures --force
"""

import json
import re
import struct
import sys
import zlib
from pathlib import Path

# --check 要在内存里补做分页（生成物是 gen + paginate 两步的结果），两份脚本都在仓库根、都只用 stdlib
sys.path.insert(0, str(Path(__file__).resolve().parent))
import paginate_patchouli_json as paginate_module  # noqa: E402

ROOT = Path(__file__).resolve().parent
SRC_MD = ROOT / "src/main/resources/assets/wandscape/guidebook"
OUT_DATA = ROOT / "src/main/resources/data/wandscape/patchouli_books/guide"
OUT_ASSETS = ROOT / "src/main/resources/assets/wandscape/patchouli_books/guide"
OUT_RUNTIME = SRC_MD / "runtime"
BOOK_TEXTURE = ROOT / "src/main/resources/assets/wandscape/textures/gui/guidebook/book.png"
# 着陆文案的唯一来源：book.json 的 landing_text 语言键（中英各一句，内嵌帕秋莉链接命令）
LANDING_LANG_FILE = ROOT / "lang_src/content/wandscape.json"
LANDING_LANG_KEY = "wandscape.guide_book.landing"

NS = "wandscape"
BOOK_ID = "wandscape:guide"

# md 语言目录 → Patchouli 语言目录
LANGS = [("zh_cn", "zh_cn"), ("en", "en_us")]
# md 语言目录 → lang_src 里的语言键后缀（两套命名不同，别混用）
LANG_KEY_SUFFIX = {"zh_cn": "zh_cn", "en": "en_us"}
# 清单格式版本：Java 侧不认的版本一律走降级路径，不做「缺字段补默认」
MANIFEST_VERSION = 1

# ---------------------------------------------------------------- 结构清单
# 分类：(id, 中文名, 英文名, 图标, sortnum, zh 描述, en 描述)
#
# 手册沿「玩法主线 → 各大功能分类 → 关于我们」一条线铺开：
#   「玩法主线」只放路线与阅读顺序，外加元素与城镇等级、游客与三值这两页无独立分类的总括内容；
#   其余每个大功能各占一个分类，分类页第一条就是那篇功能总览。
CATEGORIES = [
    ("playstyle", "玩法主线", "Gameplay Tracks", "minecraft:compass", 0,
     "这一部分展示了模组的大致官方游玩路线。对于具体内容，见各功能分类。",
     "This section shows the mod's general intended route. For the details of a feature, see its own category."),
    ("buildings", "建筑", "Buildings", "minecraft:stone_bricks", 10,
     "这一页展示了模组的所有建筑类型和对应功能。对于如何建造，见《0，入门》《管理》《建造子模式》。",
     "Every building type and what each one does. For how to build, see 《0. Getting Started》, 《Management》 and 《Build Mode》."),
    ("management", "管理", "Management", "minecraft:lever", 20,
     "这一页展示了模组的管理系统，操作方式。对于具体建筑，见《建筑》。",
     "The mod's management system and how to operate it. For individual buildings, see 《Buildings》."),
    ("magic", "魔法", "Spells", "wandscape:spell_scroll", 30,
     "这一页展示了法师，施法与具体魔法。对于法杖与魔法道具，见《装备与物品》。",
     "Mages, casting, and each spell. For wands and magic items, see 《Equipment and Items》."),
    ("items", "装备与物品", "Equipment and Items", "wandscape:wand", 40,
     "这一页展示了模组的装备（法杖）与一些物品。对于法术卷轴等法术，见《魔法》，对于如何制作它们，见《合成站》。",
     "The mod's equipment (wands) and its smaller items. For spells such as scrolls, see 《Spells》; for how to craft them, see 《Crafting Station》."),
    ("custom", "自定义与数据包", "Customization & Datapacks", "minecraft:structure_block", 50,
     "这一页展示了模组建筑、物品、魔法、合成配方与战利品等内容的模组方块和数据包的自定义方式，以及查状态与急救用的模组指令。对于相关的模组本体内容，见《建筑》《魔法》《装备与物品》。",
     "How to customize the mod's own blocks and data packs — buildings, items, spells, crafting recipes and loot — plus the mod's own commands for reading state and rescuing a stuck colony. For the mod's own content, see 《Buildings》, 《Spells》 and 《Equipment and Items》."),
    ("compat", "联动与兼容", "Integration & Compatibility", "minecraft:crafting_table", 60,
     "这一页展示了模组兼容和联动的其他模组。如果出现了兼容的BUG，见《关于我们》。",
     "What this mod does together with other mods. If you run into a compatibility bug, see 《About Us》."),
    ("about", "关于我们", "About Us", "minecraft:name_tag", 70,
     "制作者名单、官方平台与反馈渠道。",
     "Credits, official platforms, and where to send feedback."),
]

# 条目：(md 文件名去掉 .md, 所属分类, 图标, sortnum)。条目名取 md 的 H1。
# 同一篇 md 可以登记多次、挂到不同分类——会生成内容相同的多份条目（内容同源，不存在两份要维护）。
ENTRIES = [
    # ── 玩法主线：路线与阅读顺序，外加两页没有独立分类的总括内容 ──
    ("index_guide", "playstyle", "wandscape:guide_book", 0),
    ("intro_0_guide", "playstyle", "minecraft:writable_book", 1),
    ("intro_0_5_guide", "playstyle", "minecraft:knowledge_book", 2),
    ("element_level_guide", "playstyle", "wandscape:element_earth", 3),
    ("track_tourist_guide", "playstyle", "wandscape:tourist_spawn_egg", 4),
    ("tourists_guide", "playstyle", "minecraft:emerald", 5),
    ("track_adventure_guide", "playstyle", "minecraft:iron_sword", 6),
    ("track_tech_guide", "playstyle", "minecraft:redstone", 7),
    ("track_diplomacy_guide", "playstyle", "minecraft:white_banner", 8),

    # ── 建筑：总览 → 建筑维护 → 每一类建筑各一条 → 建筑扫描器 ──
    # 类别条目与 buildings/*.json 的 category 一一对应（government→市政厅、storage→仓库、
    # workstation、crafting_station、magic_station、tavern、altar、mage_hut、node、
    # decoration、shop、service、relax、atm），排序照建造面板的分类顺序。
    # 首尾两条不是建筑类别：建筑维护讲受损建筑怎么修，建筑扫描器是把自建房导入成建筑。
    ("buildings_guide", "buildings", "minecraft:bricks", 0),
    ("anomaly_guide", "buildings", "minecraft:anvil", 1),
    ("townhall_guide", "buildings", "minecraft:bell", 2),
    ("warehouse_guide", "buildings", "minecraft:chest", 3),
    ("workstation_guide", "buildings", "minecraft:crafting_table", 4),
    ("crafting_guide", "buildings", "wandscape:wand", 5),
    ("magic_station_guide", "buildings", "wandscape:spell_scroll", 6),
    ("tavern_guide", "buildings", "minecraft:brewing_stand", 7),
    ("altar_guide", "buildings", "minecraft:enchanting_table", 8),
    ("mage_hut_guide", "buildings", "minecraft:red_bed", 9),
    ("node_guide", "buildings", "wandscape:element_earth", 10),
    ("decoration_guide", "buildings", "minecraft:lantern", 11),
    ("shop_guide", "buildings", "minecraft:emerald", 12),
    ("service_guide", "buildings", "minecraft:light_blue_bed", 13),
    ("relax_guide", "buildings", "minecraft:oak_stairs", 14),
    ("atm_guide", "buildings", "minecraft:gold_ingot", 15),
    ("building_scanner_guide", "buildings", "wandscape:building_scanner", 16),

    # ── 管理：面板本身 + 四个子模式 ──
    ("panel_guide", "management", "minecraft:compass", 0),
    ("panel_build_guide", "management", "minecraft:scaffolding", 1),
    ("panel_road_guide", "management", "minecraft:dirt_path", 2),
    ("panel_tasks_guide", "management", "minecraft:paper", 3),
    ("panel_settings_guide", "management", "minecraft:redstone_torch", 4),

    # ── 魔法：法师 + 施法（上手讲怎么放）+ 高级施法管理（策略/锁/门控）+ 每个魔法一条 ──
    # 魔法图标用原版物品（模组无 per-magic 图标，10 条共用 spell_scroll 会让分类页不可读）
    ("mages_guide", "magic", "wandscape:wandscape_npc_spawn_egg", 0),
    ("casting_guide", "magic", "minecraft:blaze_rod", 1),
    ("advanced_casting_guide", "magic", "minecraft:lectern", 2),
    ("magic_beam_guide", "magic", "minecraft:spectral_arrow", 3),
    ("magic_meteor_guide", "magic", "minecraft:fire_charge", 4),
    ("magic_desperation_guide", "magic", "minecraft:diamond_sword", 5),
    ("magic_enfeeble_field_guide", "magic", "minecraft:fermented_spider_eye", 6),
    ("magic_conversion_guide", "magic", "minecraft:lead", 7),
    ("magic_petrification_guide", "magic", "minecraft:stone", 8),
    ("magic_fortification_guide", "magic", "minecraft:shield", 9),
    ("magic_heal_guide", "magic", "minecraft:golden_apple", 10),
    ("magic_teleport_guide", "magic", "minecraft:ender_pearl", 11),
    ("magic_revive_guide", "magic", "minecraft:totem_of_undying", 12),

    # ── 装备与物品：各类条目（小道具按类归并，法杖整族一条）——
    # 没有别的分类那样的总览页：这一类的东西就那么几件，底下每条自己就说清了，再写一篇总览是重复。
    ("wand_guide", "items", "wandscape:wand", 0),
    ("oath_ring_guide", "items", "wandscape:oath_ring", 1),
    ("scepter_guide", "items", "wandscape:omni_scepter", 2),
    ("magic_compass_guide", "items", "wandscape:magic_compass", 3),
    ("warehouse_terminal_guide", "items", "wandscape:warehouse_terminal", 4),

    # ── 自定义：总览 → 建筑扫描器（自定义建筑唯一的产出途径）→ 各条数据导入 → 指令 ──
    # 指令不属于「导入」，但它是同一批读者（开服/改数据的人）的另一把工具：`/wandscape` 下查状态与急救那批。
    ("custom_guide", "custom", "minecraft:structure_block", 0),
    ("building_scanner_guide", "custom", "wandscape:building_scanner", 1),
    ("custom_buildings_guide", "custom", "minecraft:scaffolding", 2),
    ("custom_packs_guide", "custom", "minecraft:bundle", 3),
    ("custom_elements_guide", "custom", "wandscape:element_earth", 4),
    ("custom_recipes_guide", "custom", "minecraft:crafting_table", 5),
    ("custom_magic_guide", "custom", "wandscape:spell_scroll", 6),
    ("custom_loot_guide", "custom", "minecraft:chest", 7),
    ("custom_commands_guide", "custom", "minecraft:command_block", 8),

    # ── 联动与兼容：每个第三方模组一条，只讲玩家看得见的效果 ──
    ("curios_guide", "compat", "minecraft:gold_ingot", 0),
    ("irons_spells_guide", "compat", "minecraft:enchanted_book", 1),
    ("goety_guide", "compat", "minecraft:soul_lantern", 2),

    # ── 关于我们：平台、反馈与制作者，一条打完 ──
    ("about_guide", "about", "minecraft:name_tag", 0),
]

# 《标题》→ 链接目标（条目 id 或分类 id，link_command 两者都认）。条目名与标题同文时才会命中。
#
# 按语言分开存：md 编译（convert_inline）用合并视图，运行期清单按语言各发一份——
# 兜底阅读器只拿自己那一种语言的表去把正文里的《…》变成链接，不靠猜。
TITLE_TO_DOC_ZH = {
    # zh_cn
    # 「建筑 / 管理 / 魔法 / 装备与物品 / 自定义与数据包 / 联动与兼容」既是分类名、也是功能名——一律指向**分类**：
    # 分类页里第一条就是那篇总览，往下才是各条细节，比直接跳条目更顺手。
    # 玩法主线
    "0，入门": "intro_0_guide",
    "0.5，推荐了解的功能": "intro_0_5_guide",
    "1，游客线": "track_tourist_guide",
    "2，冒险线": "track_adventure_guide",
    "3，科技线": "track_tech_guide",
    "4，外交线": "track_diplomacy_guide",
    "概览": "index_guide",
    # 分类入口
    "建筑": "buildings",
    "管理": "management",
    "魔法": "magic",
    "装备与物品": "items",
    "自定义与数据包": "custom",
    "联动与兼容": "compat",
    "关于我们": "about_guide",
    # 玩法主线里的总括页
    "元素与城镇等级": "element_level_guide",
    "游客与三值": "tourists_guide",
    # 各分类下的具体页
    "管理面板": "panel_guide",
    "建造子模式": "panel_build_guide",
    "道路子模式": "panel_road_guide",
    "任务大厅": "panel_tasks_guide",
    "设置中心": "panel_settings_guide",
    "法师": "mages_guide",
    "施法": "casting_guide",
    "高级施法管理": "advanced_casting_guide",
    "合成站": "crafting_guide",
    "魔法工坊": "magic_station_guide",
    "工作站": "workstation_guide",
    "商店": "shop_guide",
    "服务设施与酒店": "service_guide",
    "酒馆": "tavern_guide",
    "祭坛": "altar_guide",
    "法师小屋": "mage_hut_guide",
    "ATM": "atm_guide",
    # 装备与物品
    "法杖": "wand_guide",
    "盟誓戒指": "oath_ring_guide",
    "权杖": "scepter_guide",
    "元素节点": "node_guide",
}

TITLE_TO_DOC_EN = {
    # en_us
    "0. Getting Started": "intro_0_guide",
    "0.5 Recommended Features": "intro_0_5_guide",
    "1. Tourist Track": "track_tourist_guide",
    "2. Adventure Track": "track_adventure_guide",
    "3. Tech Track": "track_tech_guide",
    "4. Diplomacy Track": "track_diplomacy_guide",
    "Overview": "index_guide",
    "Buildings": "buildings",
    "Management": "management",
    "Spells": "magic",
    "Equipment and Items": "items",
    "Customization & Datapacks": "custom",
    "Integration & Compatibility": "compat",
    "About Us": "about_guide",
    "Elements and Town Level": "element_level_guide",
    "Tourists and Values": "tourists_guide",
    "Management Panel": "panel_guide",
    "Build Mode": "panel_build_guide",
    "Road Mode": "panel_road_guide",
    "Task Hall": "panel_tasks_guide",
    "Settings": "panel_settings_guide",
    "Mages": "mages_guide",
    "Casting": "casting_guide",
    "Advanced Casting": "advanced_casting_guide",
    "Magic Workshop": "magic_station_guide",
    "Workstation": "workstation_guide",
    "Crafting Station": "crafting_guide",
    "Shop": "shop_guide",
    "Service Facilities and Hotels": "service_guide",
    "Tavern": "tavern_guide",
    "Altar": "altar_guide",
    "Mage Hut": "mage_hut_guide",
    "ATM": "atm_guide",
    # Equipment and Items
    "Wand": "wand_guide",
    "Oath Ring": "oath_ring_guide",
    "Scepters": "scepter_guide",
    "Element Node": "node_guide",
}

# md 正文里两种语言的《…》同时存在（一篇 md 只用一种语言），编译期查合并表即可。
# 两侧同名标题必须指向同一目标——`check_manifest()` 会断言这一点。
TITLE_TO_DOC = {**TITLE_TO_DOC_ZH, **TITLE_TO_DOC_EN}
TITLE_TO_DOC_BY_LANG = {"zh_cn": TITLE_TO_DOC_ZH, "en": TITLE_TO_DOC_EN}

# 内联代码的着色（帕秋莉没有等宽字体，用颜色区分）
CODE_COLOR = "$(#8a5a2b)"
# 引用的着色
QUOTE_COLOR = "$(#6b5a45)"

IMG_RE = re.compile(r"^!\[(.*?)\]\((.*?)(?:\s+=(\d+)x(\d+))?\)$")
LI_RE = re.compile(r"^([-*+]|\d+[.)])\s+(.*)$")
H3_RE = re.compile(r"^#{3,}\s+(.*)$")
RULE_RE = re.compile(r"^(-{3,}|\*{3,}|_{3,})$")
TDASH_RE = re.compile(r"^:?-{2,}:?$")

# 合法的 $(l:…) 目标 = 本手册的分类 id ∪ 条目 id；由 build_books 填好后再编译。
_VALID_TARGETS = set()


class Warn:
    """收集告警，跑完统一打印。转换期的语义降级都从这里出声，不静默丢内容。"""

    def __init__(self):
        self.items = []

    def __call__(self, msg):
        if msg not in self.items:
            self.items.append(msg)


# ---------------------------------------------------------------- 文本转换
def to_plain(text):
    """去掉 md 强调标记，得到纯文本（用于条目名与页面标题——帕秋莉不解析标题里的 $()）。"""
    text = re.sub(r"!\[(.*?)\]\(.*?\)", r"\1", text)
    text = re.sub(r"\[(.*?)\]\(.*?\)", r"\1", text)
    text = text.replace("**", "").replace("~~", "").replace("`", "")
    return re.sub(r"\*(.+?)\*", r"\1", text).strip()


def split_row(line):
    s = line.strip()
    if s.startswith("|"):
        s = s[1:]
    if s.endswith("|"):
        s = s[:-1]
    return [c.strip() for c in s.split("|")]


def doc_to_category():
    """doc id → 分类 id。同一篇 md 可以登记多次（如 building_scanner_guide），**先出现者胜出**——
    帕秋莉条目 id 按这个规则定，运行期清单与 md 链接目标都跟着它，三处不许各算各的。"""
    out = {}
    for doc, cid, _icon, _sortnum in ENTRIES:
        out.setdefault(doc, cid)
    return out


def link_command(target, warn):
    """md 链接目标 → 帕秋莉链接命令；无法映射时返回 None（调用方降级为纯文本）。"""
    t = target.strip()
    if t.startswith(("http://", "https://")):
        if ")" in t:
            warn("外部链接含 ')'，会截断帕秋莉命令，链接将失效：%s" % t)
        return "$(l:%s)" % t
    for prefix in ("guidebook:", "guide:"):
        if t.startswith(prefix):
            t = t[len(prefix):].strip()
            break
    anchor = ""
    if "#" in t:
        t, _, anchor = t.partition("#")
    if t.endswith(".md"):
        t = t[:-3]

    doc_to_cat = doc_to_category()
    cat_ids = {c[0] for c in CATEGORIES}

    if t in doc_to_cat:
        target_path = "%s/%s" % (doc_to_cat[t], t)
    elif t in cat_ids:
        target_path = t
    elif "/" in t:
        target_path = t
    else:
        # 既不是 .md 相对链接、也不是本手册的条目/分类 id（如 #锚点、action:…）
        return None

    if anchor:
        target_path += "#" + anchor

    return "$(l:%s:%s)" % (NS, target_path)


def parse_link(text, i):
    """text[i] 是 '['，尝试解析 [label](target)，返回 (label, target, 结束下标)。"""
    if text[i] != "[":
        return None
    depth, j = 0, i
    while j < len(text):
        if text[j] == "[":
            depth += 1
        elif text[j] == "]":
            depth -= 1
            if depth == 0:
                break
        j += 1
    else:
        return None
    if j + 1 >= len(text) or text[j + 1] != "(":
        return None
    depth, k = 0, j + 1
    while k < len(text):
        if text[k] == "(":
            depth += 1
        elif text[k] == ")":
            depth -= 1
            if depth == 0:
                break
        k += 1
    else:
        return None
    return text[i + 1:j], text[j + 2:k], k + 1


def convert_inline(text, warn, resume=""):
    """md 行内标记 → 帕秋莉 $() 命令。

    帕秋莉的 $() 是「清空整个样式栈」而非「弹出当前样式」，所以嵌套强调
    （如引用块里的 **粗体**）一旦内层闭合就会把外层样式一起抹掉。
    这里用 resume（外层样式的重开命令串）在内层 $() 之后补回来。
    """
    out = []
    i, n = 0, len(text)
    while i < n:
        if text.startswith("**", i):
            j = text.find("**", i + 2)
            if j != -1:
                out.append("$(bold)"
                           + convert_inline(text[i + 2:j], warn, resume + "$(bold)")
                           + "$()" + resume)
                i = j + 2
                continue
        if text.startswith("~~", i):
            j = text.find("~~", i + 2)
            if j != -1:
                out.append("$(strike)"
                           + convert_inline(text[i + 2:j], warn, resume + "$(strike)")
                           + "$()" + resume)
                i = j + 2
                continue
        c = text[i]
        if c == "*":
            j = text.find("*", i + 1)
            if j != -1 and j > i + 1:
                out.append("$(italic)"
                           + convert_inline(text[i + 1:j], warn, resume + "$(italic)")
                           + "$()" + resume)
                i = j + 1
                continue
        if c == "`":
            j = text.find("`", i + 1)
            if j != -1:
                out.append(CODE_COLOR
                           + convert_inline(text[i + 1:j], warn, resume + CODE_COLOR)
                           + "$()" + resume)
                i = j + 1
                continue
        if c == "[":
            parsed = parse_link(text, i)
            if parsed is not None:
                label, target, end = parsed
                cmd = link_command(target, warn)
                if cmd is None:
                    warn("链接无帕秋莉等价形式，已降级为纯文本：%s" % target)
                    out.append(convert_inline(label, warn, resume))
                else:
                    out.append(cmd
                               + convert_inline(label, warn, resume + cmd)
                               + "$(/l)" + resume)
                i = end
                continue
        if c == "《":
            j = text.find("》", i + 1)
            if j != -1:
                title = text[i + 1:j].strip()
                target_doc = TITLE_TO_DOC.get(title)
                if target_doc:
                    cmd = link_command(target_doc, warn)
                    if cmd:
                        out.append(cmd + "《" + title + "》" + "$(/l)" + resume)
                        i = j + 1
                        continue
        if c == "$":
            warn("正文出现字面 '$'，可能与帕秋莉命令语法冲突：%s" % text)
        out.append(c)
        i += 1
    return "".join(out)


# ---------------------------------------------------------------- 自检
# 帕秋莉 BookTextParser 认得的命令（见 _refs/patchouli .../book/text/BookTextParser.java）
SIMPLE_CMDS = {
    "", "reset", "clear", "nocolor", "br", "br2", "2br", "p", "/l", "/t", "/c",
    "playername", "k", "obf", "l", "bold", "m", "strike", "n", "underline",
    "o", "italic", "italics",
}
PUSH_PREFIX = ("l:", "t:", "tooltip:", "c:", "command:")
POP_CMDS = ("/l", "/t", "/c")
HEX_RE = re.compile(r"^#[0-9a-fA-F]{3}$|^#[0-9a-fA-F]{6}$")
CODE_RE = re.compile(r"^[0-9a-f]$")
LI_RE_CMD = re.compile(r"^li\d*$")


def verify_page_text(text):
    """按帕秋莉的解析规则静态自检一段页面文本，返回问题列表。

    未知命令会被原样显示成 "$(xxx)"；样式栈下溢会让帕秋莉抛异常并渲染 [ERROR]。
    这两类问题在生成期就该挡住，而不是等进游戏才发现。
    """
    issues = []
    stack = 1  # 基线帧
    for cmd in re.findall(r"\$\(([^)]*)\)", text):
        if cmd in POP_CMDS:
            stack -= 1
            if stack < 1:
                issues.append("样式栈下溢：$(%s)" % cmd)
                stack = 1
        elif cmd in ("", "reset", "clear"):
            stack = 1
        elif LI_RE_CMD.match(cmd) or HEX_RE.match(cmd) or CODE_RE.match(cmd) or cmd in SIMPLE_CMDS:
            pass
        elif cmd.startswith(PUSH_PREFIX):
            stack += 1
        else:
            issues.append("未知命令：$(%s)" % cmd)
    if stack != 1:
        issues.append("样式栈未回到基线（残留 %d 层）：%s" % (stack - 1, text[:120]))
    return issues


# ---------------------------------------------------------------- 块解析
def parse_blocks(lines):
    """把一节的原始行切成块：(kind, ...)。"""
    i, n = 0, len(lines)
    while i < n:
        s = lines[i].strip()
        if not s:
            i += 1
            continue
        if RULE_RE.match(s):
            i += 1
            continue
        m = IMG_RE.match(s)
        if m:
            yield ("image", m.group(1), m.group(2).strip(), m.group(3), m.group(4))
            i += 1
            continue
        if s.startswith("|"):
            rows = []
            while i < n and lines[i].strip().startswith("|"):
                rows.append(split_row(lines[i]))
                i += 1
            yield ("table", rows)
            continue
        if s.startswith(">"):
            buf = []
            while i < n and lines[i].strip().startswith(">"):
                buf.append(lines[i].strip()[1:].strip())
                i += 1
            yield ("quote", " ".join(x for x in buf if x))
            continue
        if LI_RE.match(s):
            items = []
            while i < n:
                m2 = LI_RE.match(lines[i].strip())
                if not m2:
                    break
                items.append(m2.group(2))
                i += 1
            yield ("list", items)
            continue
        m3 = H3_RE.match(s)
        if m3:
            yield ("h3", m3.group(1))
            i += 1
            continue
        yield ("para", s)
        i += 1


def render_table(rows, warn):
    if not rows:
        return ""
    header = rows[0]
    body = [r for r in rows[1:] if not all(TDASH_RE.match(c or "-") for c in r)]
    out = []
    if any(c.strip() for c in header):
        out.append("$(bold)" + "  ".join(convert_inline(c, warn) for c in header if c.strip()) + "$()")
    for row in body:
        cells = [c for c in row if c.strip()]
        if cells:
            out.append("$(li)" + " — ".join(convert_inline(c, warn) for c in cells))
    return "".join(out)


def render_block(block, warn):
    kind = block[0]
    if kind == "para":
        return convert_inline(block[1], warn)
    if kind == "h3":
        return "$(bold)" + convert_inline(block[1], warn) + "$()"
    if kind == "list":
        return "".join("$(li)" + convert_inline(item, warn) for item in block[1])
    if kind == "quote":
        # 引用块整体加斜体 + 弱化色；嵌套强调闭合后要靠 resume 把这两层补回来
        quote = "$(italic)" + QUOTE_COLOR
        return quote + convert_inline(block[1], warn, quote) + "$()"
    if kind == "table":
        return render_table(block[1], warn)
    return ""


def render_pages(title, blocks, warn):
    """一节 → 若干页。图片强制另起 image 页。"""
    pages = []
    buf = []
    pending_title = title

    def flush():
        nonlocal buf, pending_title
        text = "$(br2)".join(x for x in buf if x).strip()
        buf = []
        if text:
            for issue in verify_page_text(text):
                warn("页面「%s」：%s" % (pending_title or "首页", issue))
            page = {"type": "patchouli:text", "text": text}
            if pending_title:
                page["title"] = pending_title
            pages.append(page)
        pending_title = None

    for block in blocks:
        if block[0] == "image":
            flush()
            alt, src, _w, _h = block[1], block[2], block[3], block[4]
            page = {"type": "patchouli:image", "images": [src]}
            if alt:
                page["text"] = convert_inline(alt, warn)
            pages.append(page)
            continue
        rendered = render_block(block, warn)
        if rendered:
            buf.append(rendered)
    flush()
    return pages


# 前言（第一个 `##` 之前的那段，通常是 `>` 引用块）短于这个行数就并进第一节。
# 单占一页会留下半页空白，在首页上表现为「左边只有一句引用」的残页。
MIN_PREAMBLE_LINES = 8

# 分页例外：(md 语言, 条目 id) → 为什么这一条暂时做不到偶数页。
#
# 这五条都是**内容长度**问题，不是排版问题：正文行数正好卡在 2 页与 3 页之间，
# 摊成 4 页会出现不足 7 行的残页（比末页右半空白更难看），压成 2 页又得砍正文。
# 分页器已经把能做的都做了（重排、并节、按句级粒度找切点），剩下的要靠增删正文，
# 而那属于内容决定，不该由排版脚本代劳。
#
# 某条例外不再命中时 build_books 会报错提醒删掉它——不留一份会过期的死名单。
PAGINATION_ODD_EXCEPTIONS = {
    ("zh_cn", "buildings_guide"):
        "前言 12 行 + 先盖哪几座 20 行 = 32 行；2 页容量 30 行装不下，4 页每页不足 7 行",
    ("zh_cn", "advanced_casting_guide"):
        "总体策略 10 行 + 施法锁与装备门控 18 行 = 28 行；后者超过带标题页的 16 行容量",

    # ── 下面这批是「加了插图之后才变奇数」的，成因是插图与正文各占各的页 ──
    #
    # 一张插图占 1 页，整篇要偶数页就得让**正文页数是奇数**。而正文页数能不能是奇数，
    # 由它的行数卡死：1 页装得下 14 行（首页），3 页每页至少 7 行、共需 21 行。
    # 于是**正文 15–20 行**这一段成了死角：1 页装不下、3 页又填不满，只能是 2 页（偶数）。
    # 这类条目一旦配图，页数必为奇数，没有排版解法——要么删图，要么接受末页右半空着。
    ("zh_cn", "element_level_guide"): "正文 20 行；15–20 行死角，配图后无解",
    ("zh_cn", "mages_guide"):         "正文 19 行；15–20 行死角，配图后无解",
    ("zh_cn", "casting_guide"):       "正文 17 行；15–20 行死角，配图后无解",
    ("en_us", "altar_guide"):         "正文 19 行；15–20 行死角，配图后无解",
    ("en_us", "mage_hut_guide"):      "正文 19 行；15–20 行死角，配图后无解",
    # 这两条的正文都在死角边缘卡着：并成一页只差一行（15 行 vs 首页容量 14），
    # 摊开又凑不满 3 页的 21 行。删图或增删正文都能走通，纯排版无解。
    ("zh_cn", "tavern_guide"):
        "正文 4+10 行，并成一页差 1 行（含段间空行共 15 行 > 首页 14）；配图后为 3 页",
    ("zh_cn", "workstation_guide"):
        "正文 21 行；并成一页装不下（含段间空行 22 行 > 首页 14），摊成 3 页又每页不足 7 行，"
        "块界也切不出 7/7/7；配图后为 3 页",
}


def compile_doc(md, warn):
    """md 全文 → (条目名, 页面列表)。"""
    name, sections = None, []
    cur_title, cur_lines = None, []
    for line in md.split("\n"):
        s = line.rstrip()
        if name is None and s.startswith("# "):
            name = to_plain(s[2:])
            continue
        if s.startswith("## "):
            sections.append((cur_title, cur_lines))
            cur_title, cur_lines = to_plain(s[3:]), []
            continue
        cur_lines.append(line)
    sections.append((cur_title, cur_lines))

    rendered = [(title, list(parse_blocks(lines))) for title, lines in sections]
    rendered = [(t, b) for t, b in rendered if b]

    # 前言太短就并进第一节，不单独成页。
    # 合并时把第一节的标题降级成加粗行、**排在前言之后**——前言那行 `> …` 是
    # 「怎么做」的前提行，按规范要跟着条目名走，不能被小节标题挤到下面去。
    if len(rendered) > 1 and rendered[0][0] is None:
        preamble = rendered[0][1]
        preamble_lines = paginate_module.lines_for_text(
            "$(br2)".join(x for x in (render_block(b, warn) for b in preamble) if x))
        if preamble_lines < MIN_PREAMBLE_LINES:
            first_title, first_blocks = rendered[1]
            rendered[1] = (None, preamble + [("h3", first_title)] + first_blocks)
            rendered.pop(0)

    pages = []
    for title, blocks in rendered:
        pages.extend(render_pages(title, blocks, warn))

    # 帕秋莉首页画的是条目名，不会画页面 title；把首页的 title 降级成加粗首行
    if pages and pages[0]["type"] == "patchouli:text" and "title" in pages[0]:
        first = pages[0]
        first["text"] = "$(bold)" + first.pop("title") + "$()$(br2)" + first["text"]

    pages = [p for p in pages if p.get("text", "x") != ""]
    return name, pages


def check_pagination(book_lang, doc, pages, warn, used_exceptions=None):
    """生成期自检：这一篇的页必须都能装进帕秋莉的物理页，页数必须取偶。

    挡的是「内容超容量 → book.json 的 resize 把字号缩小」——那是本书最不能出现的问题
    （玩家读到的字会小到看不清），必须让 build 直接失败，而不是等进游戏靠肉眼发现。
    奇数页同理会空掉末页右半，除了记在 PAGINATION_ODD_EXCEPTIONS 里的那几条。
    """
    if not pages:
        warn("[%s] 条目 %s 没有任何页面" % (book_lang, doc))
        return
    for i, page in enumerate(pages):
        if page.get("type") != "patchouli:text":
            continue
        lines = paginate_module.lines_for_text(page.get("text", ""))
        # 容量看的是这一页**画在书的第几页**：0 号页要腾出条目名的位置。图片页也占页序，
        # 所以下标得按整篇数，不能按正文页重新数。
        cap = paginate_module.page_capacity(page, i)
        if lines > cap:
            warn("[%s] 条目 %s 第 %d 页 %d 行，超过帕秋莉这一页的 %d 行容量（进游戏会被缩小字号）"
                 % (book_lang, doc, i + 1, lines, cap))
    # 图片 / 模板页各占一整页，对开页面上和正文页一样会让末页右半空出来
    if len(pages) > 1 and len(pages) % 2 == 1:
        key = (book_lang, doc)
        if key in PAGINATION_ODD_EXCEPTIONS:
            if used_exceptions is not None:
                used_exceptions.add(key)
            return
        warn("[%s] 条目 %s 共 %d 页（奇数）：帕秋莉左右同时展示，末页右半会空成白纸"
             % (book_lang, doc, len(pages)))


# ---------------------------------------------------------------- 写出
# --check 模式下不落盘：所有生成物收进内存，跑完与磁盘逐字节比对（见 check_outputs）
CHECK_MODE = False
GENERATED = {}


# 手写的插图页 / 模板页必须留住的字段。图片页的 `text` 是图注、`images` 可以多张
# （帕秋莉给每张配左右箭头），模板页则整个 sourceObject 都要原样带走。
ATOMIC_PAGE_KEYS = ("type", "images", "text", "title", "border", "image",
                    "u", "v", "width", "height", "texture_width", "texture_height",
                    "scale", "x", "y", "components", "include", "processor", "anchor")


def load_atomic_pages(path):
    """读一份条目 JSON，摘出手写的非文本页。返回 [(前面压着多少页正文页, 页), …]。"""
    if not path.is_file():
        return []
    try:
        pages = json.loads(path.read_text(encoding="utf-8")).get("pages", [])
    except (OSError, ValueError):
        return []
    out, n = [], 0
    for page in pages:
        if isinstance(page, dict) and page.get("type") == "patchouli:text":
            n += 1
        elif isinstance(page, dict) and page.get("type"):
            out.append((n, {k: v for k, v in page.items() if k in ATOMIC_PAGE_KEYS}))
    return out


def harvest_atomic_pages(book_lang):
    """把这一语言下所有条目手写的插图页先收进内存：{条目 id: [(锚点, 页), …]}。

    **必须在 `entries/` 清空重建之前调**——那一步会把上一轮的手写页连同旧 JSON 一起删掉，
    之后再读就只剩空目录了。锚点按「前面压着多少页正文页」记，源文 md 的长短变了也不会
    把图插到别处去。
    """
    out = {}
    root = OUT_ASSETS / book_lang / "entries"
    if not root.is_dir():
        return out
    for path in root.rglob("*.json"):
        atomics = load_atomic_pages(path)
        if atomics:
            out[path.stem] = atomics
    return out


def reinsert_atomic(pages, atomics, warn, doc):
    """把捞回来的原子页插进刚编译出的 pages，并守住两条会画坏的边界。"""
    if not atomics:
        return pages
    n = len(pages)
    by_anchor = {}
    for idx, page in atomics:
        by_anchor.setdefault(min(idx, n), []).append(page)

    out = []
    for i, page in enumerate(pages):
        out.extend(by_anchor.get(i, ()))
        out.append(page)
    out.extend(by_anchor.get(n, ()))

    # 首页画的是条目名（`PageText.getTextHeight()` 里 `pageNum == 0` 那条），
    # 把图顶在它前面会让条目名压到图上——插在正文第一页之后。
    if out and out[0].get("type") != "patchouli:text":
        warn("[%s] 图片页落在第 0 页：那一页要画条目名，已改插到正文首页之后" % doc)
        head = next((i for i, p in enumerate(out) if p.get("type") == "patchouli:text"), None)
        if head is not None:
            out.insert(head + 1, out.pop(0))
    return out


def write_json(path, obj):
    text = json.dumps(obj, ensure_ascii=False, indent=2) + "\n"
    if CHECK_MODE:
        GENERATED[str(path)] = text
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


# ---------------------------------------------------------------- 运行期清单
def lang_value(key, md_lang, warn):
    """读 lang_src 里的语言键（着陆文案与书名的唯一来源）。读不到就出声并返回空串。"""
    try:
        data = json.loads(LANDING_LANG_FILE.read_text(encoding="utf-8"))
        return data[key][LANG_KEY_SUFFIX[md_lang]]
    except Exception as e:  # 键被删/改名/文件缺失都从这里出声，不静默出一页空着陆
        warn("读不到语言键 %s[%s]：%s" % (key, LANG_KEY_SUFFIX[md_lang], e))
        return ""


def landing_markdown(md_lang, warn):
    """着陆文案：lang_src 的帕秋莉语言键 → 兜底阅读器认的 md。

    文案仍然只有一处来源（book.json 的 landing_text 指的那个键），这里只是把
    `$(l:…:分类/条目)标签$(/l)` 反解成 md 链接、`$(br2)` 反解成空行。其余命令一律出声，
    免得新增命令后兜底把 `$(…)` 原样画给玩家看。
    """
    raw = lang_value(LANDING_LANG_KEY, md_lang, warn)
    if not raw:
        return ""

    cat_ids = {c[0] for c in CATEGORIES}

    def link(m):
        target = m.group(1)
        label = m.group(2)
        # 语言键里写的是 `命名空间:分类/条目`；清单只用不带到命名空间的条目 id
        if ":" in target:
            target = target.split(":", 1)[1]
        if "/" in target:
            target = target.split("/", 1)[1] + ".md"
        else:
            target = ("category:" + target) if target in cat_ids else target + ".md"
        return "[%s](%s)" % (label, target)

    text = re.sub(r"\$\(l:([^)]+)\)(.*?)\$\(/l\)", link, raw)
    text = text.replace("$(br2)", "\n\n")
    if "$(" in text:
        warn("着陆文案里有兜底读不懂的帕秋莉命令，已原样保留：%s" % LANDING_LANG_KEY)
    return text


def build_manifest(md_lang, names_by_doc, warn):
    """运行期清单：兜底阅读器与帕秋莉条目映射共用的一棵结构树。"""
    return {
        "manifest_version": MANIFEST_VERSION,
        "book": BOOK_ID,
        "landing": {
            # 书名与着陆文案都取 book.json 指的那两个语言键，两套渲染因此同名
            "title": lang_value("wandscape.guide_book.name", md_lang, warn),
            "text": landing_markdown(md_lang, warn),
        },
        "categories": [
            {
                "id": cid,
                "name": zh if md_lang == "zh_cn" else en,
                "desc": zh_desc if md_lang == "zh_cn" else en_desc,
                "icon": icon,
                "sortnum": sortnum,
            }
            for cid, zh, en, icon, sortnum, zh_desc, en_desc in CATEGORIES
        ],
        # 保持注册顺序：同一篇 md 登记两次时，先出现的那条是它的规范归属
        "entries": [
            {
                "doc": doc,
                "category": cid,
                "name": names_by_doc.get(doc, doc),
                "icon": icon,
                "sortnum": sortnum,
            }
            for doc, cid, icon, sortnum in ENTRIES
        ],
        "titles": dict(TITLE_TO_DOC_BY_LANG[md_lang]),
    }


def check_manifest(warn):
    """清单与正文的自检：能让兜底跳转/链接静默失效的事，都在这里出声。"""
    doc_to_cat = doc_to_category()
    cat_ids = {c[0] for c in CATEGORIES}

    for title, target in TITLE_TO_DOC.items():
        if target not in doc_to_cat and target not in cat_ids:
            warn("《%s》指向了不存在的目标：%s" % (title, target))

    # 两种语言里恰好同名的标题（如 ATM）必须指向同一处，否则改标题时容易只改一边
    for title, target in TITLE_TO_DOC_ZH.items():
        other = TITLE_TO_DOC_EN.get(title)
        if other is not None and other != target:
            warn("标题《%s》中英两侧指向不同目标：%s / %s" % (title, target, other))

    # 空分类在兜底那边会变成一页只有标题的空目录页，没有存在的理由
    for c in CATEGORIES:
        if not any(e[1] == c[0] for e in ENTRIES):
            warn("分类 %s（%s）下没有任何条目" % (c[0], c[1]))

    # 通用别名（词尾 _guide 去掉）。`index` 是着陆页的保留 id，优先于别名，无需告警；
    # 别名词撞上分类 id 时（buildings / custom / about），该条目必须正好是该分类的第一条——
    # 否则玩家敲分类名会拿到别的页。撞上另一个条目 id 同样是歧义，一并出声。
    first_in_cat = {}
    for doc, cid, _icon, _sortnum in ENTRIES:
        first_in_cat.setdefault(cid, doc)
    for doc, cid in doc_to_cat.items():
        if not doc.endswith("_guide"):
            continue
        alias = doc[: -len("_guide")]
        if alias == "index":
            continue
        if alias in doc_to_cat or (alias in cat_ids and first_in_cat[alias] != doc):
            warn("别名 %s（来自 %s）与条目/分类撞车，玩家敲它拿到的页面不确定" % (alias, doc))

    for md_lang, _book_lang in LANGS:
        lang_dir = SRC_MD / md_lang
        if not lang_dir.is_dir():
            continue
        titles = TITLE_TO_DOC_BY_LANG[md_lang]
        for src in sorted(lang_dir.glob("*.md")):
            doc = src.stem
            if doc not in doc_to_cat:
                warn("md 目录里有未登记的孤儿文档：%s（登记进 ENTRIES 或删掉它）" % src)
            text = src.read_text(encoding="utf-8")
            for m in re.finditer(r"《([^》]+)》", text):
                if m.group(1).strip() not in titles:
                    warn("%s 里的《%s》不在标题表里，两种渲染都会退化成纯文本"
                         % (src.relative_to(ROOT), m.group(1).strip()))
            for m in re.finditer(r"\[[^\]]*\]\(([^)]+)\)", text):
                target = m.group(1).strip()
                if target.startswith(("http://", "https://", "action:", "#")):
                    continue
                if ":" in target and not target.startswith(("guidebook:", "guide:")):
                    continue  # 图片走 wandscape: 资源路径，不是文档链接
                for prefix in ("guidebook:", "guide:"):
                    if target.startswith(prefix):
                        target = target[len(prefix):]
                        break
                target = target[:-3] if target.endswith(".md") else target
                if target.startswith("assets/"):
                    continue
                if target not in doc_to_cat and target not in cat_ids:
                    warn("%s 里的链接指向不存在的文档/分类：%s"
                         % (src.relative_to(ROOT), m.group(1)))


def build_books():
    global _VALID_TARGETS
    warn = Warn()
    cat_by_id = {c[0]: c for c in CATEGORIES}
    known_docs = {e[0] for e in ENTRIES}
    missing = []
    used_exceptions = set()
    _VALID_TARGETS = set(cat_by_id) | known_docs
    check_manifest(warn)

    for md_lang, book_lang in LANGS:
        lang_dir = SRC_MD / md_lang
        if not lang_dir.is_dir():
            missing.append(str(lang_dir))
            continue

        # 手写的插图页得赶在下面那轮清空之前收好——清了就再也读不回来了
        atomic_by_doc = harvest_atomic_pages(book_lang)

        # 清掉上一轮生成物，避免改名/删条目后残留（分类被整个删掉时，空目录也要一并带走）
        # --check 只读，绝不动磁盘
        if not CHECK_MODE:
            for sub in ("categories", "entries"):
                target = OUT_ASSETS / book_lang / sub
                if target.exists():
                    for old in sorted(target.rglob("*.json")):
                        old.unlink()
                    for old in sorted(target.rglob("*"), key=lambda p: len(p.parts), reverse=True):
                        if old.is_dir() and not any(old.iterdir()):
                            old.rmdir()

        for cid, zh, en, icon, sortnum, zh_desc, en_desc in CATEGORIES:
            name, desc = (zh, zh_desc) if md_lang == "zh_cn" else (en, en_desc)
            # 分类描述同样由帕秋莉的 BookTextRenderer 渲染，所以《》与行内标记要和 md 正文一样转换，
            # 否则描述里的《建筑》只是纯文本，点不动。
            write_json(OUT_ASSETS / book_lang / "categories" / (cid + ".json"), {
                "name": name,
                "description": convert_inline(desc, warn),
                "icon": icon,
                "sortnum": sortnum,
            })

        names_by_doc = {}
        for doc, cid, icon, sortnum in ENTRIES:
            src = lang_dir / (doc + ".md")
            if not src.is_file():
                warn("缺少 md 文件，跳过条目：%s" % src)
                continue
            name, pages = compile_doc(src.read_text(encoding="utf-8"), warn)
            if not name:
                warn("md 没有 H1 标题，无法确定条目名：%s" % src)
                continue
            if cid not in cat_by_id:
                warn("条目 %s 引用了未定义分类 %s" % (doc, cid))
            names_by_doc[doc] = to_plain(name)

            # 插图页 / 模板页不用 md 写（`![]()` 那条路被分页器吃图 + 一张图要在中英
            # 两份 md 各写一遍），直接手写进条目 JSON，生成时按原位置带过来。
            entry_path = OUT_ASSETS / book_lang / "entries" / cid / (doc + ".json")
            atomics = atomic_by_doc.get(doc, [])

            entry = {
                "name": name,
                "category": "%s:%s" % (NS, cid),
                "icon": icon,
                "sortnum": sortnum,
                "read_by_default": True,
                "pages": pages,
            }
            # 分页是生成的一部分：单跑 gen 就得到最终形态，不必再手动跑一趟 paginate。
            # 之前那两步的写法出过事——忘了第二步，仓库里就留下「生成物是长节、分页器没跑」的旧状态，
            # 而 --check 又用同一个分页器在内存里比对，两边一起空转，谁都没发现。
            #
            # 原子页这时还没插进 `pages`，只能把张数报给分页器——它据此把**正文页数**的
            # 奇偶配到「加上原子页之后为偶」。插完正好落回偶数页，不用再跑第二趟。
            #
            # 别想着插完再补一趟 `paginate_data` 兜底：它对 `$(br)` 后紧跟的空格不幂等，
            # 跑第二趟会把已经生成的正文改掉（实测 en_us 有 13 篇会被这一趟改出漂移）。
            paginate_module.paginate_data(entry, label="%s/%s" % (book_lang, doc),
                                          atomic_count=len(atomics))
            entry["pages"] = reinsert_atomic(entry["pages"], atomics, warn, doc)
            check_pagination(book_lang, doc, entry["pages"], warn, used_exceptions)
            write_json(entry_path, entry)

        # 兜底阅读器读的那一份：分类、条目、书名号表、着陆文案
        write_json(OUT_RUNTIME / (md_lang + ".json"),
                   build_manifest(md_lang, names_by_doc, warn))

    write_json(OUT_DATA / "book.json", {
        "name": "wandscape.guide_book.name",
        "landing_text": "wandscape.guide_book.landing",
        "subtitle": "wandscape.guide_book.subtitle",
        "book_texture": "wandscape:textures/gui/guidebook/book.png",
        "nameplate_color": "FBE8A6",
        "use_resource_pack": True,
        # 无成就锁定时出版进度条恒为 0%，先关掉；做解锁时再打开
        "show_progress": False,
        # 兜底用：万一还有页超出容量，宁可缩字号也不要截断内容。
        # 正常情况下永远不触发——分页器按帕秋莉的真实容量切页，check_pagination 会在
        # 生成期就把超容量的页报成 build 失败。这里的另一个选项 overflow 会让文字画到
        # 书页外面糊在 GUI 上（书 GUI 没有 scissor 裁剪），truncate 在帕秋莉源码里是坏的
        # （拿绝对屏幕 y 去比页面常量 PAGE_HEIGHT），两个都不能用。
        "text_overflow_mode": "resize",
    })

    # 例外名单是「记录当下的毛病」，不是永久豁免：条目内容改了、页数不再是奇数时，
    # 这条例外就该删掉。留着它会让下一个人以为这里还有问题。
    for key in sorted(set(PAGINATION_ODD_EXCEPTIONS) - used_exceptions):
        warn("分页例外 %s/%s 已经不再命中（现在是偶数页），请从 PAGINATION_ODD_EXCEPTIONS 删掉"
             % key)

    for m in missing:
        warn("语言目录不存在：%s" % m)
    return warn


# ---------------------------------------------------------------- 占位贴图
class Canvas:
    def __init__(self, w, h):
        self.w, self.h = w, h
        self.px = bytearray(w * h * 4)

    def set(self, x, y, c):
        if 0 <= x < self.w and 0 <= y < self.h:
            i = (y * self.w + x) * 4
            self.px[i:i + 4] = bytes(c)

    def over(self, x, y, c):
        """把带 alpha 的 c 叠到现有像素上（等价 MC GuiGraphics.fill 的 ARGB 混合）。"""
        if not (0 <= x < self.w and 0 <= y < self.h):
            return
        a = c[3]
        if a == 0:
            return
        i = (y * self.w + x) * 4
        if a == 255:
            self.px[i:i + 4] = bytes(c)
            return
        for k in range(3):
            self.px[i + k] = (c[k] * a + self.px[i + k] * (255 - a)) // 255
        self.px[i + 3] = min(255, a + self.px[i + 3] * (255 - a) // 255)

    def rect(self, x, y, w, h, c):
        for yy in range(y, y + h):
            for xx in range(x, x + w):
                self.set(xx, yy, c)

    def frame(self, x, y, w, h, c, t=1):
        self.rect(x, y, w, t, c)
        self.rect(x, y + h - t, w, t, c)
        self.rect(x, y, t, h, c)
        self.rect(x + w - t, y, t, h, c)

    def tri(self, x, y, w, h, c, left=True):
        for i in range(h):
            t = abs((h - 1) / 2 - i) / max((h - 1) / 2, 1)
            span = max(int(round(w * (1 - t))), 1)
            self.rect(x + (w - span) if not left else x, y + i, span, 1, c)


# 帕秋莉书皮图集色板（奥术秘典色系）
GOLD_OUTER = (197, 160, 89, 255)
GOLD_INNER = (226, 193, 114, 255)
GOLD_DARK = (122, 88, 24, 255)
GOLD_BRIGHT = (251, 232, 166, 255)
COVER_DARK = (19, 22, 39, 255)
COVER_HIGHLIGHT = (37, 42, 72, 255)
COVER_SHADOW = (10, 12, 22, 255)
SEAM_CREASE = (20, 12, 4, 255)
PAGE_LIGHT = (248, 244, 234, 255)
PAGE_BOTTOM = (240, 232, 212, 255)
PAGE_BORDER = (221, 210, 184, 255)
PAGE_EDGE_1 = (214, 203, 176, 255)
PAGE_EDGE_2 = (226, 215, 190, 255)
BTN_BG_NORMAL = (26, 29, 46, 240)
BTN_BG_HOVER = (44, 49, 78, 255)
BTN_RIM_HOVER = (255, 232, 144, 255)
GLYPH_NORMAL = (229, 195, 120, 255)
GLYPH_HOVER = (255, 251, 234, 255)
BOOKMARK_BG = (138, 26, 38, 255)
BOOKMARK_ADD_BG = (110, 16, 26, 255)
MARK_READ_TICK = (68, 229, 68, 255)


# ---- 绘制原语：语义对齐 MC GuiGraphics 的同名方法 ----
def _fill(c, x1, y1, x2, y2, col):
    """等价 GuiGraphics.fill：x2/y2 为开区间，带 alpha 时混合。"""
    if col[3] == 255:
        c.rect(x1, y1, x2 - x1, y2 - y1, col)
        return
    for yy in range(y1, y2):
        for xx in range(x1, x2):
            c.over(xx, yy, col)


def _hline(c, x1, x2, y, col):
    """等价 GuiGraphics.hLine：两端都含。"""
    _fill(c, x1, y, x2 + 1, y + 1, col)


def _vline(c, x, y1, y2, col):
    """等价 GuiGraphics.vLine：两端都含。"""
    _fill(c, x, y1, x + 1, y2 + 1, col)


def _outline(c, x, y, w, h, col):
    _hline(c, x, x + w - 1, y, col)
    _hline(c, x, x + w - 1, y + h - 1, col)
    _vline(c, x, y + 1, y + h - 2, col)
    _vline(c, x + w - 1, y + 1, y + h - 2, col)


def _gradient_v(c, x, y, w, h, top, bottom):
    """等价 GuiGraphics.fillGradient 的纵向渐变。"""
    for i in range(h):
        t = i / h
        c.rect(x, y + i, w, 1, tuple(int(top[k] + (bottom[k] - top[k]) * t) for k in range(4)))


def _corner_bracket(c, cx, cy, dx, dy):
    """四角蔓藤角扣：L 形金边 + 角心金铆钉。"""
    _hline(c, min(cx, cx + dx * 6), max(cx, cx + dx * 6), cy, GOLD_BRIGHT)
    _vline(c, cx, min(cy, cy + dy * 6), max(cy, cy + dy * 6), GOLD_BRIGHT)
    rx1, rx2 = sorted((cx + dx * 2, cx + dx * 3))
    ry1, ry2 = sorted((cy + dy * 2, cy + dy * 3))
    _fill(c, rx1, ry1, rx2 + 1, ry2 + 1, GOLD_BRIGHT)


def _draw_cover(c, x, y, w, h):
    """硬质封皮：基底 + 明暗倒角 + 双道烫金滚边 + 四角角扣。"""
    _fill(c, x, y, x + w, y + h, COVER_DARK)
    _hline(c, x, x + w - 1, y, COVER_HIGHLIGHT)
    _vline(c, x, y, y + h - 1, COVER_HIGHLIGHT)
    _hline(c, x, x + w - 1, y + h - 1, COVER_SHADOW)
    _vline(c, x + w - 1, y, y + h - 1, COVER_SHADOW)
    _outline(c, x + 2, y + 2, w - 4, h - 4, GOLD_OUTER)
    _outline(c, x + 4, y + 4, w - 8, h - 8, GOLD_INNER)
    _corner_bracket(c, x + 3, y + 3, 1, 1)
    _corner_bracket(c, x + w - 4, y + 3, -1, 1)
    _corner_bracket(c, x + 3, y + h - 4, 1, -1)
    _corner_bracket(c, x + w - 4, y + h - 4, -1, -1)


def _draw_spine(c, spine_x, cover_y, cover_h):
    """中央 3D 弧面书脊 + 三道嵌铆金箍 + 缝线装订深痕。"""
    for i in range(-7, 8):
        t = abs(i) / 7.0
        col = (int(44 * (1 - t) + 14 * t), int(49 * (1 - t) + 16 * t),
               int(82 * (1 - t) + 28 * t), 255)
        _vline(c, spine_x + i, cover_y + 1, cover_y + cover_h - 2, col)

    for ry in (cover_y + 24, cover_y + cover_h // 2, cover_y + cover_h - 24):
        _fill(c, spine_x - 7, ry - 1, spine_x + 8, ry + 2, GOLD_OUTER)
        _hline(c, spine_x - 7, spine_x + 7, ry - 1, GOLD_INNER)
        _hline(c, spine_x - 7, spine_x + 7, ry + 1, GOLD_DARK)
        _fill(c, spine_x - 5, ry, spine_x - 3, ry + 1, GOLD_BRIGHT)
        _fill(c, spine_x + 4, ry, spine_x + 6, ry + 1, GOLD_BRIGHT)

    _vline(c, spine_x, cover_y + 8, cover_y + cover_h - 9, SEAM_CREASE)


def _draw_page(c, x, y, w, h, is_left):
    """单页羊皮纸：侧沿叠纸厚度 + 纵向渐变基底 + 内饰框线 + 中缝下凹阴影。

    位置不可动：帕秋莉把正文钉死在 PAGE_WIDTH/HEIGHT + TOP_PADDING +
    LEFT_PAGE_X/RIGHT_PAGE_X 上，羊皮纸必须正好垫在正文底下。
    """
    if is_left:
        _vline(c, x - 2, y + 2, y + h - 3, PAGE_EDGE_1)
        _vline(c, x - 1, y + 1, y + h - 2, PAGE_EDGE_2)
    else:
        _vline(c, x + w + 1, y + 2, y + h - 3, PAGE_EDGE_1)
        _vline(c, x + w, y + 1, y + h - 2, PAGE_EDGE_2)

    _gradient_v(c, x, y, w, h, PAGE_LIGHT, PAGE_BOTTOM)

    fx, fy, fw, fh = x + 4, y + 4, w - 8, h - 8
    _outline(c, fx, fy, fw, fh, PAGE_BORDER)
    _fill(c, fx, fy, fx + 2, fy + 2, GOLD_OUTER)
    _fill(c, fx + fw - 2, fy, fx + fw, fy + 2, GOLD_OUTER)
    _fill(c, fx, fy + fh - 2, fx + 2, fy + fh, GOLD_OUTER)
    _fill(c, fx + fw - 2, fy + fh - 2, fx + fw, fy + fh, GOLD_OUTER)

    # 中缝 16 像素平滑衰减的下凹阴影，向书脊一侧加深
    gutter = 16
    start = x + w - gutter if is_left else x
    for i in range(gutter):
        t = (i if is_left else gutter - i) / gutter
        col = (0x24, 0x18, 0x0A, int(65 * (t ** 1.6)))
        for yy in range(y, y + h):
            c.over(start + i, yy, col)


# ---- 交互按钮：矢量绘制，烘进帕秋莉硬编码取样的 UV 槽 ----
def _btn_preamble(c, x, y, w, h, hovered):
    """hover 外发光 + 底板 + 描边，返回字形颜色。"""
    if hovered:
        _fill(c, x - 1, y - 1, x + w + 1, y + h + 1, (212, 175, 55, 0x44))
    _fill(c, x, y, x + w, y + h, BTN_BG_HOVER if hovered else BTN_BG_NORMAL)
    _outline(c, x, y, w, h, BTN_RIM_HOVER if hovered else GOLD_OUTER)
    return GLYPH_HOVER if hovered else GLYPH_NORMAL


def _draw_arrow_button(c, x, y, w, h, is_left, hovered):
    """翻页箭头（◄ / ►）。"""
    col = _btn_preamble(c, x, y, w, h, hovered)
    cx = x + w // 2
    cy = y + h // 2
    if is_left:
        _vline(c, cx - 3, cy, cy, col)
        _vline(c, cx - 2, cy - 1, cy + 1, col)
        _vline(c, cx - 1, cy - 2, cy + 2, col)
        _vline(c, cx, cy - 3, cy + 3, col)
        _hline(c, cx, cx + 3, cy, col)
    else:
        _vline(c, cx + 3, cy, cy, col)
        _vline(c, cx + 2, cy - 1, cy + 1, col)
        _vline(c, cx + 1, cy - 2, cy + 2, col)
        _vline(c, cx, cy - 3, cy + 3, col)
        _hline(c, cx - 3, cx, cy, col)


def _draw_back_button(c, x, y, w, h, hovered):
    """返回上级按键（↩ 弧形返回箭标）。"""
    col = _btn_preamble(c, x, y, w, h, hovered)
    cx = x + w // 2
    cy = y + h // 2
    _hline(c, cx - 3, cx + 2, cy - 1, col)
    _vline(c, cx + 2, cy - 1, cy + 2, col)
    _hline(c, cx + 1, cx + 2, cy + 2, col)
    _vline(c, cx - 3, cy - 2, cy, col)
    _vline(c, cx - 2, cy - 3, cy + 1, col)


def _draw_bookmark_tab(c, x, y, w, h, is_add, hovered):
    """书签页签真丝底板（is_add 时空位画加号十字），hovered 时叠外圈微光。"""
    _fill(c, x, y, x + w, y + h, BOOKMARK_ADD_BG if is_add else BOOKMARK_BG)
    _outline(c, x, y, w, h, GOLD_BRIGHT if hovered else GOLD_OUTER)
    _vline(c, x + w - 1, y + 1, y + h - 2, GOLD_BRIGHT)

    if is_add:
        cx = x + w // 2
        cy = y + h // 2
        _vline(c, cx, cy - 2, cy + 2, GOLD_BRIGHT)
        _hline(c, cx - 2, cx + 2, cy, GOLD_BRIGHT)

    if hovered:
        _fill(c, x - 1, y - 1, x + w + 1, y + h + 1, (212, 175, 55, 0x33))


def _draw_small_arrow(c, x, y, is_left, hovered):
    """图片页微型矢量翻图箭头（5x7，◄ / ►）。"""
    w, h = 5, 7
    if hovered:
        _fill(c, x - 1, y - 1, x + w + 1, y + h + 1, (212, 175, 55, 0x44))
    _fill(c, x, y, x + w, y + h, BTN_BG_HOVER if hovered else BTN_BG_NORMAL)
    _outline(c, x, y, w, h, BTN_RIM_HOVER if hovered else GOLD_OUTER)
    col = GLYPH_HOVER if hovered else GLYPH_NORMAL
    cy = y + 3
    if is_left:
        _vline(c, x + 1, cy, cy, col)
        _vline(c, x + 2, cy - 1, cy + 1, col)
        _vline(c, x + 3, cy - 2, cy + 2, col)
    else:
        _vline(c, x + 1, cy - 2, cy + 2, col)
        _vline(c, x + 2, cy - 1, cy + 1, col)
        _vline(c, x + 3, cy, cy, col)


# ---- 11x11 功能图标按钮的 7 种字形 ----
def _glyph_resize(c, cx, cy, col):
    _hline(c, cx - 2, cx, cy - 3, col)
    _hline(c, cx - 2, cx, cy + 1, col)
    _vline(c, cx - 3, cy - 2, cy, col)
    _vline(c, cx + 1, cy - 2, cy, col)
    _fill(c, cx - 1, cy - 2, cx, cy - 1, GOLD_BRIGHT)
    _fill(c, cx + 2, cy + 2, cx + 3, cy + 3, col)
    _fill(c, cx + 3, cy + 3, cx + 4, cy + 4, col)


def _glyph_config(c, cx, cy, bg, col):
    _fill(c, cx - 1, cy - 1, cx + 2, cy + 2, col)
    _hline(c, cx - 1, cx + 1, cy - 3, col)
    _hline(c, cx - 1, cx + 1, cy + 3, col)
    _vline(c, cx - 3, cy - 1, cy + 1, col)
    _vline(c, cx + 3, cy - 1, cy + 1, col)
    _fill(c, cx, cy, cx + 1, cy + 1, bg)


def _glyph_eye(c, cx, cy, col, mark_read):
    _hline(c, cx - 2, cx + 2, cy - 2, col)
    _hline(c, cx - 2, cx + 2, cy + 2, col)
    _vline(c, cx - 3, cy - 1, cy + 1, col)
    _vline(c, cx + 3, cy - 1, cy + 1, col)
    _fill(c, cx, cy - 1, cx + 1, cy + 2, col)
    if mark_read:
        _vline(c, cx + 3, cy - 4, cy - 1, MARK_READ_TICK)
        _hline(c, cx + 2, cx + 4, cy - 3, MARK_READ_TICK)


def _glyph_history(c, cx, cy, col):
    _hline(c, cx - 1, cx + 1, cy - 3, col)
    _hline(c, cx - 1, cx + 1, cy + 3, col)
    _vline(c, cx - 3, cy - 1, cy + 1, col)
    _vline(c, cx + 3, cy - 1, cy + 1, col)
    _fill(c, cx - 2, cy - 2, cx - 1, cy - 1, col)
    _fill(c, cx + 2, cy - 2, cx + 3, cy - 1, col)
    _fill(c, cx - 2, cy + 2, cx - 1, cy + 3, col)
    _fill(c, cx + 2, cy + 2, cx + 3, cy + 3, col)
    _fill(c, cx, cy, cx + 1, cy + 1, col)
    _vline(c, cx, cy - 2, cy, col)
    _hline(c, cx, cx + 2, cy, col)


def _glyph_advancements(c, cx, cy, col):
    _hline(c, cx - 2, cx + 2, cy - 3, col)
    _hline(c, cx - 2, cx + 2, cy - 2, col)
    _hline(c, cx - 1, cx + 1, cy - 1, col)
    _vline(c, cx, cy, cy + 2, col)
    _hline(c, cx - 2, cx + 2, cy + 3, col)
    _fill(c, cx - 3, cy - 2, cx - 2, cy - 1, col)
    _fill(c, cx + 3, cy - 2, cx + 4, cy - 1, col)


def _glyph_editor(c, cx, cy, col):
    _fill(c, cx + 2, cy - 3, cx + 3, cy - 2, col)
    _fill(c, cx + 1, cy - 2, cx + 2, cy - 1, col)
    _fill(c, cx, cy - 1, cx + 1, cy, col)
    _fill(c, cx - 1, cy, cx, cy + 1, col)
    _fill(c, cx - 2, cy + 1, cx - 1, cy + 2, col)
    _fill(c, cx - 3, cy + 2, cx - 2, cy + 3, GOLD_BRIGHT)
    _fill(c, cx + 3, cy - 2, cx + 4, cy - 1, col)
    _fill(c, cx + 2, cy - 1, cx + 3, cy, col)


def _draw_icon_button(c, x, y, kind, hovered):
    """11x11 功能图标按钮：缩放 / 配置 / 眼睛 / 标记已读 / 历史 / 成就 / 编辑器。"""
    w = h = 11
    if hovered:
        _fill(c, x - 1, y - 1, x + w + 1, y + h + 1, (212, 175, 55, 0x44))
    bg = BTN_BG_HOVER if hovered else BTN_BG_NORMAL
    _fill(c, x, y, x + w, y + h, bg)
    _outline(c, x, y, w, h, BTN_RIM_HOVER if hovered else GOLD_OUTER)

    col = GLYPH_HOVER if hovered else GLYPH_NORMAL
    cx, cy = x + 5, y + 5
    if kind == "config":
        _glyph_config(c, cx, cy, bg, col)
    elif kind == "eye":
        _glyph_eye(c, cx, cy, col, False)
    elif kind == "mark_read":
        _glyph_eye(c, cx, cy, col, True)
    elif kind == "resize":
        _glyph_resize(c, cx, cy, col)
    elif kind == "history":
        _glyph_history(c, cx, cy, col)
    elif kind == "advancements":
        _glyph_advancements(c, cx, cy, col)
    elif kind == "editor":
        _glyph_editor(c, cx, cy, col)


def _draw_landing_nameplate(c, x, y, w, h):
    """着陆页古典真丝描金燕尾飘带铭牌底托。

    深绯红天鹅绒织锦纵向渐变光泽 + 双道古金滚边 + 左端书壳外翻卷折扣环 +
    右端古典燕尾切角 + 下方六行翻卷下垂阴影。左侧 8px 落在书外（帕秋莉把它
    画在屏幕 (-8,12)），别在左端放重要图案。
    """
    ribbon_h = 24
    ribbon_w = w
    tail_cut = 8
    fold_shadow = (53, 6, 11, 255)

    # 1. 飘带投影（下方柔和双层漫反射）
    _fill(c, x + 4, y + ribbon_h, x + ribbon_w - tail_cut - 2, y + ribbon_h + 1, (0, 0, 0, 0x30))
    _fill(c, x + 6, y + ribbon_h + 1, x + ribbon_w - tail_cut - 6, y + ribbon_h + 2, (0, 0, 0, 0x18))

    # 2. 飘带主体（纵向渐变光泽，右端随行渐收出燕尾）
    for ry in range(ribbon_h):
        x_end = x + ribbon_w - _tail_cut_at(ry, ribbon_h, tail_cut)
        f = ry / (ribbon_h - 1)
        _hline(c, x, x_end - 1, y + ry,
               (int(0x8A * (1 - f) + 0x5A * f), int(0x18 * (1 - f) + 0x0C * f),
                int(0x24 * (1 - f) + 0x14 * f), 255))

    # 3. 上下沿双道古金滚边
    _hline(c, x, x + ribbon_w - 1, y, GOLD_OUTER)
    _hline(c, x, x + ribbon_w - 2, y + 1, GOLD_INNER)
    _hline(c, x, x + ribbon_w - 1, y + ribbon_h - 1, GOLD_OUTER)
    _hline(c, x, x + ribbon_w - 2, y + ribbon_h - 2, GOLD_INNER)

    # 4. 右端燕尾切角描金饰边
    for ry in range(ribbon_h):
        x_end = x + ribbon_w - _tail_cut_at(ry, ribbon_h, tail_cut)
        _vline(c, x_end - 1, y + ry, y + ry, GOLD_BRIGHT)
        _vline(c, x_end - 2, y + ry, y + ry, GOLD_OUTER)

    # 5. 左端书壳外翻卷折扣环 + 左下翻卷下垂阴影折角
    clasp_x = x + 6
    _vline(c, clasp_x - 1, y, y + ribbon_h - 1, GOLD_OUTER)
    _vline(c, clasp_x, y, y + ribbon_h - 1, GOLD_BRIGHT)
    _vline(c, clasp_x + 1, y, y + ribbon_h - 1, GOLD_OUTER)
    for i in range(6):
        _hline(c, x + i, clasp_x, y + ribbon_h + i, fold_shadow)


def _tail_cut_at(ry, ribbon_h, tail_cut):
    """燕尾切角：行越靠近上下沿，右侧收进越多。"""
    dy = abs(ry - (ribbon_h - 1) / 2.0)
    return int((1.0 - dy / ((ribbon_h - 1) / 2.0)) * tail_cut)


def _atlas_slot(c, u, v, w, h, painter):
    """按帕秋莉的取样规则填一格：正常在 (u,v)，hover 在 (u+w,v)。

    见 GuiButtonBook.renderWidget：u + (isHoveredOrFocused() ? width : 0)。
    """
    painter(u, v, False)
    painter(u + w, v, True)


def build_book_texture():
    """512×256 帕秋莉书皮 + 按钮图集（23 类槽位）。

    帕秋莉只认 book_texture 这一张贴图（GuiBook.drawFromTexture 固定 blit 512×256），
    书本外壳、书脊、羊皮纸页、着陆页铭牌与全部交互按钮因此整体烘在图集里，客户端不再
    需要自绘代码。图集坐标 (0,0) 与书本左上角 (bookLeft, bookTop) 重合；只有着陆页铭牌
    (0,180) 是例外——帕秋莉把它画在屏幕 (-8,12)，左侧 8px 落在书外。

    槽位一览（权威表见 docs/plan/patchouli-art-asset-spec.md 一.3）：
        (0,0)        272×180  双页本体（封皮 + 书脊 + 左右页）
        (0,180)      140×31   着陆页铭牌底条
        (140,180)    110×3    分隔条          (140,183)     99×14   搜索框
        (250,180)    16×16    锁图标
        (140/148/156,197)     8×8   未读 / 待办 / 已完成标记
        (272,0 / 10) 18×10    翻页大箭头（右 / 左）
        (272,20 / 27) 5×7     图片页翻图小箭头（右 / 左）
        (272,160 / 170) 13×10 书签页签（已存 / 空位加书签）
        (308,0)      18×9     返回上级
        (308,9/20/31) 11×11   编辑器 / 配置 / 标记已读
        (330,9/20/31) 11×11   调整大小 / 成就 / 历史
        (405,149)    106×106  插图外框（中空）
    按钮 hover 槽位一律是 u + 宽度，见 GuiButtonBook.renderWidget。

    烘不进来的三处（原先由客户端逐控件实时绘制，静态图集表达不了，已舍弃）：
        - 封面外侧落差阴影与外出血 7px/6px：帕秋莉只 blit 272×180 一块，
          画布内没有余地（原版书皮同样边缘零留白铺满整块）
        - 分类按钮 (20×20) 的常驻金框：那些像素同时属于书页背景，烘进去会在每一页
          右页的同一格出现；其悬停态又因 GuiButtonCategory 不走 hover 槽位而无法表达
        - 条目行的悬停金色流光：GuiButtonEntry 不是 GuiButtonBook，没有 hover 槽位
    """
    c = Canvas(512, 256)

    # ── 书本本体：(0,0) 起 272×180 ──
    _draw_cover(c, 0, 0, 272, 180)
    _draw_spine(c, 136, 0, 180)
    _draw_page(c, 8, 5, 125, 170, True)
    _draw_page(c, 139, 5, 125, 170, False)

    # ── 着陆页铭牌底条：帕秋莉画在屏幕 (-8,12)，左侧 8px 落在书外 ──
    _draw_landing_nameplate(c, 0, 180, 140, 31)

    # ── 交互按钮：正常/hover 成对落在帕秋莉硬编码的 UV 槽 ──
    # hover 槽位规则 = u + 宽度，见 GuiButtonBook.renderWidget。
    # 5×7：图片页翻图小箭头（v=20 右 / v=27 左）
    _atlas_slot(c, 272, 20, 5, 7, lambda x, y, hv: _draw_small_arrow(c, x, y, False, hv))
    _atlas_slot(c, 272, 27, 5, 7, lambda x, y, hv: _draw_small_arrow(c, x, y, True, hv))
    # 18×10：主翻页大箭头（v=0 右 / v=10 左）
    _atlas_slot(c, 272, 0, 18, 10, lambda x, y, hv: _draw_arrow_button(c, x, y, 18, 10, False, hv))
    _atlas_slot(c, 272, 10, 18, 10, lambda x, y, hv: _draw_arrow_button(c, x, y, 18, 10, True, hv))
    # 13×10：书签页签（v=160 已存 / v=170 空位加书签）
    _atlas_slot(c, 272, 160, 13, 10, lambda x, y, hv: _draw_bookmark_tab(c, x, y, 13, 10, False, hv))
    _atlas_slot(c, 272, 170, 13, 10, lambda x, y, hv: _draw_bookmark_tab(c, x, y, 13, 10, True, hv))
    # 18×9：返回上级
    _atlas_slot(c, 308, 0, 18, 9, lambda x, y, hv: _draw_back_button(c, x, y, 18, 9, hv))
    # 11×11 功能图标：编辑器 / 配置 / 标记已读（书籍页脚三连）
    _atlas_slot(c, 308, 9, 11, 11, lambda x, y, hv: _draw_icon_button(c, x, y, "editor", hv))
    _atlas_slot(c, 308, 20, 11, 11, lambda x, y, hv: _draw_icon_button(c, x, y, "config", hv))
    _atlas_slot(c, 308, 31, 11, 11, lambda x, y, hv: _draw_icon_button(c, x, y, "mark_read", hv))
    # 11×11 功能图标：调整大小 / 成就 / 历史（着陆页底部图标排）
    _atlas_slot(c, 330, 9, 11, 11, lambda x, y, hv: _draw_icon_button(c, x, y, "resize", hv))
    _atlas_slot(c, 330, 20, 11, 11, lambda x, y, hv: _draw_icon_button(c, x, y, "advancements", hv))
    _atlas_slot(c, 330, 31, 11, 11, lambda x, y, hv: _draw_icon_button(c, x, y, "history", hv))

    # ── 帕秋莉静态内嵌装饰（硬编码取样，非按钮） ──
    # 140,180 110x3：分隔条（古典双道细金线）
    c.rect(140, 180, 110, 1, GOLD_OUTER)
    c.rect(140, 181, 110, 1, GOLD_INNER)
    c.rect(140, 182, 110, 1, GOLD_DARK)

    # 140,183 99x14：搜索框（象牙白羊皮纸内芯 + 古金框线）
    c.rect(140, 183, 99, 14, PAGE_LIGHT)
    c.frame(140, 183, 99, 14, GOLD_OUTER)

    # 250,180 16x16：锁图标（古典金铜挂锁）
    c.frame(254, 181, 8, 7, GOLD_INNER, 2)
    c.rect(252, 187, 12, 9, GOLD_OUTER)
    c.frame(252, 187, 12, 9, COVER_DARK)
    c.rect(257, 190, 2, 4, COVER_DARK)

    # 140/148/156,197 8x8：未读 / 待办 / 完成标记
    # 140,197 未读：青铜圆环
    c.frame(141, 198, 6, 6, (140, 128, 108, 255))
    c.set(142, 197, (140, 128, 108, 255))
    c.set(145, 197, (140, 128, 108, 255))
    c.set(142, 204, (140, 128, 108, 255))
    c.set(145, 204, (140, 128, 108, 255))

    # 148,197 待办：暖珀方块
    c.rect(150, 198, 4, 6, (218, 165, 32, 255))
    c.rect(149, 199, 6, 4, (218, 165, 32, 255))

    # 156,197 完成：翡翠绿对勾
    for i in range(3):
        c.set(157 + i, 201 + i, (46, 160, 67, 255))
        c.set(157 + i, 202 + i, (46, 160, 67, 255))
    for i in range(5):
        c.set(159 + i, 203 - i, (46, 160, 67, 255))
        c.set(159 + i, 204 - i, (46, 160, 67, 255))

    # 405,149 106x106：图片 / 实体 / 多方块外框（中空双金滚边）
    c.frame(405, 149, 106, 106, GOLD_OUTER, 2)
    c.frame(407, 151, 102, 102, COVER_DARK, 1)
    return c


def write_png(path, canvas):
    raw = bytearray()
    stride = canvas.w * 4
    for y in range(canvas.h):
        raw.append(0)
        raw += canvas.px[y * stride:(y + 1) * stride]

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data
                + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF))

    blob = b"\x89PNG\r\n\x1a\n"
    blob += chunk(b"IHDR", struct.pack(">IIBBBBB", canvas.w, canvas.h, 8, 6, 0, 0, 0))
    blob += chunk(b"IDAT", zlib.compress(bytes(raw), 9))
    blob += chunk(b"IEND", b"")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(blob)


def build_textures(force):
    if BOOK_TEXTURE.exists() and not force:
        print("书皮图集已存在，跳过：%s（用 --force 覆盖）" % BOOK_TEXTURE.relative_to(ROOT))
        return
    write_png(BOOK_TEXTURE, build_book_texture())
    print("已生成书皮图集：%s (512x256)" % BOOK_TEXTURE.relative_to(ROOT))


# ---------------------------------------------------------------- 入口
def expected_output(path, text):
    """某个生成物「现在应该长什么样」。

    分页已经在 build_books 里做完了（生成与分页是同一步），所以这里直接比对，
    不用再补做一趟——补做那套写法正是之前「两边一起空转」的来源。
    """
    return text


def check_outputs():
    """--check：拿刚生成的内容与磁盘上的生成物逐字节比对，列出缺失/过期/残留。"""
    problems = []
    on_disk = set()
    for base in (OUT_ASSETS, OUT_DATA, OUT_RUNTIME):
        if base.exists():
            on_disk |= {str(p) for p in base.rglob("*.json")}
    for path, text in GENERATED.items():
        disk = Path(path)
        if not disk.is_file():
            problems.append("缺少生成物：%s" % disk.relative_to(ROOT))
        elif disk.read_text(encoding="utf-8") != expected_output(path, text):
            problems.append("生成物过期，请重跑 gen_patchouli.py + paginate_patchouli_json.py：%s"
                            % disk.relative_to(ROOT))
    for path in sorted(on_disk - set(GENERATED)):
        problems.append("残留生成物（当前结构里已没有）：%s" % Path(path).relative_to(ROOT))
    return problems


def main(argv):
    global CHECK_MODE
    if len(argv) > 1 and argv[1] == "textures":
        build_textures("--force" in argv)
        return 0

    CHECK_MODE = "--check" in argv
    warn = build_books()
    problems = check_outputs() if CHECK_MODE else []
    if not CHECK_MODE:
        cats_zh = len(list((OUT_ASSETS / "zh_cn/categories").glob("*.json")))
        ent_zh = len(list((OUT_ASSETS / "zh_cn/entries").rglob("*.json")))
        ent_en = len(list((OUT_ASSETS / "en_us/entries").rglob("*.json")))
        print("手册 %s：%d 个分类，zh_cn %d 条目 / en_us %d 条目"
              % (BOOK_ID, cats_zh, ent_zh, ent_en))
    for m in warn.items:
        print("  警告: %s" % m)
    for p in problems:
        print("  不一致: %s" % p)
    # 警告与不一致都必须清零：警告会在游戏里变成 [ERROR]、纯文本链接或过期的兜底页
    return 1 if (warn.items or problems) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
