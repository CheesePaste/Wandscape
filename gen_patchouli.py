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
    assets/wandscape/textures/gui/guidebook/book.png      （textures 子命令，占位书皮）

md 语言目录 → Patchouli 语言目录：en → en_us，zh_cn → zh_cn。
Patchouli 以 en_us 目录为枚举索引，因此两套目录都必须完整生成。

用法
    python gen_patchouli.py                # 编译手册 JSON
    python gen_patchouli.py textures       # 生成占位书皮（已存在则不覆盖）
    python gen_patchouli.py textures --force
"""

import json
import re
import struct
import sys
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SRC_MD = ROOT / "src/main/resources/assets/wandscape/guidebook"
OUT_DATA = ROOT / "src/main/resources/data/wandscape/patchouli_books/guide"
OUT_ASSETS = ROOT / "src/main/resources/assets/wandscape/patchouli_books/guide"
BOOK_TEXTURE = ROOT / "src/main/resources/assets/wandscape/textures/gui/guidebook/book.png"

NS = "wandscape"
BOOK_ID = "wandscape:guide"

# md 语言目录 → Patchouli 语言目录
LANGS = [("zh_cn", "zh_cn"), ("en", "en_us")]

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
    ("tlm_guide", "compat", "minecraft:name_tag", 3),

    # ── 关于我们：平台、反馈与制作者，一条打完 ──
    ("about_guide", "about", "minecraft:name_tag", 0),
]

# 《标题》→ 链接目标（条目 id 或分类 id，link_command 两者都认）。条目名与标题同文时才会命中。
TITLE_TO_DOC = {
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

    doc_to_cat = {e[0]: e[1] for e in ENTRIES}
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

    pages = []
    for title, lines in sections:
        blocks = list(parse_blocks(lines))
        if not blocks:
            continue
        pages.extend(render_pages(title, blocks, warn))

    # 帕秋莉首页画的是条目名，不会画页面 title；把首页的 title 降级成加粗首行
    if pages and pages[0]["type"] == "patchouli:text" and "title" in pages[0]:
        first = pages[0]
        first["text"] = "$(bold)" + first.pop("title") + "$()$(br2)" + first["text"]

    pages = [p for p in pages if p.get("text", "x") != ""]
    return name, pages


# ---------------------------------------------------------------- 写出
def write_json(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def build_books():
    global _VALID_TARGETS
    warn = Warn()
    cat_by_id = {c[0]: c for c in CATEGORIES}
    known_docs = {e[0] for e in ENTRIES}
    missing = []
    _VALID_TARGETS = set(cat_by_id) | known_docs

    for md_lang, book_lang in LANGS:
        lang_dir = SRC_MD / md_lang
        if not lang_dir.is_dir():
            missing.append(str(lang_dir))
            continue

        # 清掉上一轮生成物，避免改名/删条目后残留（分类被整个删掉时，空目录也要一并带走）
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
            write_json(OUT_ASSETS / book_lang / "entries" / cid / (doc + ".json"), {
                "name": name,
                "category": "%s:%s" % (NS, cid),
                "icon": icon,
                "sortnum": sortnum,
                "read_by_default": True,
                "pages": pages,
            })



    write_json(OUT_DATA / "book.json", {
        "name": "wandscape.guide_book.name",
        "landing_text": "wandscape.guide_book.landing",
        "subtitle": "wandscape.guide_book.subtitle",
        "book_texture": "wandscape:textures/gui/guidebook/book.png",
        "use_resource_pack": True,
        # 无成就锁定时出版进度条恒为 0%，先关掉；做解锁时再打开
        "show_progress": False,
        # 页面超长时缩放字号而不是截断
        "text_overflow_mode": "resize",
    })

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


COVER = (74, 53, 36, 255)
COVER_DARK = (52, 36, 24, 255)
COVER_HI = (112, 82, 54, 255)
GOLD = (176, 132, 68, 255)
PAGE = (246, 236, 216, 255)
PAGE_EDGE = (214, 198, 168, 255)
PLATE = (150, 112, 58, 255)
PLATE_HOVER = (192, 150, 82, 255)
INK = (246, 236, 216, 255)


def _icon_button(c, u, v, kind, hover):
    c.rect(u, v, 11, 11, PLATE_HOVER if hover else PLATE)
    c.frame(u, v, 11, 11, COVER_DARK)
    if kind == "resize":
        c.frame(u + 3, v + 2, 5, 5, INK)
        c.rect(u + 6, v + 5, 2, 2, INK)
    elif kind == "editor":
        c.rect(u + 4, v + 2, 2, 6, INK)
        c.rect(u + 3, v + 8, 5, 2, INK)
    elif kind == "advancements":
        c.rect(u + 2, v + 7, 7, 2, INK)
        c.rect(u + 3, v + 4, 5, 2, INK)
        c.rect(u + 4, v + 2, 3, 2, INK)
    elif kind == "config":
        c.rect(u + 3, v + 3, 5, 5, INK)
        c.rect(u + 3, v + 5, 5, 1, COVER_DARK)
        c.rect(u + 5, v + 2, 1, 7, INK)
    elif kind == "history":
        c.frame(u + 3, v + 2, 6, 6, INK)
        c.rect(u + 2, v + 4, 2, 1, INK)
    elif kind == "eye":
        c.frame(u + 2, v + 4, 7, 3, INK)
        c.rect(u + 4, v + 4, 3, 3, INK)


def build_book_texture():
    """512×256 占位书皮。坐标全部对齐帕秋莉 GuiBook 的取样区，详见 docs/guidebook-patchouli.md。"""
    c = Canvas(512, 256)

    # 0,0 272x180：整幅双页背景
    c.rect(0, 0, 272, 180, COVER)
    c.frame(0, 0, 272, 180, COVER_DARK, 2)
    c.frame(2, 2, 268, 176, GOLD)
    c.rect(131, 4, 10, 172, COVER_HI)
    c.rect(134, 4, 4, 172, COVER_DARK)
    for px in (15, 141):
        c.rect(px, 18, 116, 156, PAGE)
        c.frame(px, 18, 116, 156, PAGE_EDGE)

    # 0,180 140x31：着陆页名称牌
    c.rect(0, 180, 140, 31, GOLD)
    c.frame(0, 180, 140, 31, COVER_DARK)

    # 140,180 110x3：分隔条
    c.rect(140, 180, 110, 3, GOLD)
    c.rect(140, 181, 110, 1, COVER_HI)

    # 140,183 99x14：搜索框
    c.rect(140, 183, 99, 14, PAGE)
    c.frame(140, 183, 99, 14, COVER_HI)

    # 250,180 16x16：锁
    c.frame(254, 181, 8, 7, GOLD, 2)
    c.rect(252, 187, 12, 9, GOLD)
    c.frame(252, 187, 12, 9, COVER_DARK)
    c.rect(257, 190, 2, 4, COVER_DARK)

    # 140/148/156,197 8x8：未读 / 待办 / 完成
    c.frame(141, 198, 6, 6, (140, 128, 108, 255))
    c.set(142, 197, (140, 128, 108, 255))
    c.set(145, 197, (140, 128, 108, 255))
    c.set(142, 204, (140, 128, 108, 255))
    c.set(145, 204, (140, 128, 108, 255))
    c.rect(150, 198, 4, 6, (196, 148, 40, 255))
    c.rect(149, 199, 6, 4, (196, 148, 40, 255))
    for i in range(3):
        c.set(157 + i, 201 + i, (60, 140, 60, 255))
        c.set(157 + i, 202 + i, (60, 140, 60, 255))
    for i in range(5):
        c.set(159 + i, 203 - i, (60, 140, 60, 255))
        c.set(159 + i, 204 - i, (60, 140, 60, 255))

    # 308,0 18x9（+18 悬停）：返回
    for hover in (0, 1):
        u = 308 + hover * 18
        c.rect(u, 0, 18, 9, PLATE_HOVER if hover else PLATE)
        c.frame(u, 0, 18, 9, COVER_DARK)
        c.tri(u + 7, 2, 4, 5, INK)
        c.rect(u + 11, 4, 3, 1, INK)

    # 272,10 左 / 272,0 右，18x10（+18 悬停）：翻页
    for hover in (0, 1):
        u = 272 + hover * 18
        for left in (True, False):
            v = 10 if left else 0
            c.rect(u, v, 18, 10, PLATE_HOVER if hover else PLATE)
            c.frame(u, v, 18, 10, COVER_DARK)
            c.tri(u + 7 if left else u + 6, v + 2, 5, 6, INK, left)

    # 272,27 左 / 272,20 右，5x7（+5 悬停）：小箭头
    for hover in (0, 1):
        u = 272 + hover * 5
        c.tri(u, 20, 5, 7, INK, left=False)
        c.tri(u, 27, 5, 7, INK, left=True)

    # 272,160 / 272,170，13x10（+13 悬停）：书签页签
    for hover in (0, 1):
        u = 272 + hover * 13
        for v, bright in ((160, 0), (170, 1)):
            col = (222, 182, 104, 255) if bright else (170, 128, 62, 255)
            if hover:
                col = (238, 202, 128, 255) if bright else (196, 152, 80, 255)
            c.rect(u, v, 10, 10, col)
            for i in range(5):
                c.rect(u + 10 + i, v + i, 1, max(10 - 2 * i, 1), col)
            c.frame(u, v, 10, 10, COVER_DARK)

    # 11x11 图标按钮（+11 悬停）
    for u, v, kind in ((330, 9, "resize"), (308, 9, "editor"), (330, 20, "advancements"),
                       (308, 20, "config"), (330, 31, "history"), (308, 31, "eye")):
        for hover in (0, 1):
            _icon_button(c, u + hover * 11, v, kind, hover)

    # 405,149 106x106：图片 / 实体 / 多方块外框（中空）
    c.frame(405, 149, 106, 106, GOLD, 3)
    c.frame(408, 152, 100, 100, COVER_DARK)
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
        print("占位书皮已存在，跳过：%s（用 --force 覆盖）" % BOOK_TEXTURE.relative_to(ROOT))
        return
    write_png(BOOK_TEXTURE, build_book_texture())
    print("已生成占位书皮：%s (512x256)" % BOOK_TEXTURE.relative_to(ROOT))


# ---------------------------------------------------------------- 入口
def main(argv):
    if len(argv) > 1 and argv[1] == "textures":
        build_textures("--force" in argv)
        return 0

    warn = build_books()
    cats_zh = len(list((OUT_ASSETS / "zh_cn/categories").glob("*.json")))
    ent_zh = len(list((OUT_ASSETS / "zh_cn/entries").rglob("*.json")))
    ent_en = len(list((OUT_ASSETS / "en_us/entries").rglob("*.json")))
    print("手册 %s：%d 个分类，zh_cn %d 条目 / en_us %d 条目" % (BOOK_ID, cats_zh, ent_zh, ent_en))
    for m in warn.items:
        print("  警告: %s" % m)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
