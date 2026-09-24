#!/usr/bin/env python3
"""把 `assets/wandscape/textures/guidebook/` 里的插图页插进手册条目 JSON（中英两侧）。

为什么要有这个脚本，而不是在 md 里写 `![]()`：

  - `gen_patchouli.py` 生成的 `patchouli:image` 页会被分页器吃掉（见
    `paginate_patchouli_json.py` 的 `_split_atomic` 注释，那条已经修了）；
  - 更要紧的是**一张图要在中英两份 md 里各写一遍**，图注还得各翻一次。
    图是语言无关的，只有图注分语言——手写进 JSON 反而只有一个来源。

生成器已经认得手写的图片页（`gen_patchouli.py` 的 `harvest_atomic_pages`），
所以这些页写得进去、也留得住。这个脚本只负责**首次落位**：按正文片段找锚点，
插在该页之后。之后再调整位置，直接改 JSON 或改下面的 PLACEMENTS 重跑。

用法：
    python insert_guidebook_images.py            # 落位并打印结果
    python insert_guidebook_images.py --dry-run  # 只看会插到哪
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BOOK = ROOT / "src/main/resources/assets/wandscape/patchouli_books/guide"
TEX = "wandscape:textures/guidebook/%s.png"

# (中文条目 id, 英文条目 id, 分类 id) -> [(中文锚点, 英文锚点, 图片id, 中文图注, 英文图注), …]
#
# 锚点写**正文里的一个片段**（该页 `text` 的子串），图插在**这一页之后**；None 表示插在
# 条目首页之后。不用小节标题当锚点：分页器会把过长的小节标题降级成正文加粗行、
# 或整节挪到下一页，标题字符串并不总在 `title` 字段上。
# 图注要短：图片页的正文区从 `y=120` 起、页高 156，**只放得下 4 行**（约 48 个汉字）。
PLACEMENTS = {
    ("intro_0_guide", "intro_0_guide", "playstyle"): [
        ("左键旋转朝向", "left-click to ", "intro_03_placing",
         "按住右键拖动转镜头，左键把建筑原地转 90 度",
         "Drag with the right button to turn the camera; left click rotates 90 degrees"),
        ("施工结束后会自动弹出命名界面", "the naming screen pops up", "intro_05_naming",
         "施工结束会弹出命名界面，给小镇起个名字",
         "Once construction ends you name the town"),
        ("切到【交换】页签", "switch to the Exchange", "intro_06_exchange_tab",
         "仓库的交换页签：左边是仓库，下面是背包",
         "The Exchange tab: warehouse on the left, your inventory below"),
        ("挑一个配方点【提交】", "switch to the Crafting tab", "intro_07_craft_tab",
         "工作站的合成页签，挑一个配方点提交",
         "The Workstation crafting tab: pick a recipe and submit"),
    ],
    ("element_level_guide", "element_level_guide", "playstyle"): [
        (None, None, "elem_overview_tab",
         "俯瞰模式顶部信息栏：小镇等级与七种元素存量",
         "The overview bar: town level and the seven elements"),
    ],
    ("tourists_guide", "tourists_guide", "playstyle"): [
        ("三值（满意值", "Three Values (Satisfa", "tourist_three_values",
         "建筑左上角那三个图标就是三值，绿满意、蓝魔法、黄奇观",
         "The three icons over a building: green, blue and yellow"),
        ("夜晚会尝试寻找酒店入住", "Tourists interact at building interact spots", "tourist_detail",
         "右键游客打开详情页，四个数都在这一页",
         "Right click a tourist for the detail page with all four numbers"),
    ],
    ("anomaly_guide", "anomaly_guide", "buildings"): [
        (None, None, "bld_repair",
         "建筑面板底部的修复按钮：它数出缺料，再向仓库要",
         "The Repair button at the bottom of a building panel"),
    ],
    ("townhall_guide", "townhall_guide", "buildings"): [
        (None, None, "townhall_panel",
         "市政厅面板：改名、等级与经验，以及复活法师",
         "The Town Hall panel: name, level, XP, and reviving mages"),
    ],
    ("workstation_guide", "workstation_guide", "buildings"): [
        (None, None, "workstation_panel",
         "工作站把元素合成物品，也能把物品分解回元素",
         "The Workstation turns elements into items, or breaks them back down"),
    ],
    ("crafting_guide", "crafting_guide", "buildings"): [
        (None, None, "crafting_panel",
         "合成站发布任务，成品直接进小镇仓库",
         "A Crafting Station task sends the product straight to the warehouse"),
    ],
    ("tavern_guide", "tavern_guide", "buildings"): [
        ("也可以录用现成的简历", "take on a résumé that is", "tavern_panel",
         "酒馆能直接雇一名，也能录用游客留下的简历",
         "The Tavern hires directly, or takes a resume a tourist left"),
    ],
    ("altar_guide", "altar_guide", "buildings"): [
        (None, None, "altar_panel",
         "在祭坛上发布仪式，由一名在世的法师起阵",
         "Order the rite at the Altar; a living mage performs it"),
    ],
    ("mage_hut_guide", "mage_hut_guide", "buildings"): [
        (None, None, "mage_hut_roster",
         "在小镇法师名单里挑人指派，一位法师一间小屋",
         "Assign a mage from the roster; one mage per hut"),
    ],
    ("node_guide", "node_guide", "buildings"): [
        ("得先在它的界面里设好采集次数", "set how many harvests", "node_panel",
         "先在界面里设好采集次数再发布，法师才会去采",
         "Set the harvest count first, then publish, and mages will gather"),
    ],
    ("shop_guide", "shop_guide", "buildings"): [
        ("最大库存", "maximum stock", "shop_panel",
         "最大库存默认是 0，不设这家店永远不会进货",
         "Max stock defaults to 0; leave it and the shop never stocks up"),
    ],
    ("panel_build_guide", "panel_build_guide", "management"): [
        ("左键把建筑原地转 90 度", "left-click to ", "build_adjust",
         "右侧面板能按格微调，左键把建筑原地转 90 度",
         "Nudge by the block on the right; left click rotates 90 degrees"),
    ],
    ("panel_road_guide", "panel_road_guide", "management"): [
        ("四个工具：「替换」", "Four tools. Replace", "road_tools",
         "四个工具里只有替换和样条铺出来的算路",
         "Only Replace and Spline lay an actual road"),
    ],
    ("panel_tasks_guide", "panel_tasks_guide", "management"): [
        ("想让它快点开工就点「加急」", "Rush", "tasks_tabs",
         "任务大厅、工坊流水线、法师名册，任务卡上能加急",
         "Tasks, Workshop and Roster; rush a job from its card"),
    ],
    ("panel_settings_guide", "panel_settings_guide", "management"): [
        ("「本镇」页人人能改", "Town page", "settings_tabs",
         "本镇页人人能改，其余五页要管理员",
         "Anyone can edit Town; the other five pages need an operator"),
    ],
    ("mages_guide", "mages_guide", "magic"): [
        ("跟随模式:开启后", "Follow Mode: When enabled", "mage_panel",
         "法师面板：装备，以及跟随、和平、策略、解雇",
         "The mage panel: gear, plus Follow, Peace, Strategy and Dismiss"),
    ],
    ("casting_guide", "casting_guide", "magic"): [
        (None, None, "casting_slots",
         "卷轴放进策略槽法师才会用，一类最多放三个",
         "A scroll works only in a strategy slot; three per category at most"),
    ],
}

LANGS = (("zh_cn", 0, 1, 3), ("en_us", 1, 0, 4))  # 书目录, 锚点下标, 对照锚点下标, 图注下标


def image_page(image: str, caption: str) -> dict:
    return {"type": "patchouli:image", "images": [TEX % image], "text": caption}


def locate(pages: list, anchor: str | None, fallback: str | None) -> tuple[int, str]:
    """算出插到第几页之后。锚点在正文里找不到就用另一语言的锚点兜底（同一篇结构共用）。"""
    if anchor is None:
        return 1, ""
    hits = [i for i, p in enumerate(pages) if anchor in (p.get("text") or "")]
    if len(hits) == 1:
        return hits[0] + 1, ""
    if not hits and fallback:
        hits = [i for i, p in enumerate(pages) if fallback in (p.get("text") or "")]
        if len(hits) == 1:
            return hits[0] + 1, "锚点「%s」没命中，改用另一语言的锚点" % anchor
    why = "没命中" if not hits else "命中 %d 页" % len(hits)
    return 1, "锚点「%s」%s，图改插在首页之后" % (anchor, why)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--dry-run", action="store_true", help="只报告会插到哪，不写盘")
    args = ap.parse_args()

    notes, touched, skipped = [], 0, 0
    for (doc_zh, doc_en, cid), specs in sorted(PLACEMENTS.items()):
        for book_lang, ai, fi, ci in LANGS:
            doc = doc_zh if book_lang == "zh_cn" else doc_en
            path = BOOK / book_lang / "entries" / cid / (doc + ".json")
            if not path.is_file():
                notes.append("缺失条目：%s" % path.relative_to(ROOT))
                continue
            data = json.loads(path.read_text(encoding="utf-8"))
            pages = data.get("pages", [])

            # 幂等：同一张图已经在里面就不重复插
            have = {i for p in pages if p.get("type") == "patchouli:image"
                    for i in p.get("images", [])}
            for spec in specs:
                image = spec[2]
                if (TEX % image) in have:
                    skipped += 1
                    continue
                at, note = locate(pages, spec[ai], spec[fi])
                if note:
                    notes.append("[%s/%s] %s" % (book_lang, doc, note))
                pages.insert(at, image_page(image, spec[ci]))
                touched += 1

            data["pages"] = pages
            if not args.dry_run:
                path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n",
                                encoding="utf-8")

    for n in notes:
        print("  注意：" + n)
    print("%s %d 张（已在位跳过 %d 张）"
          % ("会插入" if args.dry_run else "已插入", touched, skipped))
    return 1 if any(n.startswith("缺失条目") for n in notes) else 0


if __name__ == "__main__":
    raise SystemExit(main())
