#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""paginate_patchouli_json.py — 帕秋莉手册（Patchouli）JSON 条目自动分页工具。

功能定位：
    针对 patchouli_books/**/entries/**/*.json 条目文件做排版自检与自动分页。
    把超过一页容量的正文切到下一页，切点优先选语义边界：
    `（下一页）` 硬分页提示 → `$(br2)` 段界 → `$(li)` 列表/表格行界 → 句号 → 按字硬切。
    切分时自动闭合与恢复样式栈标签（$(bold)、$(italic) 等），并给跨页的表格重发表头。

为什么阈值是这几个数（不是拍脑袋）：
    帕秋莉 GuiBook.PAGE_WIDTH=116 / PAGE_HEIGHT=156 / TEXT_LINE_HEIGHT=9。
    PageText.getTextHeight() 决定正文从哪一行起排：
        pageNum == 0  → 22px（顶部要画条目名）→ 只剩 14 行
        有 title      → 12px（top 画小节标题）→ 16 行
        两者都不是    → -4px                → 17 行
    超出容量的页会被 book.json 的 text_overflow_mode=resize 缩小字号塞进去——
    内容超得越多字越小。所以这里的硬指标就是「一页绝不超容量」，让 resize 永不触发。

    每行能排多少字：116px ÷ 9px(汉字) ≈ 12 个汉字，ASCII 约 6px → 19 个。
    注意别按 13/24 估——那会把 17 行的页算成 15 行，直接导致缩字。

用法：
    python paginate_patchouli_json.py                          # 扫描并处理当前手册所有条目
    python paginate_patchouli_json.py --dry-run                # 演练模式：仅检测并打印分页计划，不改动文件
    python paginate_patchouli_json.py <file_or_dir>            # 处理指定 JSON 文件或目录
    python paginate_patchouli_json.py --max-lines 12           # 覆写单页行数上限（默认 14/16/17，按页型）
    python paginate_patchouli_json.py --keep-hints             # 保留“（展示图片，下一页）”等提示文字不作剔除
    python paginate_patchouli_json.py --check                  # 只体检不改写：列出超容量页、奇数页条目、欠满页

注意：gen_patchouli.py 生成条目时已经调用本模块的 paginate_data，
    「重新生成手册」一步到位；这里的手动入口主要给体检与单独排查用。
"""

import argparse
import glob
import json
import math
import re
import sys
from collections import deque
from pathlib import Path

# 确保在 Windows 控制台输出 UTF-8 字符时不出现乱码
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 默认扫描目录
DEFAULT_SEARCH_PATH = "src/main/resources/assets/wandscape/patchouli_books/guide"

TAG_RE = re.compile(r"\$\(([^)]*)\)")
PAGE_BREAK_HINT_RE = re.compile(
    r"[（(]\s*(?:展示图片[，,]?\s*|放图[，,]?\s*|show\s*image[，,]?\s*)?(?:下一页|翻页|下页|另起一页|next\s*page|page\s*break)\s*[）)]",
    re.IGNORECASE
)
STYLE_PUSH_CMDS = {"bold", "italic", "italics", "strike", "underline", "obf", "k"}
RESET_CMDS = {"", "reset", "clear", "nocolor"}

# ---- 帕秋莉物理页容量（见文件头说明，改之前先读 GuiBook.java / PageText.java） ----
PAGE_CAP_FIRST = 14      # 条目首页（顶部画条目名）
PAGE_CAP_TITLED = 16     # 带小节标题的页
PAGE_CAP_PLAIN = 17      # 普通页（既非首页也无标题）
# 「残页」线：多页条目里某页不足这个行数就要再平衡。
# 定 7 是量出来的：一页 17 行，7 行占四成，看着是「不满」而不是「没内容」；
# 定 8 会把一堆 7 行的页判成残页，逼着分页器去摊开邻页，反而摊出更碎的页。
MIN_PAGE_LINES = 7

CHARS_PER_LINE_CJK = 12.0
CHARS_PER_LINE_ASCII = 19.0

# 切分点：段落分隔（额外占一行空行）、显式换行（不额外占行）、列表项（不额外占行）
_BR2_CMDS = ("br2", "2br", "p")
_BREAK_RE = re.compile(r"\$\((br2|2br|p|br)\)")
_LI_RE = re.compile(r"\$\(li\d*\)")
_SENTENCE_RE = re.compile(r"(?<=[。！？!?；;])")


# ---------------------------------------------------------------- 度量
def strip_formatting(text: str) -> str:
    return TAG_RE.sub("", text)


def estimate_visual_width(plain_text: str) -> float:
    """按「一个汉字 = 1 宽」折算视觉宽度。"""
    width = 0.0
    for ch in plain_text:
        if ord(ch) > 0x2E80:
            width += 1.0
        else:
            width += (CHARS_PER_LINE_CJK / CHARS_PER_LINE_ASCII)
    return width


def _piece_lines(text: str) -> int:
    """一块不含分隔命令的文字占几行（按视觉宽度折算）。"""
    plain = strip_formatting(text).strip()
    if not plain:
        return 0
    return max(1, int(math.ceil(estimate_visual_width(plain) / CHARS_PER_LINE_CJK)))


def lines_for_text(text: str) -> int:
    """一页正文渲染出来占几行。

    必须把 `$(br2)` 的空行算进去——只按总宽度除行宽会漏掉段间空行，
    估算就比实际小，于是「看着没超的一页」进游戏反倒被缩字。
    pack_chunks 的累加口径要和这里**逐块一致**。
    """
    total = 0
    for sep, _piece, n in to_chunks(text, 10 ** 9):
        total += n + (1 if sep == "$(br2)" else 0)
    return total


def is_cjk_dominant(text: str) -> bool:
    plain = strip_formatting(text)
    if not plain:
        return True
    cjk_count = sum(1 for ch in plain if ord(ch) > 0x2E80)
    return cjk_count > (len(plain) * 0.2)


# ---------------------------------------------------------------- 切块
def to_chunks(text: str, max_chunk_lines: int):
    """把一页正文拆成最小块：[(sep, text, lines)]。

    sep 是这块与上一块之间的分隔命令（首块为 ""）：
        "$(br2)" 段落分隔（渲染时额外占一行空行）
        "$(br)"  显式换行
        "$(li)"  列表/表格项
    单个块超过 max_chunk_lines 时继续按句号切，句子还超就按字数硬切——
    否则这种块在 pack 阶段会整块顶出一页。
    """
    chunks = []
    for sep, seg in _split_blocks(text):
        seg = seg.strip()
        if not seg:
            continue
        # 每个原始块的首片沿用块间分隔（$(br2)/(br)/(li)），
        # 块内再切出来的续片改成普通换行——不然续片会继承 $(li) 变成多一个子弹
        for k, (_piece_sep, piece) in enumerate(_split_oversized(seg, max_chunk_lines)):
            chunks.append((sep if k == 0 else "$(br)",
                           piece, _piece_lines(piece)))
    return [(s, t, n) for s, t, n in chunks if t.strip()]


def _split_blocks(text: str):
    """按 $(br2) / $(br) / $(li) 把正文切成 (分隔命令, 片段)。"""
    out = []
    pos = 0
    pending_sep = ""
    for m in re.finditer(r"\$\((br2|2br|p|br)\)|\$\((li\d*)\)", text):
        piece = text[pos:m.start()]
        if piece.strip():
            out.append((pending_sep, piece))
        elif not out:
            pass  # 开头的分隔命令丢掉，免得页面以空行起头
        cmd = m.group(1) or m.group(2)
        pending_sep = "$(br2)" if cmd in _BR2_CMDS else ("$(br)" if cmd == "br" else "$(li)")
        pos = m.end()
    tail = text[pos:]
    if tail.strip():
        out.append((pending_sep, tail))
    return out


def _split_oversized(seg: str, max_lines: int):
    """把超长的单块按句号切；句子仍超长就按字数硬切。首块 sep 为 ""。"""
    if _piece_lines(seg) <= max_lines:
        return [("", seg)]

    pieces = [p for p in _SENTENCE_RE.split(seg) if p.strip()]
    if len(pieces) == 1:
        pieces = _hard_cut(seg, max_lines)

    out, buf, buf_lines = [], [], 0
    for p in pieces:
        p_lines = _piece_lines(p)
        if p_lines > max_lines:
            if buf:
                out.append(("", "".join(buf)))
                buf, buf_lines = [], 0
            for sub in _hard_cut(p, max_lines):
                out.append(("", sub))
            continue
        if buf and buf_lines + p_lines > max_lines:
            out.append(("", "".join(buf)))
            buf, buf_lines = [], 0
        buf.append(p)
        buf_lines += p_lines
    if buf:
        out.append(("", "".join(buf)))
    return out


def _hard_cut(seg: str, max_lines: int):
    """无可切标点的长串，按每行容量折算成定长片段。"""
    per_line = CHARS_PER_LINE_CJK
    budget = max(1, int(max_lines * per_line))
    plain = strip_formatting(seg)
    # 按视觉宽度而不是字符数切片，中英混排才不会切歪
    pieces, buf, width = [], [], 0.0
    for ch in seg:
        w = 1.0 if ord(ch) > 0x2E80 else (per_line / CHARS_PER_LINE_ASCII)
        if buf and width + w > budget:
            pieces.append("".join(buf))
            buf, width = [], 0.0
        buf.append(ch)
        width += w
    if buf:
        pieces.append("".join(buf))
    if not plain:
        return [seg]
    return pieces or [seg]


# ---------------------------------------------------------------- 样式栈
def extract_style_state(text: str):
    """一段文字结束时还开着的样式命令（跨页要继续生效的那些）。"""
    active = set()
    for match in TAG_RE.finditer(text):
        cmd = match.group(1).strip()
        if cmd in RESET_CMDS:
            active.clear()
        elif cmd in STYLE_PUSH_CMDS:
            active.add(cmd)
        elif cmd.startswith("#"):
            active.add(cmd)
    return active


def _carry_styles(pages):
    """把跨页还开着的样式在下一页补回来，并在上一页末尾收干净。"""
    out = []
    carry = set()
    for idx, page in enumerate(pages):
        text = page
        if carry:
            text = "".join("$(%s)" % s for s in sorted(carry)) + text
        active = extract_style_state(text)
        if active and idx < len(pages) - 1:
            text += "$()"
            carry = active
        else:
            carry = set()
        out.append(text)
    return out


# ---------------------------------------------------------------- 表头
def _is_table_header(text: str) -> bool:
    """认表格首块：加粗短行（render_table 输出的 `$(bold)表头$()`）。

    光看这一块不够——正文里也可能有加粗单行。调用方要再确认它后面紧跟 $(li)，
    两个条件都成立才当表头，否则会把普通加粗行复制到每一页。
    """
    text = text.strip()
    return (text.startswith("$(bold)") and text.endswith("$()")
            and _piece_lines(text) <= 1)


# ---------------------------------------------------------------- 打包成页
def _join_chunks(chunks):
    return "".join((sep + text) for sep, text, _n in chunks).strip()


def _slice_lines(chunks):
    """一段切块单独成页时占几行（页首那块不跟空行，所以首块的 $(br2) 不计）。"""
    total = 0
    for i, (sep, _text, n) in enumerate(chunks):
        total += n + (1 if (i and sep == "$(br2)") else 0)
    return total


def _min_pages(chunks, caps):
    """这段切块至少需要几页。

    不能拿「总行数 ≤ 容量之和」来估：块是原子的，一页装不下半块。
    30 行落进 14+17=31 看起来够，可三块是 8/14/6 时两种切法（8 | 20、23 | 6）
    都超容量，实际得三页。所以这里直接让切分算法试，试到能切为止。
    """
    if not chunks:
        return 0
    for k in range(1, len(caps) + 1):
        if _balanced_cut(chunks, caps[:k]) is not None:
            return k
    return len(caps) + 1


def _balanced_cut(chunks, caps):
    """把 chunks 切成 len(caps) 页，每页不超容量，且**尽量均分**。

    贪心「装满一页再开下一页」会切出 13 行 + 4 行这种一头沉：一节的最后一段
    往往很短，全被推给末页。这里用一遍 DP 在所有合法切点里挑页间行数最接近的，
    所以一节占两页时是 8/9 而不是 13/4。返回 None 表示这个页数装不下。
    """
    n, m = len(chunks), len(caps)
    if m == 0 or n == 0:
        return None
    INF = float("inf")
    # dp[j][i] = 前 i 块放进 j 页、按「页行数平方和最小」算的最优值
    dp = [[INF] * (n + 1) for _ in range(m + 1)]
    back = [[0] * (n + 1) for _ in range(m + 1)]
    dp[0][0] = 0
    for j in range(1, m + 1):
        for i in range(1, n + 1):
            for k in range(j - 1, i):
                if dp[j - 1][k] == INF:
                    continue
                lines = _slice_lines(chunks[k:i])
                if lines > caps[j - 1]:
                    continue
                cost = dp[j - 1][k] + lines * lines
                if cost < dp[j][i]:
                    dp[j][i] = cost
                    back[j][i] = k
    if dp[m][n] == INF:
        return None
    cuts, i = [], n
    for j in range(m, 0, -1):
        k = back[j][i]
        cuts.append((k, i))
        i = k
    cuts.reverse()
    return [chunks[a:b] for a, b in cuts]


def pack_chunks(chunks, cap_first: int, cap_rest: int, max_chars=None):
    """把切块装进页里，返回页文本列表。首页按 cap_first，其余页按 cap_rest。

    max_chars 是额外的软约束（默认不设），只影响每页切多长的下限判断。
    """
    if not chunks:
        return []
    caps = [cap_first] + [cap_rest] * (len(chunks) - 1)
    m = _min_pages(chunks, caps)
    sliced = _balanced_cut(chunks, caps[:m]) if m <= len(caps) else None
    if sliced is None:
        # 装不下（例如首页容量小、内容全是一大段）→ 退回逐块贪心 + 硬切兜底
        return _pack_greedy(chunks, cap_first, cap_rest, max_chars)
    return _render_pages(sliced)


def _render_pages(sliced):
    """把切好的块组渲染成页面文本，处理表头重发与样式栈。"""
    pages = []
    for chunks in sliced:
        body = []
        header = ""
        for i, (sep, text, _n) in enumerate(chunks):
            if i and sep != "$(li)":
                header = text if (i + 1 < len(chunks) and chunks[i + 1][0] == "$(li)"
                                  and _is_table_header(text)) else ""
            if not body:
                # 续页重发表头，让读者知道这几行列表项属于哪张表
                if header and text != header:
                    body.append((text, header))
                # 行首不能是换行/空行；$(li) 要留着，那是列表项的子弹
                if sep in ("$(br2)", "$(br)"):
                    sep = ""
            body.append((sep, text))
        pages.append("".join(sep + text for sep, text in body).strip())
    return [p for p in pages if p.strip()]


def _pack_greedy(chunks, cap_first, cap_rest, max_chars=None):
    """兜底装法：逐块贪心，装不下就硬切。只在 DP 无解时走到。"""
    queue = deque(chunks)
    pages = []
    cur = []
    cur_lines = 0
    cap = cap_first
    header = ""

    while queue:
        sep, text, n = queue.popleft()
        if sep != "$(li)":
            header = text if (queue and queue[0][0] == "$(li)" and _is_table_header(text)) else ""

        extra = 1 if (cur and sep == "$(br2)") else 0
        room = cap - cur_lines - extra
        if n > room:
            if cur and cur_lines >= MIN_PAGE_LINES:
                pages.append(_join_chunks(cur))
                cur, cur_lines, cap = [], 0, cap_rest
                queue.appendleft((sep, text, n))
                continue
            subs = _hard_cut(text, max(1, room))
            for idx in range(len(subs) - 1, -1, -1):
                queue.appendleft(("" if idx == 0 else "$(br)", subs[idx], _piece_lines(subs[idx])))
            continue

        if not cur:
            if header and text != header:
                cur.append(("", header, _piece_lines(header)))
                cur_lines += _piece_lines(header)
            if sep in ("$(br2)", "$(br)"):
                sep = ""
        cur.append((sep, text, n))
        cur_lines += extra + n

    if cur:
        pages.append(_join_chunks(cur))
    return [p for p in pages if p.strip()]


def split_page_text(text: str, cap_first: int, cap_rest: int, remove_hints=True, max_chars=None):
    """一页正文 → 若干页正文。

    `（下一页）` 是作者手写的硬分页提示，优先级最高：先按它切组，组内再按容量切。
    """
    groups = []
    if remove_hints:
        last = 0
        for m in PAGE_BREAK_HINT_RE.finditer(text):
            groups.append(text[last:m.start()])
            last = m.end()
        groups.append(text[last:])
    else:
        groups = [text]

    pages = []
    for gi, group in enumerate(groups):
        group = group.strip()
        if not group:
            continue
        # 按本组可能出现的最小容量预切：这样每块都装得进任何一页，DP 一定有解
        chunks = to_chunks(group, min(cap_first, cap_rest))
        if not chunks:
            continue
        pages.extend(pack_chunks(chunks, cap_first if gi == 0 else cap_rest,
                                 cap_rest, max_chars=max_chars))
    return _carry_styles(pages)


# ---------------------------------------------------------------- 平衡
def _page_lines(data: dict) -> int:
    return lines_for_text(data.get("text", ""))


def _cap_for(data: dict, index: int) -> int:
    if index == 0:
        return PAGE_CAP_FIRST
    if data.get("title"):
        return PAGE_CAP_TITLED
    return PAGE_CAP_PLAIN


def page_capacity(data: dict, index: int) -> int:
    """这一页最多排多少行（`_cap_for` 的公开版，给生成期自检用）。"""
    return _cap_for(data, index)


def _demote_title(data: dict) -> dict:
    """把一页的小节标题降级成正文加粗首行（同 gen 对首页的做法），合并时用。

    合并会让这一页的小节标题失去「页眉」位置，但标题本身不能丢——
    降级成加粗行，读者照样看得见这一节的起点。
    """
    out = dict(data)
    title = out.pop("title", "")
    if title:
        out["text"] = "$(bold)" + title + "$()$(br2)" + out.get("text", "")
    return out


def _all_fit(pages) -> bool:
    return all(_page_lines(p) <= _cap_for(p, i) for i, p in enumerate(pages))


def _caps_for(start: int, has_title: bool, count: int):
    """从条目第 start 页起连续 count 页各自的容量。

    组内第一页可能是条目首页（只剩 14 行）或带小节标题（16 行），
    之后每页都是普通页（17 行）。
    """
    if start == 0:
        first = PAGE_CAP_FIRST
    elif has_title:
        first = PAGE_CAP_TITLED
    else:
        first = PAGE_CAP_PLAIN
    return [first] + [PAGE_CAP_PLAIN] * max(0, count - 1)


def _group_by_title(pages):
    """按小节标题把页分组：每遇到一页带标题就开一组，条目首页自成第一组。

    一组的页共享一个小节标题（只画在组内第一页的页眉上）。平衡时**只动组内**，
    组与组之间的边界不动，所以每个小节标题都还留在页眉位置上。
    """
    groups = []
    for i, page in enumerate(pages):
        if i == 0 or page.get("title"):
            groups.append([])
        groups[-1].append(page)
    return groups


def _group_chunks(group, limit=PAGE_CAP_FIRST):
    """一组的正文按顺序拼成切块序列（跨页处补回段落分隔）。

    limit 是单块行数上限：调小就把长段按句子切得更碎。块是原子的，
    粗块会让「这一节该占几页」没有解（8/7/10 三块塞不进 14+17），
    所以凑不出偶数页时会用更细的粒度重试。
    """
    chunks = []
    for k, page in enumerate(group):
        for j, (sep, text, n) in enumerate(to_chunks(page.get("text", ""), limit)):
            if not chunks:
                sep = ""
            elif k and j == 0:
                sep = "$(br2)"      # 原来切页时丢掉的段间分隔，这里补回来
            chunks.append((sep, text, n))
    return chunks


def _has_thin(pages) -> bool:
    """有没有「残页」：多页条目里某页不足 MIN_PAGE_LINES 行。

    末页也算——17 行的内容切成 13+4 和切成 9+8 页数一样，后者才是想要的。
    真正的短条目会在 `_reflow` 里就定成 1 页，根本走不到这里。
    """
    return len(pages) > 1 and any(_page_lines(p) < MIN_PAGE_LINES for p in pages)


def balance_pages(pages):
    """把整篇收敛到「偶数页 + 没有残页」。

    偶数页是因为帕秋莉左右同时展示，奇数页末尾右半会空成白纸。
    做法是**按小节重新排版**：小节之间的边界不动（标题留在页眉），只在组内决定这一节占几页。
    页数总和是奇数时，先试着把某一节从 1 页摊成 2 页（摊完两页都得够满）；
    实在摊不动就把相邻两组并成一组（后一组标题降级成加粗行）。
    单页条目（内容本来就装不满一页）不参与平衡。
    """
    if len(pages) <= 1 or not _all_fit(pages):
        return pages
    if len(pages) % 2 == 0 and not _has_thin(pages):
        return pages

    groups = _group_by_title(pages)
    # 摊不开就并节，一直并到排版结果达标或只剩一节
    for _ in range(len(groups)):
        # 块切得越细，页界越好找；粗块切不动时才用细块（会多几个段中换行）
        for limit in (PAGE_CAP_FIRST, 10, MIN_PAGE_LINES):
            out = _reflow(groups, limit)
            if out is not None and not _has_thin(out):
                return out
        groups = _merge_two_groups(groups)
        if groups is None:
            break
    return pages


def _reflow(groups, limit=PAGE_CAP_FIRST):
    """给每组定页数并重排；凑不成偶数页返回 None。"""
    infos = []
    for i, group in enumerate(groups):
        chunks = _group_chunks(group, limit)
        if not chunks:
            return None
        title = group[0].get("title")
        # 只有第一组落在条目首页（容量 14），其余组至少也是带标题页（16）
        start = 0 if i == 0 else 1
        lo = _min_pages(chunks, _caps_for(start, bool(title), len(chunks)))
        if lo > len(chunks):                      # 单块超出任何容量，切不动
            return None
        hi = max(lo, min(_slice_lines(chunks) // MIN_PAGE_LINES, len(chunks)))
        infos.append((chunks, title, start, lo, hi))

    plan = [lo for _c, _t, _s, lo, _hi in infos]
    if sum(plan) % 2 == 1:
        # 摊开一节（多在 1 → 2 页）凑偶数。**摊完两页都得够满**才算数：
        # 16 行摊成 8+8 是好事，12 行摊成 6+6 只是把残页从一页变成两页，宁可不摊。
        best = None
        for i, (chunks, title, start, lo, hi) in enumerate(infos):
            if hi <= lo:
                continue
            sliced = _balanced_cut(chunks, _caps_for(start, bool(title), lo + 1))
            if sliced is None:
                continue
            fills = [_slice_lines(part) for part in sliced]
            if min(fills) < MIN_PAGE_LINES:
                continue
            score = min(fills)
            if best is None or score > best[0]:
                best = (score, i)
        if best is None:
            return None
        plan[best[1]] += 1

    out = []
    for (chunks, title, start, _lo, _hi), count in zip(infos, plan):
        sliced = _balanced_cut(chunks, _caps_for(start, bool(title), count))
        if sliced is None:
            return None
        for k, part in enumerate(sliced):
            page = {"type": "patchouli:text", "text": _render_pages([part])[0]}
            if k == 0 and title:
                page["title"] = title
            out.append(page)
    return out if _all_fit(out) else None


def _merge_two_groups(groups):
    """把相邻两组并成一组（后一组的标题降级成加粗首行），找合并后内容最少的一对。

    只在「摊不开」时兜底：合并会让一个小节标题从页眉降级成正文加粗行，
    但标题本身不丢——总好过末页右半永远空着。
    """
    if len(groups) < 2:
        return None
    best = None
    for i in range(len(groups) - 1):
        head = groups[i][0]
        tail_page = _demote_title(groups[i + 1][0])
        combined = [dict(head, text=head.get("text", "").rstrip() + "$(br2)" + tail_page["text"])]
        combined += groups[i][1:] + [_demote_title(p) for p in groups[i + 1][1:]]
        size = _slice_lines(_group_chunks(combined))
        if best is None or size < best[0]:
            best = (size, i, combined)
    _size, i, combined = best
    return groups[:i] + [combined] + groups[i + 2:]


# ---------------------------------------------------------------- 入口处理
def paginate_data(data: dict, max_lines_override=None, max_chars_override=None,
                  remove_hints=True, verbose=False, label="") -> bool:
    """就地把条目 dict 的 pages 切开并平衡；有改动返回 True。"""
    pages = data.get("pages")
    if not pages or not isinstance(pages, list):
        return False

    original = json.dumps(pages, ensure_ascii=False, sort_keys=True)

    cap_first = max_lines_override or PAGE_CAP_FIRST
    cap_rest = max_lines_override or PAGE_CAP_PLAIN

    new_pages = []
    for page in pages:
        if page.get("type") != "patchouli:text" or "text" not in page:
            new_pages.append(page)
            continue
        text = page.get("text", "")
        # 有标题的页容量小一行，先按它切；切出来的续页再按普通页/标题页各算各的
        cap_here_first = cap_first if not new_pages else (PAGE_CAP_TITLED if page.get("title") else cap_rest)
        parts = split_page_text(text, cap_here_first, cap_rest,
                                remove_hints=remove_hints, max_chars=max_chars_override)
        if not parts:
            parts = [text]
        if len(parts) == 1 and parts[0] == text:
            new_pages.append(page)
            continue
        head = dict(page)
        head["text"] = parts[0]
        new_pages.append(head)
        for cont in parts[1:]:
            new_pages.append({"type": "patchouli:text", "text": cont})

    balanced = balance_pages(new_pages)

    if verbose and label and json.dumps(balanced, ensure_ascii=False, sort_keys=True) != original:
        print(" -> %s: %d 页 → %d 页" % (label, len(pages), len(balanced)))
        for i, p in enumerate(balanced):
            if p.get("type") == "patchouli:text":
                print("    [p%d] %d 行%s" % (i + 1, _page_lines(p),
                                             "（标题）" if p.get("title") else ""))

    if json.dumps(balanced, ensure_ascii=False, sort_keys=True) == original:
        return False
    data["pages"] = balanced
    return True


def process_entry_file(file_path: Path, max_lines_override=None, max_chars_override=None,
                       remove_hints=True, dry_run=False, verbose=False) -> bool:
    """处理单个帕秋莉条目 JSON 文件。如果有页面被改动，返回 True。"""
    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
    except Exception as e:
        print(" [跳过] 无法读取 JSON 文件 %s: %s" % (file_path, e))
        return False

    if not paginate_data(data, max_lines_override, max_chars_override, remove_hints,
                         verbose=verbose, label=file_path.name):
        return False

    if not dry_run:
        file_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    return True


# ---------------------------------------------------------------- 体检
def audit(entries):
    """体检：列出超容量页、奇数页条目、欠满页。返回 (问题列表, 统计 dict)。"""
    problems = []
    stats = {"pages": 0, "over": 0, "odd": 0, "thin": 0}
    for rel, data in entries:
        pages = [p for p in data.get("pages", []) if p.get("type") == "patchouli:text"]
        stats["pages"] += len(pages)
        if len(pages) > 1 and len(pages) % 2 == 1:
            stats["odd"] += 1
            problems.append("奇数页条目（末页右半会空）：%s（%d 页）" % (rel, len(pages)))
        for i, p in enumerate(pages):
            lines = _page_lines(p)
            cap = _cap_for(p, i)
            if lines > cap:
                stats["over"] += 1
                problems.append("超容量页（会被缩小字号）：%s 第 %d 页 %d 行 / 容量 %d"
                                % (rel, i + 1, lines, cap))
            elif lines < MIN_PAGE_LINES and i < len(pages) - 1:
                stats["thin"] += 1
                problems.append("欠满页（非末页）：%s 第 %d 页 只有 %d 行" % (rel, i + 1, lines))
    return problems, stats


def _collect(target: str):
    root_path = Path(target)
    files = []
    if root_path.is_file() and root_path.suffix == ".json":
        files.append(root_path)
    elif root_path.is_dir():
        entries_dir = root_path / "entries" if (root_path / "entries").exists() else root_path
        files.extend(sorted(entries_dir.rglob("*.json")))
    else:
        for match in glob.glob(target, recursive=True):
            p = Path(match)
            if p.is_file() and p.suffix == ".json":
                files.append(p)
    return [f for f in files if "categories" not in f.parts]


def main():
    parser = argparse.ArgumentParser(description="帕秋莉手册（Patchouli）JSON 条目自动分页工具")
    parser.add_argument("target", nargs="?", default=DEFAULT_SEARCH_PATH,
                        help="目标文件路径、目录或 glob 通配符（默认扫描 guide/**/entries）")
    parser.add_argument("--max-lines", type=int, default=None,
                        help="覆写单页行数上限（默认首页 14、带标题页 16、普通页 17）")
    parser.add_argument("--max-chars", type=int, default=None,
                        help="单页汉字数上限（默认不设，只按行数算）")
    parser.add_argument("--dry-run", action="store_true",
                        help="演练模式：仅打印分页结果，不写回文件")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="输出详细检测与行数估算日志")
    parser.add_argument("--keep-hints", action="store_true",
                        help="不清理（展示图片，下一页）等提示文字")
    parser.add_argument("--check", action="store_true",
                        help="只体检不改写：列出超容量页、奇数页条目、欠满页")
    args = parser.parse_args()

    json_files = _collect(args.target)
    if not json_files:
        print("未找到可处理的条目 JSON 文件：%s" % args.target)
        return 0

    if args.check:
        entries = []
        for jf in json_files:
            try:
                entries.append((str(jf), json.loads(jf.read_text(encoding="utf-8"))))
            except Exception as e:
                print(" [跳过] 无法读取 JSON 文件 %s: %s" % (jf, e))
        problems, stats = audit(entries)
        print("体检 %d 个条目：%d 个文本页，超容量 %d，奇数页条目 %d，欠满页 %d"
              % (len(entries), stats["pages"], stats["over"], stats["odd"], stats["thin"]))
        for p in problems:
            print("  " + p)
        return 1 if problems else 0

    print("正在扫描 %d 个帕秋莉条目 JSON 文件..." % len(json_files))
    modified_count = 0
    for jf in sorted(json_files):
        did_modify = process_entry_file(
            jf,
            max_lines_override=args.max_lines,
            max_chars_override=args.max_chars,
            remove_hints=not args.keep_hints,
            dry_run=args.dry_run,
            verbose=args.verbose
        )
        if did_modify:
            modified_count += 1
            status = "[演练需分页]" if args.dry_run else "[已自动分页]"
            rel = jf.relative_to(Path.cwd()) if jf.is_relative_to(Path.cwd()) else jf
            print(" %s %s" % (status, rel))

    action = "需重新分页" if args.dry_run else "已完成分页保存"
    print("\n处理完成！共 %d 个条目，其中 %d 个条目%s。" % (len(json_files), modified_count, action))
    return 0


if __name__ == "__main__":
    sys.exit(main())
