# imgui-java 中文字体 glyph ranges 截断导致中文显示为 ????? (调查记录)

> 本文件用于向 [SpaiR/imgui-java](https://github.com/SpaiR/imgui-java) 提交 issue。
> 记录了从"中文显示为 ?????"到定位根因的完整调查过程、JUnit 实测数据与修复方案。

## 环境

| 项 | 值 |
|----|----|
| imgui-java | `io.github.spair:imgui-java-binding:1.86.10` / `imgui-java-lwjgl3:1.86.10` / `imgui-java-natives-windows:1.86.10` |
| Java | OpenJDK 21.0.7 (Microsoft build) |
| 平台 | Windows 11 24H2, x86_64 |
| 宿主 | Minecraft NeoForge 1.21.1 客户端 (mod 内嵌 ImGui dev tooling) |
| 字体 | SimHei (`C:\Windows\Fonts\simhei.ttf`, TrueType glyf, 合法 cmap + CJK 字形) |

## 现象

ImGui 面板中的中文字符串（如 `"道路制作工坊"`、`"曲线编辑"`、`"添加节点"`）全部显示为
`?????`。英文、数字、ASCII 正常。已确认：

- 字体文件本身合法（TTF sfnt 结构、`glyf` + `cmap` 表完整，包含 CJK 字形）
- 加载路径成功（`addFontFromFileTTF` 返回非 0 native 指针，无异常）
- 仅中文字符（U+4E00–U+9FFF 区间）缺失，其余字符正常
- 现象是**持续存在**的（不是偶发、不随 GC 变化）

## 调查过程

### 假设 1: GC 移动了 glyph ranges 数组（悬空指针）

`ImFontConfig.setGlyphRanges(short[])` 的 JNI 实现只是把数组首地址存进 C 结构体
（`IM_FONT_CONFIG->GlyphRanges = &glyphRanges[0]`），`ImFontAtlas::AddFont` 在
**build() 时才读取该指针**。若数组被 GC 回收/移动，build() 会读到悬空内存 → CJK 字形丢失。

**测试**：写 JUnit（ImGui 字体 build 是纯 CPU，无 GL 依赖），build() 后触发多次
`System.gc()` 再探测字形。

**结果**：**排除**。内置 ranges + GC 前探测就失败（`beforeGC=false`），与 GC 无关。

### 假设 2: glyph ranges 数组在 JNI 返回时被截断 ⭐ 根因

`getGlyphRangesChineseSimplifiedCommon()` 的 JNI 实现用 `RETURN_GLYPH_2_SHORT` 宏
把 C++ `ImWchar16`（无符号 16 位）逐对拷进 Java `short[]`（有符号 16 位）。

CJK 汉字 U+4E00 起，全部超过 Java `short` 最大值 `0x7FFF`。无符号 16 位
`0x4E00` 在 Java 有符号 `short` 中按位模式是负数 `-28672`，但 JNI 宏按"值"
截断/转换时无法保留位模式，导致 **0x4E00 起的所有 CJK 区间在返回数组中丢失**。

数组中实际只保留了 Basic Latin、Latin Supplement、General Punctuation、
CJK Symbols/Hiragana/Katakana 等 ≤ 0x7FFF 的区间；CJK Ideographs
（0x4E00–0x9FAF）完全缺失。

**测试**：手工构造 ranges 数组（显式 `(short)` 强转保留无符号位模式），
build() 后探测"道 路 编 辑 制 作 阵 列"8 个汉字。

**结果**：**确认根因**。手工构造后 `beforeGC=true`，GC 压力后 `afterGC=true`。

### JUnit 实测数据

```
[GlyphTest] builtInRanges   beforeGC=false   ← 内置 ranges: build 后立即失败
[GlyphTest] builtInRanges   afterGC =false   ← GC 后仍失败（排除 GC 悬空指针）
[GlyphTest] explicitRanges  beforeGC=true    ← 手工 (short) 强转 ranges: 立即成功
[GlyphTest] explicitRanges  afterGC =true    ← GC 压力后依然成功
```

探测方式：`font.findGlyphNoFallback(codepoint).ptr != 0`。

## 根因总结

`ImGui.getIO().getFonts().getGlyphRangesChineseSimplifiedCommon()`（以及
`getGlyphRangesChineseFull()` 等）在 imgui-java 1.86.10 中**返回的数组被截断**：
JNI 侧把无符号 `ImWchar16` 区间按有符号 Java `short` 处理，所有码点 > 0x7FFF
的区间（即整个 CJK Unified Ideographs 0x4E00–0x9FAF）丢失。

无论使用什么中文字体、无论 addFont 是否成功、无论 GC 与否，atlas 里都不会有
汉字字形 → 中文必然显示 `?????`。这与
[SpaiR/imgui-java #70](https://github.com/SpaiR/imgui-java/issues/70)
描述的现象一致（该 issue 关注的是"数组被截断"，与本调查结论吻合）。

## 修复方案（客户端侧 workaround，已验证）

不调用损坏的内置方法，手工构造 ranges 数组，每个值显式 `(short)` 强转保留
无符号位模式（这正是 C++ `ImWchar16` 期望的）：

```java
short[] cjkRanges = new short[]{
        (short) 0x0020, (short) 0x00FF, // Basic Latin + Latin Supplement
        (short) 0x2000, (short) 0x206F, // General Punctuation
        (short) 0x3000, (short) 0x30FF, // CJK Symbols and Punctuations, Hiragana, Katakana
        (short) 0x31F0, (short) 0x31FF, // Katakana Phonetic Extensions
        (short) 0xFF00, (short) 0xFFEF, // Half-width characters
        (short) 0xFFFD, (short) 0xFFFD, // Invalid
        (short) 0x4E00, (short) 0x9FFF, // CJK Unified Ideographs (full range)
        0,
};

ImFontConfig cfg = new ImFontConfig();
cfg.setOversampleH(2);
cfg.setOversampleV(2);
cfg.setGlyphRanges(cjkRanges); // 持有数组引用,防 GC
ImFont font = ImGui.getIO().getFonts().addFontFromFileTTF(SIMHEI, 17.0f, cfg);
ImGui.getIO().getFonts().build();
```

关键点：

1. **必须显式 `(short)` 强转** —— 否则 `0x4E00` 字面量超过 `short` 范围无法编译，
   `0x9FFF` 等直接赋值会编译错误；强转后保留的是底层 16 位无符号位模式。
2. **用 `setGlyphRanges()` 而非 `addFontFromFileTTF(..., ranges)` 第 4 参** ——
   前者让 `ImFontConfig` 的 Java 字段持有数组引用，防止 GC 回收（后者数组只在
   调用栈存活，build() 时可能已回收，存在悬空指针隐患）。

## 建议的上游修复方向

1. `ImFontAtlas.java` 中 `getGlyphRangesChineseFull()` / `getGlyphRangesChineseSimplifiedCommon()`
   等方法的 JNI `RETURN_GLYPH_2_SHORT` 宏应按**无符号**位模式拷贝，或改用
   `char[]`/`int[]` 传递 ImWchar16。
2. 或：文档中明确警告这些方法在 Java `short` 下有截断问题，推荐手工构造。
3. 另建议：`addFontFromFileTTF(..., short[] glyphRanges)` 第 4 参的形式也应持有
   数组引用（或提供 `ImFontConfig.setGlyphRanges` 的替代），避免 GC 悬空指针。

## 相关文件

- `src/main/java/com/wsteam/wandscape/imgui/ImGuiManager.java` — 修复后的字体加载
- `src/test/java/com/wsteam/wandscape/imgui/ImGuiFontGlyphTest.java` — 守护测试

## 验证

- `./gradlew test` 全绿（含 `ImGuiFontGlyphTest`）
- `./gradlew compileJava` 通过
- 游戏中中文面板恢复正常
