package com.wsteam.wandscape.shared.client.bubble;

import com.wsteam.wandscape.npc.entity.WandscapeNpc;
import com.wsteam.wandscape.shared.data.Emotion;
import com.wsteam.wandscape.shared.data.VisitMemory;
import com.wsteam.wandscape.shared.ui.I18n;
import com.wsteam.wandscape.tourist.entity.TouristEntity;
import com.wsteam.wandscape.tourist.internal.TouristState;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;

/**
 * Text pools for NPC speech bubbles, organized by emotion × state for tourists
 * and by status for NPC wizards. Provides contextual dialogue with optional
 * building name references.
 */
public final class AmbientTextPools {

    private static final Random RNG = new Random();
    private static final String BUILDING_PLACEHOLDER = "{building}";

    private AmbientTextPools() {}

    // ── Tourist text pools ──────────────────────────────────────────

    /** Generic texts (no building reference). */
    private static final Map<Emotion, Map<TouristState, List<String>>> GENERIC_TEXTS = buildGenericTexts();

    /** Texts with {building} placeholder. */
    private static final Map<Emotion, Map<TouristState, List<String>>> BUILDING_TEXTS = buildBuildingTexts();

    /** States that don't have building texts (sleeping, idle — just generic). */
    private static final Map<TouristState, List<String>> IDLE_TEXTS = Map.ofEntries(
            Map.entry(TouristState.IDLE, List.of("休息中", "站着发呆", "稍微歇会儿", "喘口气")),
            Map.entry(TouristState.SLEEPING, List.of("zzz…", "睡得真香", "好梦", "呼噜…"))
    );

    @Nullable
    public static Component getTouristText(LivingEntity entity) {
        if (!(entity instanceof TouristEntity tourist)) return null;

        TouristState state = tourist.getCurrentState();

        // IDLE and SLEEPING use fixed pools regardless of emotion
        if (state == TouristState.IDLE || state == TouristState.SLEEPING) {
            List<String> pool = IDLE_TEXTS.get(state);
            if (pool == null || pool.isEmpty()) return null;
            int idx = RNG.nextInt(pool.size());
            return bubble("bubble.wandscape.tourist.idle." + state.name().toLowerCase() + "." + idx,
                    pool.get(idx));
        }

        Emotion emotion = Emotion.fromBarRatio(minRatioPct(tourist));

        // 50% chance to use a building reference if we have recent visits
        List<VisitMemory> visits = tourist.getRecentVisits();
        if (!visits.isEmpty() && RNG.nextFloat() < 0.5f) {
            Picked picked = pick(BUILDING_TEXTS, emotion, state);
            if (picked != null) {
                VisitMemory mem = visits.get(RNG.nextInt(visits.size()));
                String buildingName = mem.buildingDisplayName();
                if (buildingName != null && !buildingName.isEmpty()) {
                    String key = "bubble.wandscape.tourist.building." + emotion.name().toLowerCase()
                            + "." + state.name().toLowerCase() + "." + picked.idx();
                    Component building = (mem.buildingTypeId() == null || mem.buildingTypeId().isEmpty())
                            ? Component.literal(buildingName)
                            : I18n.name("building.wandscape." + mem.buildingTypeId(), buildingName);
                    return bubble(key, picked.text(), building);
                }
            }
        }

        Picked generic = pick(GENERIC_TEXTS, emotion, state);
        if (generic == null) return null;
        return bubble("bubble.wandscape.tourist.generic." + emotion.name().toLowerCase()
                + "." + state.name().toLowerCase() + "." + generic.idx(), generic.text());
    }

    /** 三条需求条填充率的最小值（min-ratio×100），驱动闲逛气泡情绪。 */
    private static int minRatioPct(TouristEntity tourist) {
        int c = pct(tourist.getComfortSat(), tourist.getComfortNeed());
        int m = pct(tourist.getMagicSat(), tourist.getMagicNeed());
        int w = pct(tourist.getWonderSat(), tourist.getWonderNeed());
        return Math.min(Math.min(c, m), w);
    }

    private static int pct(int sat, int need) {
        return need <= 0 ? 0 : (int) Math.floor(sat * 100.0 / need);
    }

    /** One selected pool entry with its stable index (used to derive the lang key). */
    private record Picked(int idx, String text) {}

    @Nullable
    private static Picked pick(Map<Emotion, Map<TouristState, List<String>>> pool,
                               Emotion emotion, TouristState state) {
        Map<TouristState, List<String>> stateMap = pool.get(emotion);
        if (stateMap == null) return null;
        List<String> candidates = stateMap.get(state);
        if (candidates == null || candidates.isEmpty()) return null;
        int idx = RNG.nextInt(candidates.size());
        return new Picked(idx, candidates.get(idx));
    }

    /** Build a translatable bubble component; {@code {building}} placeholders map to {@code %s} args. */
    private static Component bubble(String key, String fallback, Object... args) {
        return Component.translatableWithFallback(key, fallback.replace(BUILDING_PLACEHOLDER, "%s"), args);
    }

    // ── NPC wizard text pools ───────────────────────────────────────

    private static final Map<String, List<String>> NPC_STATUS_TEXTS = new ConcurrentHashMap<>();

    static {
        NPC_STATUS_TEXTS.put("idle", List.of(
                "休息一下", "今天也挺忙的", "歇会儿", "站着发呆",
                "待会儿再干", "忙碌的一天啊", "嗯…想想下一步"
        ));
        NPC_STATUS_TEXTS.put("gathering", List.of(
                "加把劲", "材料还不少", "这是好东西", "收获不错",
                "再采一点", "今天的成果不错", "这片区域资源丰富"
        ));
        NPC_STATUS_TEXTS.put("transforming", List.of(
                "快完成了", "完美", "一砖一瓦", "结构稳固",
                "尺寸刚好", "接下来是这边…", "就差一点了"
        ));
        NPC_STATUS_TEXTS.put("moving", List.of(
                "该去工作了", "去那边看看", "还有活要干", "走起",
                "不能闲着", "下一站", "时间不等人"
        ));
        // "casting" is matched via opKind below, but also add fallback
        NPC_STATUS_TEXTS.put("casting", List.of(
                "魔力汇聚…", "就是现在！", "法术释放", "感受元素的力量",
                "能量充盈", "就是这种感觉", "集中…"
        ));
        NPC_STATUS_TEXTS.put("transform", List.of(
                "转化开始", "物质重组", "炼金术的奥妙", "变了变了"
        ));
        NPC_STATUS_TEXTS.put("block_interact", List.of(
                "这个方块…", "让我看看", "这就是目标", "找到了"
        ));
        NPC_STATUS_TEXTS.put("ritual", List.of(
                "仪式进行中", "古老的力量", "遵从契约", "元素共鸣",
                "法力在流动…"
        ));

        // Fallback for any unrecognized status
        NPC_STATUS_TEXTS.put("__fallback__", List.of(
                "这座魔法小镇真不错", "环境宜人", "继续努力", "日子一天天过",
                "希望一切顺利"
        ));
    }

    @Nullable
    public static Component getNpcText(LivingEntity entity) {
        if (!(entity instanceof WandscapeNpc npc)) return null;

        String opKind = npc.getOpKind();

        // Try matching by opKind prefix first (more specific)
        if (opKind != null && !opKind.isEmpty()) {
            String baseKey = null;
            if (opKind.startsWith("ritual:")) baseKey = "ritual";
            else if (opKind.startsWith("block_interact:")) baseKey = "block_interact";
            else if (opKind.startsWith("transform")) baseKey = "transform";
            if (baseKey != null) return pickNpcText(baseKey);
        }

        // Try matching by status key (WandscapeNpc returns stable keys, not zh text)
        String statusKey = npc.getStatusText();
        if (statusKey != null && !statusKey.isEmpty()) {
            switch (statusKey) {
                case "idle" -> { return pickNpcText("idle"); }
                case "gathering" -> { return pickNpcText("gathering"); }
                case "moving" -> { return pickNpcText("moving"); }
                case "transforming" -> { return pickNpcText("transforming"); }
                case "casting" -> { return pickNpcText("casting"); }
                default -> {}
            }
        }

        return pickNpcText("__fallback__");
    }

    private static Component pickNpcText(String poolKey) {
        List<String> pool = NPC_STATUS_TEXTS.get(poolKey);
        if (pool == null || pool.isEmpty()) pool = NPC_STATUS_TEXTS.get("__fallback__");
        if (pool == null || pool.isEmpty()) return null;
        int idx = RNG.nextInt(pool.size());
        return bubble("bubble.wandscape.npc." + poolKey + "." + idx, pool.get(idx));
    }

    // ── Hardcoded text tables ──────────────────────────────────────

    private static Map<Emotion, Map<TouristState, List<String>>> buildGenericTexts() {
        Map<Emotion, Map<TouristState, List<String>>> map = new ConcurrentHashMap<>();

        map.put(Emotion.DELIGHTED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "好期待进去看看！", "听说这里很棒！", "看起来就让人兴奋",
                        "这个建筑太棒了", "迫不及待想进去了"
                ),
                TouristState.EXPLORING, List.of(
                        "这里的风景太美了！", "真是个漂亮的地方", "魔法小镇的建设真不错",
                        "每一步都是风景", "空气清新，心情舒畅"
                ),
                TouristState.WANDERING, List.of(
                        "今天心情真好~", "阳光真舒服", "真希望每天都这样",
                        "生活真美好", "悠闲的时光最珍贵"
                )
        )));

        map.put(Emotion.PLEASED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "看起来不错的选择", "进去看看吧", "这家店看起来不错",
                        "去逛逛", "来都来了"
                ),
                TouristState.EXPLORING, List.of(
                        "魔法小镇的街道很整洁", "空气真好", "绿化做得不错",
                        "设计得很用心", "这座魔法小镇发展得挺好"
                ),
                TouristState.WANDERING, List.of(
                        "嗯…去哪里好呢", "稍微走走吧", "漫步一下",
                        "享受悠闲时光", "随心走走"
                )
        )));

        map.put(Emotion.SATISFIED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "就这家吧", "进去逛逛", "看起来还行",
                        "凑合看看吧", "试试这家"
                ),
                TouristState.EXPLORING, List.of(
                        "还可以", "一般般吧", "还算干净整洁",
                        "没什么大问题", "中规中矩"
                ),
                TouristState.WANDERING, List.of(
                        "随便走走", "不着急", "慢悠悠地逛",
                        "溜达溜达", "四处看看"
                )
        )));

        map.put(Emotion.NEUTRAL, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "去看看有什么", "随便看看", "打发下时间",
                        "路过看看", "来都来了"
                ),
                TouristState.EXPLORING, List.of(
                        "嗯，没什么特别的", "就这样吧", "普普通通",
                        "没什么好看的", "到处都一样"
                ),
                TouristState.WANDERING, List.of(
                        "走一走", "没什么事做", "闲逛一下",
                        "随便走走", "打发时间"
                )
        )));

        map.put(Emotion.DISAPPOINTED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "希望这家别太差", "唉，试试看吧", "不太抱期望",
                        "随便看看吧", "希望不要踩雷"
                ),
                TouristState.EXPLORING, List.of(
                        "不太有意思", "没什么好看的", "有点无聊",
                        "也就这样了", "浪费时间"
                ),
                TouristState.WANDERING, List.of(
                        "有点无聊", "想回去了", "没什么意思",
                        "不如早点回去", "唉……"
                )
        )));

        map.put(Emotion.UPSET, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "最好别让我失望！", "算了，看看吧", "哼，就看看",
                        "最后一次机会", "还能更差吗"
                ),
                TouristState.EXPLORING, List.of(
                        "什么破地方", "一点都不好", "真没意思",
                        "太失望了", "再也不来了"
                ),
                TouristState.WANDERING, List.of(
                        "烦死了", "不想逛了", "心情都被破坏了",
                        "糟透了今天", "想回家了"
                )
        )));

        return map;
    }

    private static Map<Emotion, Map<TouristState, List<String>>> buildBuildingTexts() {
        Map<Emotion, Map<TouristState, List<String>>> map = new ConcurrentHashMap<>();

        map.put(Emotion.DELIGHTED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "上次在{building}体验很好，看看这家！",
                        "自从去了{building}就爱上这里了",
                        "希望这家和{building}一样棒",
                        "{building}给我留下了深刻印象"
                ),
                TouristState.EXPLORING, List.of(
                        "比起{building}那边，这也不差！",
                        "从{building}过来，这边也很不错",
                        "{building}那边好看，这边也不赖"
                ),
                TouristState.WANDERING, List.of(
                        "在{building}玩得很开心，继续逛逛",
                        "刚从{building}出来，心情大好",
                        "回味着{building}的美好体验"
                )
        )));

        map.put(Emotion.PLEASED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "听说{building}不错，这个应该也行",
                        "上次去了{building}感觉挺好，再看看这家",
                        "跟{building}差不多的话就很满意了"
                ),
                TouristState.EXPLORING, List.of(
                        "从{building}出来走走，挺舒服",
                        "逛完{building}再来这边，心情不错",
                        "{building}那边逛完了，继续探索"
                ),
                TouristState.WANDERING, List.of(
                        "刚才在{building}收获不错",
                        "去过{building}了，到处转转",
                        "在{building}度过了愉快的时光"
                )
        )));

        map.put(Emotion.SATISFIED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "这家和{building}差不多，试试",
                        "之前去{building}还行，这家应该也凑合",
                        "跟{building}差不多档次吧"
                ),
                TouristState.EXPLORING, List.of(
                        "比{building}差不太多",
                        "和{building}那边差不多吧",
                        "逛完{building}过来，都差不多"
                ),
                TouristState.WANDERING, List.of(
                        "刚刚那家{building}还行",
                        "去过{building}了，四处溜达",
                        "在{building}出来走走消化一下"
                )
        )));

        map.put(Emotion.NEUTRAL, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "{building}都去了，这家也看看吧",
                        "顺便逛逛这家，跟{building}一样",
                        "从{building}过来，顺便看看"
                ),
                TouristState.EXPLORING, List.of(
                        "和{building}那边差不多",
                        "逛完{building}没什么特别感觉",
                        "跟{building}一样普普通通"
                ),
                TouristState.WANDERING, List.of(
                        "刚从{building}出来，散散步",
                        "去了趟{building}，随便走走",
                        "从{building}出来透透气"
                )
        )));

        map.put(Emotion.DISAPPOINTED, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "比{building}还差就不好了",
                        "{building}就挺失望了，这家…",
                        "希望比{building}强一点吧"
                ),
                TouristState.EXPLORING, List.of(
                        "还没有{building}那边有意思",
                        "跟{building}一样让人失望",
                        "{building}不行，这边也够呛"
                ),
                TouristState.WANDERING, List.of(
                        "连{building}都不怎么样",
                        "在{building}就不太开心",
                        "{building}让人失望，逛街心情都没了"
                )
        )));

        map.put(Emotion.UPSET, new ConcurrentHashMap<>(Map.of(
                TouristState.VISITING, List.of(
                        "希望比{building}好一点",
                        "{building}已经够差了",
                        "别跟{building}一样就行"
                ),
                TouristState.EXPLORING, List.of(
                        "跟{building}一样差劲",
                        "比{building}还差，服了",
                        "从{building}出来心情就不好"
                ),
                TouristState.WANDERING, List.of(
                        "再也不去{building}那种地方了",
                        "在{building}受够了",
                        "{building}的体验太糟糕了"
                )
        )));

        return map;
    }
}
