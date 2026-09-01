package com.wsteam.wandscape.foundation.util;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Character name pools shared by mages ({@code WandscapeNpc}) and tourists
 * ({@code TouristEntity}). Three naming styles, switchable per colony in the
 * town hall UI (default: {@link NameStyle#FANTASY}); the rule only affects
 * names generated after the switch — stored names never change.
 *
 * <ul>
 *   <li>{@link NameStyle#FANTASY} — Latin/Roman single names (Marcus, Aurelia), no surname.
 *       Stored as {@code wandscape.character_name.fantasy.<i>}.</li>
 *   <li>{@link NameStyle#CHINESE} — surname + given name (王明). Stored as
 *       {@code wandscape.character_name.zh.s<i>.g<j>}; parts resolve to
 *       {@code zhs.<i>}/{@code zhg.<j>} lang keys so each client renders in its
 *       own language (王明 / Wang Ming).</li>
 *   <li>{@link NameStyle#ENGLISH} — given + surname (John Smith). Stored as
 *       {@code wandscape.character_name.en.f<i>.l<j>}; parts resolve to
 *       {@code enf.<i>}/{@code enl.<j>} lang keys (约翰·史密斯 / John Smith).</li>
 * </ul>
 *
 * <p>Legacy saves use the old flat key {@code wandscape.character_name.<i>}
 * (44 bilingual names) — those entries and this fallback stay forever.
 * Names are stored server-side as keys; the client renders them in its own
 * language. Language-adaptive separators come from the lang file
 * ({@code sep_zh}: "" in zh / " " in en; {@code sep_en}: "·" in zh / " " in en),
 * so the client resolves the layout even when the name was built server-side.
 */
public final class CharacterNames {

    public static final String KEY_PREFIX = "wandscape.character_name.";

    private static final Pattern KEY_ZH_COMPOSITE =
            Pattern.compile("^wandscape\\.character_name\\.zh\\.s(\\d+)\\.g(\\d+)$");
    private static final Pattern KEY_EN_COMPOSITE =
            Pattern.compile("^wandscape\\.character_name\\.en\\.f(\\d+)\\.l(\\d+)$");
    private static final Pattern KEY_FANTASY =
            Pattern.compile("^wandscape\\.character_name\\.fantasy\\.(\\d+)$");

    private static final int MAX_NAME_RETRY = 8;

    private static final String[] ZH_NAMES = {
        "王明","李华","张伟","刘洋","陈静","杨帆","赵磊","黄勇","周杰","吴婷",
        "徐浩然","孙丽","马超","朱志强","胡晓","郭峰","何俊","林芳","罗成","郑雪",
        "梁文","谢强","宋佳","唐杰","许静","韩梅","冯刚","邓丽","曹宇","彭泽",
        "王浩","李欣","张帆","刘欣怡","陈雨欣","杨子轩","赵睿","黄琪","周瑶","吴涵",
        "徐婷","孙浩然","马骏","朱航"
    };

    /** 西幻池：拉丁/罗马人名（66 男 + 347 女），索引即 lang key 编号。 */
    private static final String[] FANTASY_NAMES = {
        "Appius", "Aulus", "Caelus", "Decius", "Decimus", "Faustus", "Flavius", "Gaius",
        "Caius", "Cnaeus", "Gnaeus", "Kaeso", "Caeso", "Lucius", "Mamercus", "Maximus",
        "Manius", "Marcus", "Mettius", "Numerius", "Octavianus", "Publius", "Quintus", "Secundus",
        "Septimus", "Servius", "Sextus", "Spurius", "Tertius", "Tiberius", "Titus", "Agrippa",
        "Amulius", "Arruns", "Camillus", "Canus", "Cossus", "Drusus", "Gallus", "Herius",
        "Hostus", "Lar", "Lars", "Marcellus", "Nonus", "Opiter", "Oppius", "Paulus",
        "Paullus", "Postumius", "Potitus", "Primus", "Proclus", "Proculus", "Sisenna", "Tullus",
        "Vel", "Vibius", "Vopiscus", "Augustus", "Cassius", "Galerius", "Gallio", "Julianus",
        "Placus", "Quintis", "Aburia", "Accia", "Accoleia", "Acilia", "Aebutia", "Aedinia",
        "Aelia", "Aemilia", "Albania", "Albatia", "Allectia", "Amatia", "Annia", "Antistia",
        "Antia", "Antonia", "Appuleia", "Aquillia", "Armenia", "Arria", "Arsinia", "Artoria",
        "Asinia", "Ateia", "Atia", "Atilia", "Atria", "Atronia", "Attia", "Aufidia",
        "Aurelia", "Auria", "Ausonia", "Avidia", "Avita", "Axia", "Babudia", "Baebia",
        "Balventia", "Bantia", "Barbatia", "Barria", "Betiliena", "Betucia", "Blandia", "Blossia",
        "Bruccia", "Bruttia", "Bucculeia", "Burriena", "Caecilia", "Caecina", "Caecia", "Caedicia",
        "Caelia", "Caeparia", "Caepasia", "Caerellia", "Caesennia", "Caesetia", "Caesia", "Caesonia",
        "Caesulena", "Caetronia", "Calavia", "Calidia", "Calpurnia", "Calventia", "Calvisia", "Camilia",
        "Camillia", "Camelia", "Canidia", "Caninia", "Cania", "Canuleia", "Canutia", "Caprenia",
        "Caria", "Caristania", "Carvilia", "Cassia", "Ceionia", "Cicereia", "Cilnia", "Cincia",
        "Cispia", "Claudia", "Clodia", "Cloelia", "Clovia", "Cluilia", "Cluntia", "Cocceia",
        "Coiedia", "Cominia", "Consentia", "Considia", "Coruncania", "Cordia", "Cornelia", "Cosconia",
        "Curia", "Curtia", "Decumia", "Desticia", "Dexsia", "Didia", "Dillia", "Domitia",
        "Dossenia", "Duccia", "Duronia", "Egnatia", "Epidia", "Equitia", "Fabia", "Fadia",
        "Faenia", "Faleria", "Favonia", "Festinia", "Flavia", "Flavinia", "Flavonia", "Floridia",
        "Floria", "Floronia", "Fufia", "Fulcinia", "Fulvia", "Fundana", "Furia", "Gabinia",
        "Galeria", "Gavia", "Gegania", "Gellia", "Grania", "Gratia", "Gratidia", "Helvetia",
        "Helvia", "Herennia", "Herminia", "Hirtia", "Horatia", "Hortensia", "Hosidia", "Hostilia",
        "Icilia", "Insteia", "Julia", "Junia", "Juventia", "Laberia", "Labiena", "Laelia",
        "Laetoria", "Lafrenia", "Lampronia", "Lartia", "Liburnia", "Licinia", "Livia", "Lollia",
        "Longinia", "Loreia", "Lucceia", "Lucilia", "Lucia", "Lucretia", "Lusia", "Lutatia",
        "Macrinia", "Maecilia", "Maelia", "Mallia", "Mamilia", "Manlia", "Manilia", "Marcia",
        "Maria", "Matia", "Maximia", "Memmia", "Menenia", "Messiena", "Metilia", "Milonia",
        "Minicia", "Minucia", "Modia", "Mucia", "Munatia", "Munia", "Murria", "Naevia",
        "Nasennia", "Nemetoria", "Nepia", "Nigidia", "Nigilia", "Ninnia", "Nipia", "Norbana",
        "Novia", "Numeria", "Octavia", "Olcinia", "Oppia", "Opsia", "Orania", "Otacilia",
        "Palpellia", "Papinia", "Papiria", "Papia", "Pedia", "Peltrasia", "Pescennia", "Petellia",
        "Petilia", "Petillia", "Petronia", "Pinaria", "Piscia", "Pisentia", "Placidia", "Plautia",
        "Plinia", "Plotia", "Pollia", "Pompeia", "Pompilia", "Pomponia", "Pomptina", "Pontidia",
        "Pontia", "Popidia", "Portia", "Postumia", "Potitia", "Paesentia", "Publicia", "Pullo",
        "Pupia", "Quinctilia", "Quinctia", "Quirinia", "Rabiria", "Rufia", "Rufria", "Rusonia",
        "Rutilia", "Sabucia", "Sallustia", "Salonia", "Salvia", "Scribonia", "Secundinia", "Secundia",
        "Seia", "Sempronia", "Sennia", "Sentia", "Septimia", "Sepunia", "Sepurcia", "Sergia",
        "Sertoria", "Servilia", "Sestia", "Sextilia", "Sextia", "Sidonia", "Silia", "Sittia",
        "Socellia", "Sornatia", "Spuria", "Statia", "Statilia", "Stertinia", "Suedia", "Sulpicia",
        "Tadia", "Talmudia", "Tanicia", "Tertinia", "Tettidia", "Tettiena", "Tettia", "Titiedia",
        "Titia", "Titinia", "Trebatia", "Trebellia", "Treblana", "Tremellia", "Tuccia", "Tullia",
        "Turullia", "Ulpia", "Umbrenia", "Umbria", "Ummidia", "Urgulania", "Uulia", "Vagennia",
        "Vagionia", "Vagnia", "Valeria", "Varia", "Vassenia", "Vatinia", "Vedia", "Velia",
        "Verania", "Verecundia", "Vergilia", "Verginia", "Vesnia", "Vesuvia", "Veturia", "Vibenia",
        "Vibidia", "Vibia", "Victricia", "Viducia", "Vinicia", "Vipsania", "Vipstana", "Viridia",
        "Viria", "Visellia", "Vitellia", "Vitruvia", "Volaginia", "Volcatia", "Volumnia", "Volusenna",
        "Volusena", "Volusia", "Vorenia", "Cantilia", "Cantia",
    };

    /** 中文池姓（50）。 */
    private static final String[] ZH_SURNAMES = {
        "王", "李", "张", "刘", "陈", "杨", "黄", "赵",
        "吴", "周", "徐", "孙", "马", "朱", "胡", "郭",
        "何", "高", "林", "罗", "郑", "梁", "谢", "宋",
        "唐", "许", "韩", "冯", "邓", "曹", "彭", "曾",
        "肖", "田", "董", "袁", "潘", "于", "蒋", "蔡",
        "余", "杜", "叶", "程", "苏", "魏", "吕", "丁",
        "任", "沈",
    };

    /** 中文池名（50）。 */
    private static final String[] ZH_GIVENS = {
        "伟", "明", "华", "静", "芳", "磊", "军", "洋",
        "勇", "杰", "涛", "超", "强", "斌", "鹏", "宇",
        "文", "辉", "浩", "凯", "欣", "雪", "婷", "娜",
        "丽", "梅", "玲", "琳", "燕", "娟", "敏", "霞",
        "平", "波", "刚", "健", "毅", "俊", "瑞", "琪",
        "涵", "轩", "博", "晨", "曦", "梦", "瑶", "佳",
        "怡", "泽",
    };

    /** 英文池名（50）。 */
    private static final String[] EN_FIRSTS = {
        "James", "William", "George", "Henry", "Charles", "Edward", "Robert", "Michael",
        "David", "Richard", "Thomas", "John", "Alexander", "Arthur", "Oliver", "Jack",
        "Harry", "Joseph", "Daniel", "Samuel", "Peter", "Leo", "Luke", "Nathan",
        "Oscar", "Elizabeth", "Sarah", "Alice", "Emily", "Emma", "Charlotte", "Amelia",
        "Sophia", "Olivia", "Grace", "Rose", "Lily", "Lucy", "Ella", "Eva",
        "Chloe", "Hannah", "Anna", "Victoria", "Rachel", "Jessica", "Jasmine", "Katherine",
        "Maria", "Julia",
    };

    /** 英文池姓（50）。 */
    private static final String[] EN_LASTS = {
        "Smith", "Johnson", "Williams", "Brown", "Jones", "Miller", "Davis", "Wilson",
        "Taylor", "Clark", "Walker", "Wright", "Turner", "Hall", "White", "Lewis",
        "Young", "King", "Baker", "Green", "Adams", "Hill", "Nelson", "Carter",
        "Mitchell", "Roberts", "Phillips", "Campbell", "Parker", "Evans", "Edwards", "Collins",
        "Stewart", "Morris", "Rogers", "Reed", "Cook", "Morgan", "Bell", "Murphy",
        "Bailey", "Cooper", "Richardson", "Cox", "Howard", "Ward", "Harrison", "Harris",
        "Thompson", "Robinson",
    };

    private static final Random RANDOM = new Random();

    private CharacterNames() {}

    // ── Generation ─────────────────────────────────────────────────────────

    /** Default style (western fantasy), used by legacy callers and fallbacks. */
    public static String generateRandomNameKey() {
        return generateRandomNameKey(NameStyle.FANTASY, null);
    }

    public static String generateRandomNameKey(NameStyle style) {
        return generateRandomNameKey(style, null);
    }

    /**
     * Roll a name key for the given style. {@code excludedDisplayNames} holds
     * display names already in use (usually the colony's current tourists), so
     * the single-name fantasy pool retries to avoid duplicates. Composite pools
     * (50×50) collide rarely enough that no retry is needed.
     */
    public static String generateRandomNameKey(NameStyle style,
                                               @Nullable Collection<String> excludedDisplayNames) {
        return switch (style) {
            case FANTASY -> {
                for (int attempt = 0; attempt < MAX_NAME_RETRY; attempt++) {
                    int i = RANDOM.nextInt(FANTASY_NAMES.length);
                    String key = KEY_PREFIX + "fantasy." + i;
                    if (excludedDisplayNames == null
                            || !excludedDisplayNames.contains(localizedString(key))) {
                        yield key;
                    }
                }
                yield KEY_PREFIX + "fantasy." + RANDOM.nextInt(FANTASY_NAMES.length);
            }
            case CHINESE -> KEY_PREFIX + "zh.s" + RANDOM.nextInt(ZH_SURNAMES.length)
                    + ".g" + RANDOM.nextInt(ZH_GIVENS.length);
            case ENGLISH -> KEY_PREFIX + "en.f" + RANDOM.nextInt(EN_FIRSTS.length)
                    + ".l" + RANDOM.nextInt(EN_LASTS.length);
        };
    }

    // ── Display ────────────────────────────────────────────────────────────

    /**
     * Translatable component for a name key — the client renders it in its own
     * language. A composite key (zh/en) becomes two translatable parts joined by
     * a language-adaptive separator key. A legacy literal name is wrapped as a
     * translatable key that falls back to itself, so it still displays unchanged.
     */
    public static Component displayComponent(String keyOrName) {
        if (keyOrName == null || keyOrName.isEmpty()) {
            return Component.literal(keyOrName == null ? "" : keyOrName);
        }
        int[] zh = parseChineseComposite(keyOrName);
        if (zh != null) {
            return Component.translatable(KEY_PREFIX + "zhs." + zh[0])
                    .append(Component.translatable(KEY_PREFIX + "sep_zh"))
                    .append(Component.translatable(KEY_PREFIX + "zhg." + zh[1]));
        }
        int[] en = parseEnglishComposite(keyOrName);
        if (en != null) {
            return Component.translatable(KEY_PREFIX + "enf." + en[0])
                    .append(Component.translatable(KEY_PREFIX + "sep_en"))
                    .append(Component.translatable(KEY_PREFIX + "enl." + en[1]));
        }
        return Component.translatable(keyOrName);
    }

    /**
     * Resolve a name key to the current language's string (client language on
     * the client, en_us on a dedicated server). Legacy literal names pass
     * through unchanged; missing lang entries fall back to the embedded pools.
     */
    public static String localizedString(String keyOrName) {
        if (keyOrName == null || keyOrName.isEmpty()) return keyOrName;
        if (!keyOrName.startsWith(KEY_PREFIX)) {
            return keyOrName; // legacy literal
        }
        int[] zh = parseChineseComposite(keyOrName);
        if (zh != null) {
            return resolve(KEY_PREFIX + "zhs." + zh[0], zh[0], ZH_SURNAMES)
                    + resolve(KEY_PREFIX + "sep_zh", " ")
                    + resolve(KEY_PREFIX + "zhg." + zh[1], zh[1], ZH_GIVENS);
        }
        int[] en = parseEnglishComposite(keyOrName);
        if (en != null) {
            return resolve(KEY_PREFIX + "enf." + en[0], en[0], EN_FIRSTS)
                    + resolve(KEY_PREFIX + "sep_en", " ")
                    + resolve(KEY_PREFIX + "enl." + en[1], en[1], EN_LASTS);
        }
        int fantasyIdx = parseFantasyIndex(keyOrName);
        if (fantasyIdx >= 0) {
            return resolve(KEY_PREFIX + "fantasy." + fantasyIdx, fantasyIdx, FANTASY_NAMES);
        }
        // Legacy flat key: wandscape.character_name.<i>
        int idx = indexOfKey(keyOrName);
        String resolved = Component.translatable(keyOrName).getString();
        if (idx >= 0 && resolved.equals(keyOrName)) {
            return ZH_NAMES[idx]; // lang entry missing — Chinese fallback
        }
        return resolved;
    }

    // ── Pure key parsing (no MC runtime — unit-testable) ───────────────────

    /** Chinese composite key → [surnameIdx, givenIdx], or null. */
    static int[] parseChineseComposite(String key) {
        Matcher m = KEY_ZH_COMPOSITE.matcher(key);
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** English composite key → [firstIdx, lastIdx], or null. */
    static int[] parseEnglishComposite(String key) {
        Matcher m = KEY_EN_COMPOSITE.matcher(key);
        if (!m.matches()) return null;
        return new int[]{Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))};
    }

    /** Fantasy single-name key → pool index, or -1. */
    static int parseFantasyIndex(String key) {
        Matcher m = KEY_FANTASY.matcher(key);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    /** Embedded fallback name, or null when the index is out of range. */
    @Nullable
    static String fantasyName(int idx) {
        return idx >= 0 && idx < FANTASY_NAMES.length ? FANTASY_NAMES[idx] : null;
    }

    static int fantasyPoolSize() {
        return FANTASY_NAMES.length;
    }

    private static String resolve(String langKey, int idx, String[] fallback) {
        String resolved = Component.translatable(langKey).getString();
        if (idx >= 0 && idx < fallback.length && resolved.equals(langKey)) {
            return fallback[idx];
        }
        return resolved;
    }

    private static String resolve(String langKey, String fallback) {
        String resolved = Component.translatable(langKey).getString();
        return resolved.equals(langKey) ? fallback : resolved;
    }

    private static int indexOfKey(String key) {
        try {
            return Integer.parseInt(key.substring(KEY_PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
