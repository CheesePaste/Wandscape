package com.wsteam.wandscape.foundation.ui.guidebook;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.log.LogCategory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 手册的运行期结构清单（{@code assets/wandscape/guidebook/runtime/<locale>.json}，由
 * {@code gen_patchouli.py} 生成）。
 *
 * <p>它存在的理由：手册的内容是 md 单源，但**结构**（有哪些分类、分类下有哪些条目、条目叫什么名字、
 * 正文里的《…》指向哪里）原本只活在生成器里。帕秋莉那侧靠手写枚举、兜底那侧干脆没有分类，
 * 于是同一本手册在两处渲染出两副样子。现在结构由生成器发一份清单，两侧都读它。
 *
 * <p><b>不含任何客户端引用</b>（语言目录由调用方传入），所以服务端也能读它——
 * 例如 {@code /wandscape guide} 的补全列表。清单缺失/损坏/版本不符一律降级并出声，绝不抛。
 */
public final class GuideManifest {

    /** 分类页的页 id 前缀：{@code category:<分类 id>}。 */
    public static final String CATEGORY_PREFIX = "category:";
    /** 着陆页的规范页 id。空串在历史栈里存不住（会被当无效目标丢掉），所以给个实名。 */
    public static final String ROOT_PAGE = "index";

    private static final String ROOT = "assets/wandscape/guidebook/runtime/";
    private static final int VERSION = 1;
    private static final String FALLBACK_LOCALE = "zh_cn";
    private static final String TAG = "GuideManifest";

    private static final Map<String, GuideManifest> CACHE = new HashMap<>();

    /** 一个分类：id、显示名、描述（原样 md）与排序。 */
    public record Category(String id, String name, String desc, String icon, int sortnum) {}

    /** 一个条目位：md 文档 id、所属分类、条目显示名与排序。同一篇 md 可以登记多条。 */
    public record Entry(String doc, String category, String name, String icon, int sortnum) {}

    private final String locale;
    private final String landingTitle;
    private final String landingText;
    private final List<Category> categories;
    private final List<Entry> entries;
    private final Map<String, Entry> firstEntryByDoc = new LinkedHashMap<>();
    private final Map<String, List<Entry>> entriesByCategory = new LinkedHashMap<>();
    /** 词尾 {@code _guide} 去掉后的通用别名（{@code warehouse} → {@code warehouse_guide}）。 */
    private final Map<String, String> aliasToDoc = new LinkedHashMap<>();
    private final Map<String, String> titles;

    private GuideManifest(String locale, String landingTitle, String landingText,
                          List<Category> categories, List<Entry> entries,
                          Map<String, String> titles) {
        this.locale = locale;
        this.landingTitle = landingTitle;
        this.landingText = landingText;
        this.categories = List.copyOf(categories);
        this.entries = List.copyOf(entries);
        this.titles = Map.copyOf(titles);
        for (Entry e : this.entries) {
            // 同一篇 md 登记多次时，先出现的那条是它的规范归属（与生成器同一规则）
            firstEntryByDoc.putIfAbsent(e.doc(), e);
            entriesByCategory.computeIfAbsent(e.category(), k -> new ArrayList<>()).add(e);
            if (e.doc().endsWith("_guide")) {
                aliasToDoc.putIfAbsent(
                        e.doc().substring(0, e.doc().length() - "_guide".length()), e.doc());
            }
        }
    }

    // ── 载入 ────────────────────────────────────────────────────────────────

    /** 按语言目录取清单（{@code zh_cn} / {@code en}）；读不到返回 null。结果按语言缓存。 */
    @javax.annotation.Nullable
    public static GuideManifest forLocale(String localeDir) {
        String dir = (localeDir == null || localeDir.isBlank()) ? FALLBACK_LOCALE : localeDir;
        synchronized (CACHE) {
            if (CACHE.containsKey(dir)) {
                return CACHE.get(dir);
            }
        }
        GuideManifest loaded = read(dir);
        if (loaded == null && !FALLBACK_LOCALE.equals(dir)) {
            // 语言目录缺清单时退到默认语言：名字是中文也好过没有结构
            loaded = read(FALLBACK_LOCALE);
        }
        synchronized (CACHE) {
            CACHE.put(dir, loaded);
        }
        return loaded;
    }

    /**
     * 只关心页 id 的调用方（如命令补全）用它：页 id 与语言无关，
     * 于是不必把「当前客户端语言」这种客户端概念带进服务端代码。
     */
    @javax.annotation.Nullable
    public static GuideManifest anyLocale() {
        GuideManifest m = forLocale(FALLBACK_LOCALE);
        return m != null ? m : forLocale("en");
    }

    @javax.annotation.Nullable
    private static GuideManifest read(String locale) {
        String path = ROOT + locale + ".json";
        try (InputStream is = GuideManifest.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                Log.warnOnce(LogCategory.UI, "manifest-missing-" + locale,
                        "[指南] 读不到运行期清单 {}，兜底手册退化为无分类的旧行为（重跑 gen_patchouli.py 生成它）",
                        path);
                return null;
            }
            JsonObject root = JsonParser.parseString(
                    new String(is.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
            int version = root.has("manifest_version") ? root.get("manifest_version").getAsInt() : -1;
            if (version != VERSION) {
                // 不做「缺字段补默认」：格式变了就明着降级，不猜
                Log.warnOnce(LogCategory.UI, "manifest-version-" + locale,
                        "[指南] 运行期清单 {} 版本为 {}，本版代码只认 {}，已忽略",
                        path, version, VERSION);
                return null;
            }
            return parse(locale, root);
        } catch (Exception e) {
            Log.warn(LogCategory.UI, "[指南] 运行期清单 {} 解析失败：{}", path, e.toString());
            return null;
        }
    }

    private static GuideManifest parse(String locale, JsonObject root) {
        JsonObject landing = root.has("landing") ? root.getAsJsonObject("landing") : new JsonObject();
        String title = landing.has("title") ? landing.get("title").getAsString() : "";
        String text = landing.has("text") ? landing.get("text").getAsString() : "";

        List<Category> cats = new ArrayList<>();
        for (JsonElement el : array(root, "categories")) {
            JsonObject o = el.getAsJsonObject();
            cats.add(new Category(str(o, "id"), str(o, "name"), str(o, "desc"),
                    str(o, "icon"), num(o, "sortnum")));
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonElement el : array(root, "entries")) {
            JsonObject o = el.getAsJsonObject();
            entries.add(new Entry(str(o, "doc"), str(o, "category"), str(o, "name"),
                    str(o, "icon"), num(o, "sortnum")));
        }
        Map<String, String> titles = new LinkedHashMap<>();
        if (root.has("titles")) {
            for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("titles").entrySet()) {
                titles.put(e.getKey(), e.getValue().getAsString());
            }
        }
        return new GuideManifest(locale, title, text, cats, entries, titles);
    }

    private static JsonArray array(JsonObject o, String key) {
        return o.has(key) ? o.getAsJsonArray(key) : new JsonArray();
    }

    private static String str(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static int num(JsonObject o, String key) {
        return o.has(key) ? o.get(key).getAsInt() : 0;
    }

    // ── 查询 ────────────────────────────────────────────────────────────────

    public String locale() {
        return locale;
    }

    public String landingTitle() {
        return landingTitle;
    }

    public String landingText() {
        return landingText;
    }

    public List<Category> categories() {
        List<Category> sorted = new ArrayList<>(categories);
        sorted.sort(java.util.Comparator.comparingInt(Category::sortnum));
        return sorted;
    }

    public List<Entry> entries() {
        return entries;
    }

    @javax.annotation.Nullable
    public Category category(String id) {
        for (Category c : categories) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        return null;
    }

    /** 某个分类下的条目，按登记顺序（分类内 sortnum 递增）。 */
    public List<Entry> entriesIn(String categoryId) {
        return entriesByCategory.getOrDefault(categoryId, List.of());
    }

    /** 某篇 md 的规范条目（登记多条时取第一条），不存在返回 null。 */
    @javax.annotation.Nullable
    public Entry firstEntryFor(String doc) {
        return firstEntryByDoc.get(doc);
    }

    public boolean hasDoc(String doc) {
        return firstEntryByDoc.containsKey(doc);
    }

    /** 《标题》→ 页 id（条目 id 或 {@code category:<分类 id>}）。查不到返回 null。 */
    @javax.annotation.Nullable
    public String titleTarget(String title) {
        return titles.get(title);
    }

    /**
     * 把玩家/正文给的任意页名归一化成规范页 id：
     * 空串与 {@code index} → {@link #ROOT_PAGE}；{@code category:<id>}；条目 id；
     * 词尾 {@code _guide} 的通用别名；{@code assets/…/x.md} 形式只取文件名。
     * 认不出来返回 null（调用方据此走 404，而不是拿玩家输入去拼资源路径）。
     */
    @javax.annotation.Nullable
    public String resolve(String raw) {
        if (raw == null) {
            return ROOT_PAGE;
        }
        String t = raw.trim();
        for (String prefix : new String[]{"guidebook:", "guide:"}) {
            if (t.startsWith(prefix)) {
                t = t.substring(prefix.length()).trim();
                break;
            }
        }
        if (t.isEmpty() || t.equals(ROOT_PAGE)) {
            return ROOT_PAGE;
        }
        if (t.startsWith(CATEGORY_PREFIX)) {
            String id = t.substring(CATEGORY_PREFIX.length()).trim();
            return category(id) != null ? CATEGORY_PREFIX + id : null;
        }
        if (t.startsWith("assets/")) {
            // 完整资源路径：只取文件名，且必须命中清单——绝不让调用方的字符串决定读哪个资源
            int slash = t.lastIndexOf('/');
            t = slash >= 0 ? t.substring(slash + 1) : t;
        }
        if (t.endsWith(".md")) {
            t = t.substring(0, t.length() - 3);
        }
        if (hasDoc(t)) {
            return t;
        }
        if (category(t) != null) {
            return CATEGORY_PREFIX + t;
        }
        return aliasToDoc.get(t.endsWith("_guide") ? t.substring(0, t.length() - "_guide".length()) : t);
    }
}
