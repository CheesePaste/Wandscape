#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""paginate_patchouli_json.py — 帕秋莉手册（Patchouli）JSON 条目自动分页工具。

功能定位：
    专门针对 patchouli_books/**/entries/**/*.json 条目文件进行内容排版自检与自动分页。
    当单页文本字数或排版视觉行数超标（字多了）时，智能在段落 $(br2)、换行 $(br)、
    列表 $(li) 或用户显式提示（如“（展示图片，下一页）”、“（放图，翻页）”）处切分，
    并自动闭合与恢复样式栈标签（$(bold)、$(italic) 等），杜绝 Patchouli 样式栈异常与截断。

用法：
    python paginate_patchouli_json.py                          # 扫描并处理当前手册所有条目
    python paginate_patchouli_json.py --dry-run                # 演练模式：仅检测并打印分页计划，不改动文件
    python paginate_patchouli_json.py <file_or_dir>            # 处理指定 JSON 文件或目录
    python paginate_patchouli_json.py --max-lines 16           # 自定义单页最大行数（默认无标题 18 行，有标题/首页 14 行）
    python paginate_patchouli_json.py --max-chars 180          # 自定义单页中文字数上限（默认 180，英文按 2.0 倍自动换算）
    python paginate_patchouli_json.py --keep-hints             # 保留“（展示图片，下一页）”等提示文字不作剔除
"""

import argparse
import glob
import json
import math
import os
import re
import sys
from pathlib import Path

# 确保在 Windows 控制台输出 UTF-8 字符时不出现乱码
if hasattr(sys.stdout, "reconfigure"):
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

# 默认扫描目录
DEFAULT_SEARCH_PATH = "src/main/resources/assets/wandscape/patchouli_books/guide"

SECTION_HEADER_RE = re.compile(
    r"^(?:(?:玩法|通用功能)?导航|其他|基础操作|属性|行为|推荐阅读顺序|"
    r"gameplay\s*navigation|system\s*features(?:\s*navigation)?|other|basic\s*operations|attributes|behavior|recommended\s*reading(?:\s*order)?)"
    r"[:：]?$",
    re.IGNORECASE
)

SECTION_START_RE = re.compile(
    r"^(?:(?:玩法|通用功能)?导航|其他|基础操作|属性|行为|推荐阅读顺序|"
    r"gameplay\s*navigation|system\s*features(?:\s*navigation)?|other|basic\s*operations|attributes|behavior|recommended\s*reading(?:\s*order)?)"
    r"[:：]",
    re.IGNORECASE
)

TAG_RE = re.compile(r"\$\(([^)]*)\)")
PAGE_BREAK_HINT_RE = re.compile(
    r"[（(]\s*(?:展示图片[，,]?\s*|放图[，,]?\s*|show\s*image[，,]?\s*)?(?:下一页|翻页|下页|另起一页|next\s*page|page\s*break)\s*[）)]",
    re.IGNORECASE
)
STYLE_PUSH_CMDS = {"bold", "italic", "italics", "strike", "underline", "obf", "k"}
RESET_CMDS = {"", "reset", "clear", "nocolor"}

CHARS_PER_LINE_CJK = 13.0
CHARS_PER_LINE_ASCII = 24.0
DEFAULT_MAX_LINES_NO_TITLE = 18
DEFAULT_MAX_LINES_WITH_TITLE = 14
DEFAULT_MAX_CHARS_CJK = 180
DEFAULT_MAX_CHARS_LATIN = 360

def strip_formatting(text: str) -> str:
    return TAG_RE.sub("", text)

def estimate_visual_width(plain_text: str) -> float:
    width = 0.0
    for ch in plain_text:
        if ord(ch) > 0x2E80:
            width += 1.0
        else:
            width += (CHARS_PER_LINE_CJK / CHARS_PER_LINE_ASCII)
    return width

def estimate_rendered_lines(text: str) -> int:
    if not text.strip():
        return 0
    paragraphs = re.split(r"\$\((?:br2|2br|p)\)", text)
    total_lines = 0
    for p_idx, para in enumerate(paragraphs):
        if p_idx > 0:
            total_lines += 1
        lines = re.split(r"\$\(br\)", para)
        for line in lines:
            list_items = re.split(r"\$\(li\d*\)", line)
            for l_idx, item in enumerate(list_items):
                plain = strip_formatting(item).strip()
                if not plain:
                    if l_idx > 0:
                        total_lines += 1
                    continue
                w = estimate_visual_width(plain)
                lines_for_item = max(1, math.ceil(w / CHARS_PER_LINE_CJK))
                total_lines += lines_for_item
    return total_lines

def is_cjk_dominant(text: str) -> bool:
    plain = strip_formatting(text)
    if not plain:
        return True
    cjk_count = sum(1 for ch in plain if ord(ch) > 0x2E80)
    return cjk_count > (len(plain) * 0.2)

def extract_style_state(text: str):
    active_styles = set()
    for match in TAG_RE.finditer(text):
        cmd = match.group(1).strip()
        if cmd in RESET_CMDS:
            active_styles.clear()
        elif cmd in STYLE_PUSH_CMDS:
            active_styles.add(cmd)
        elif cmd.startswith("#"):
            active_styles.add(cmd)
    return active_styles

def is_section_heading(text: str) -> bool:
    plain = strip_formatting(text).strip()
    if SECTION_HEADER_RE.match(plain):
        return True
    if SECTION_START_RE.match(plain):
        return True
    if (plain.endswith(":") or plain.endswith("：")) and len(plain) <= 15:
        return True
    return False

def clean_page_boundaries(page_str: str) -> str:
    pat_lead = re.compile(r"^(\s*\$\((?:br2|2br|br|p)\)\s*)+")
    pat_trail = re.compile(r"(\s*\$\((?:br2|2br|p)\)\s*)+$")
    s = page_str.strip()
    s = pat_lead.sub("", s).strip()
    s = pat_trail.sub("", s).strip()
    return s

def split_text_into_pages(text: str, max_lines: int, max_chars: int, remove_hints: bool = True):
    hint_matches = list(PAGE_BREAK_HINT_RE.finditer(text))
    if hint_matches:
        parts = []
        last_idx = 0
        for m in hint_matches:
            before = text[last_idx:m.start()].strip()
            before = clean_page_boundaries(before)
            if before:
                parts.append(before)
            last_idx = m.end()
            rem = text[last_idx:]
            trail_break = re.match(r"^(\s*\$\((?:br2|2br|br|p)\)\s*)+", rem)
            if trail_break:
                last_idx += trail_break.end()

        tail = text[last_idx:].strip()
        tail = clean_page_boundaries(tail)
        if tail:
            parts.append(tail)

        result_pages = []
        for part in parts:
            result_pages.extend(split_text_into_pages(part, max_lines, max_chars, remove_hints=False))
        return [clean_page_boundaries(p) for p in result_pages if p.strip()]

    plain = strip_formatting(text)
    cur_lines = estimate_rendered_lines(text)
    if cur_lines <= max_lines and len(plain) <= max_chars:
        return [clean_page_boundaries(text)]

    delim = "$(br2)"
    if "$(br2)" in text:
        delim = "$(br2)"
        sub_paras = text.split("$(br2)")
    elif "$(2br)" in text:
        delim = "$(2br)"
        sub_paras = text.split("$(2br)")
    elif "$(br)" in text:
        delim = "$(br)"
        sub_paras = text.split("$(br)")
    else:
        delim = ""
        sub_paras = re.split(r"(?<=[。！？!?])\s*", text)

    chunks = []
    current_chunk = []

    def current_chunk_stats():
        joined = (delim if delim else "").join(current_chunk)
        return estimate_rendered_lines(joined), len(strip_formatting(joined))

    for p_idx, p in enumerate(sub_paras):
        p_clean = clean_page_boundaries(p)
        if not p_clean:
            continue

        lines_before, _ = current_chunk_stats()
        # 遇到章节标题且本页已有充分内容、且后续内容足够丰富时切分
        if current_chunk and lines_before >= 6 and is_section_heading(p_clean):
            remaining_paras = [clean_page_boundaries(rp) for rp in sub_paras[p_idx:] if clean_page_boundaries(rp)]
            rem_joined = (delim if delim else "").join(remaining_paras)
            rem_lines = estimate_rendered_lines(rem_joined)
            rem_chars = len(strip_formatting(rem_joined))
            if rem_lines > 4 and rem_chars > 40:
                chunks.append((delim if delim else "").join(current_chunk))
                current_chunk = [p_clean]
                continue

        current_chunk.append(p_clean)
        lines, chars = current_chunk_stats()

        if lines > max_lines or chars > max_chars:
            # 孤行/残页保护：如果剩下的所有段落总共只有 <= 3 行或 <= 35 字，不值得单独开一页，合并到当前页
            remaining_paras = [clean_page_boundaries(rp) for rp in sub_paras[p_idx + 1:] if clean_page_boundaries(rp)]
            rem_joined = (delim if delim else "").join(remaining_paras)
            rem_lines = estimate_rendered_lines(rem_joined)
            rem_chars = len(strip_formatting(rem_joined))

            if rem_lines <= 3 and rem_chars <= 40:
                # 剩余极少，不切分，直接把剩余的所有段落全放进当前 chunk
                for rp in remaining_paras:
                    current_chunk.append(rp)
                break

            if len(current_chunk) > 1:
                current_chunk.pop()
                if current_chunk:
                    chunks.append((delim if delim else "").join(current_chunk))
                current_chunk = [p_clean]
            else:
                chunks.append((delim if delim else "").join(current_chunk))
                current_chunk = []

    if current_chunk:
        chunks.append((delim if delim else "").join(current_chunk))

    final_pages = []
    carry_styles = set()
    for idx, page_content in enumerate(chunks):
        page_str = clean_page_boundaries(page_content)
        if carry_styles:
            restore_prefix = "".join(f"$({s})" for s in sorted(carry_styles))
            page_str = restore_prefix + page_str

        active_now = extract_style_state(page_str)
        if active_now and idx < len(chunks) - 1:
            page_str += "$()"
            carry_styles = active_now
        else:
            carry_styles.clear()

        final_pages.append(clean_page_boundaries(page_str))

    return final_pages if final_pages else [clean_page_boundaries(text)]

def process_entry_file(file_path: Path, max_lines_override=None, max_chars_override=None,
                       remove_hints=True, dry_run=False, verbose=False) -> bool:
    """处理单个帕秋莉条目 JSON 文件。如果有页面被拆分，返回 True。"""
    try:
        data = json.loads(file_path.read_text(encoding="utf-8"))
    except Exception as e:
        print(f" [跳过] 无法读取 JSON 文件 {file_path}: {e}")
        return False

    pages = data.get("pages")
    if not pages or not isinstance(pages, list):
        return False

    new_pages = []
    file_modified = False

    for page_idx, page in enumerate(pages):
        page_type = page.get("type", "")
        if page_type != "patchouli:text" or "text" not in page:
            new_pages.append(page)
            continue

        raw_text = page.get("text", "")
        has_title = "title" in page
        is_first = (page_idx == 0)

        is_cjk = is_cjk_dominant(raw_text)
        if max_chars_override:
            max_chars = max_chars_override if is_cjk else int(max_chars_override * 2.0)
        else:
            max_chars = DEFAULT_MAX_CHARS_CJK if is_cjk else DEFAULT_MAX_CHARS_LATIN

        if max_lines_override:
            max_lines = max_lines_override
        else:
            max_lines = DEFAULT_MAX_LINES_WITH_TITLE if (has_title or is_first) else DEFAULT_MAX_LINES_NO_TITLE

        split_results = split_text_into_pages(raw_text, max_lines, max_chars, remove_hints=remove_hints)

        if len(split_results) <= 1:
            new_pages.append(page)
        else:
            file_modified = True
            if verbose or dry_run:
                print(f" -> 文件 {file_path.name} 第 {page_idx + 1} 页超长，切分为 {len(split_results)} 页:")
                for s_i, sp in enumerate(split_results):
                    print(f"    [子页 {s_i + 1}] 估算行数: {estimate_rendered_lines(sp)}, 字数: {len(strip_formatting(sp))}")

            # 首页保留原页面的所有字段（包括 title）
            first_page = dict(page)
            first_page["text"] = split_results[0]
            new_pages.append(first_page)

            # 后续承接页（不带 title，提供最大纯净视口）
            for next_text in split_results[1:]:
                cont_page = {"type": "patchouli:text", "text": next_text}
                new_pages.append(cont_page)

    if file_modified:
        data["pages"] = new_pages
        if not dry_run:
            file_path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        return True

    return False


def main():
    parser = argparse.ArgumentParser(description="帕秋莉手册（Patchouli）JSON 条目自动分页工具")
    parser.add_argument("target", nargs="?", default=DEFAULT_SEARCH_PATH,
                        help="目标文件路径、目录或 glob 通配符（默认扫描 guide/**/entries）")
    parser.add_argument("--max-lines", type=int, default=None,
                        help="单页最大行数阈值（默认无标题 18 行，有标题/首页 14 行）")
    parser.add_argument("--max-chars", type=int, default=None,
                        help="单页最大中文字数阈值（默认 180，英文按 2.0 倍折算）")
    parser.add_argument("--dry-run", action="store_true",
                        help="演练模式：仅打印分页结果，不写回文件")
    parser.add_argument("--verbose", "-v", action="store_true",
                        help="输出详细检测与行数估算日志")
    parser.add_argument("--keep-hints", action="store_true",
                        help="不清理（展示图片，下一页）等提示文字")

    args = parser.parse_args()
    root_path = Path(args.target)

    json_files = []
    if root_path.is_file() and root_path.suffix == ".json":
        json_files.append(root_path)
    elif root_path.is_dir():
        entries_dir = root_path / "entries" if (root_path / "entries").exists() else root_path
        json_files.extend(entries_dir.rglob("*.json"))
    else:
        for match in glob.glob(args.target, recursive=True):
            p = Path(match)
            if p.is_file() and p.suffix == ".json":
                json_files.append(p)

    json_files = [f for f in json_files if "categories" not in f.parts]

    if not json_files:
        print(f"未找到可处理的条目 JSON 文件：{args.target}")
        return 0

    print(f"正在扫描 {len(json_files)} 个帕秋莉条目 JSON 文件...")
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
            print(f" {status} {rel}")

    action = "需重新分页" if args.dry_run else "已完成分页保存"
    print(f"\n处理完成！共 {len(json_files)} 个条目，其中 {modified_count} 个条目{action}。")
    return 0


if __name__ == "__main__":
    sys.exit(main())

