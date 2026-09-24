#!/usr/bin/env python3
"""把原始截图加工成帕秋莉 `patchouli:image` 页能正确显示的 256x256 图。

为什么不能直接用原始截图（`PageImage.java:39-45`）：

    graphics.pose().scale(0.5F, 0.5F, 0.5F);
    graphics.blit(images[index], x*2 + 6, y*2 + 6, 0, 0, 200, 200);

`blit` 的 UV 分母是 **256**（帕秋莉把图集尺寸硬编码在调用里），所以它实际采样的是源图
**UV 0 .. 200/256 = 0.78125** 那一块，再把这 200x200 采样区拉伸成 100x100 逻辑像素。
一张 591x459 的截图直接丢进去会同时中两个招：

  1. **被裁**——右边和下边各约 22% 根本不显示；
  2. **被拉**——非正方形的内容被强拉成正方形。

所以这里先把内容等比缩放进 **200x200** 的方块（不裁不拉），再把它摆到 256x256 画布的
左上角。帕秋莉的采样区正好覆盖这 200x200，屏幕上就是一张不变形的图。

用法：
    python prepare_guidebook_images.py                  # 处理 guidebook_screenshots/ 全部
    python prepare_guidebook_images.py --only a.png b.png
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - 只在没装 Pillow 时报错
    sys.exit("需要 Pillow：pip install Pillow")

ROOT = Path(__file__).resolve().parent
SRC_DIR = ROOT / "guidebook_screenshots"
OUT_DIR = ROOT / "src/main/resources/assets/wandscape/textures/guidebook"
FULL_DIR = OUT_DIR / "full"

# 帕秋莉的采样区（UV 0 .. 200/256）与画布尺寸。改这两个数等于改帕秋莉源码，别动。
CANVAS = 256
CONTENT = 200

# 这本书的截图都在 GUI 缩放 2 下拍，MC 默认字高 8px 逻辑 -> 16px 物理。
# 用它估「缩完还剩多少」，判断这张图进书里还读不读得出来。
SRC_TEXT_PX = 16
LEGIBLE_PX = 8  # 屏幕上的字至少要有这么高（= MC 默认字号）


def process(path: Path) -> dict:
    """一张原始截图 -> 256x256 的成品 + full/ 目录下的高清原图。返回它的可读性数据。"""
    src = Image.open(path).convert("RGBA")
    w, h = src.size

    # 1. 导出高清原图（打进 jar，供游戏内点击放大查看）
    FULL_DIR.mkdir(parents=True, exist_ok=True)
    full_out = FULL_DIR / path.name
    src.save(full_out, optimize=True)

    # 2. 导出 256x256 采样缩略图（供帕秋莉在书页内 100x100 显示）
    scale = min(CONTENT / w, CONTENT / h)
    new = (max(1, round(w * scale)), max(1, round(h * scale)))
    fitted = src.resize(new, Image.LANCZOS)

    canvas = Image.new("RGBA", (CANVAS, CANVAS), (0, 0, 0, 0))
    # 居中摆在采样区里，四边留透明。帕秋莉的采样区正好是这块 200x200。
    canvas.paste(fitted, ((CONTENT - new[0]) // 2, (CONTENT - new[1]) // 2))

    out = OUT_DIR / path.name
    out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(out)

    # 屏幕上的最终尺寸：采样区的 200x200 逻辑像素里，内容占 new[0] x new[1]
    disp_w = new[0] / 2
    disp_h = new[1] / 2
    return {
        "name": path.name,
        "src": (w, h),
        "scale": scale,
        "display": (disp_w, disp_h),
        # 源图字高 * 缩放 * （100 逻辑像素 / 200 采样像素）
        "text_px": SRC_TEXT_PX * scale / 2,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--only", nargs="*", help="只处理这些文件名（默认全部）")
    ap.add_argument("--src", type=Path, default=SRC_DIR)
    args = ap.parse_args()

    if not args.src.is_dir():
        return print("找不到截图目录：%s" % args.src) or 1

    files = sorted(args.src.glob("*.png"))
    if args.only:
        want = set(args.only)
        files = [f for f in files if f.name in want]
    if not files:
        return print("没有可处理的 PNG") or 1

    rows = [process(f) for f in files]

    print("%-30s %-12s %-7s %-12s %s" % ("文件", "原始", "缩放", "书里显示", "等效字高"))
    for r in sorted(rows, key=lambda r: r["text_px"]):
        flag = "可读" if r["text_px"] >= LEGIBLE_PX else ("勉强" if r["text_px"] >= 6 else "读不了")
        print("%-30s %-12s %-7.3f %-12s %.1fpx  %s"
              % (r["name"], "%dx%d" % r["src"], r["scale"],
                 "%.0fx%.0f" % r["display"], r["text_px"], flag))

    bad = [r["name"] for r in rows if r["text_px"] < LEGIBLE_PX]
    if bad:
        print()
        print("%d/%d 张缩完字高不足 %dpx（这类整屏截图进书里读不出正文，"
              "只起「这个界面长这样」的示意作用）：" % (len(bad), len(rows), LEGIBLE_PX))
        for n in sorted(bad):
            print("  ", n)
    print()
    print("输出目录：%s" % OUT_DIR.relative_to(ROOT))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
