package com.wsteam.wandscape.shared.data;

import java.util.Random;

import net.minecraft.network.chat.Component;

/**
 * Shared bilingual name pool used by both mages ({@code WandscapeNpc}) and
 * tourists ({@code TouristEntity}). Every name is a lang key
 * ({@code wandscape.character_name.<i>}) so the client renders it in its own
 * language (Chinese or English). Kept in {@code shared/} because it is visible
 * to all modules without cross-module coupling.
 *
 * <p>Names are stored server-side as their key; {@link #localizedString} resolves
 * a key to the current language (falling back to Chinese if the lang entry is
 * missing, e.g. on a server without the mod's lang loaded). Legacy saves with a
 * literal Chinese name pass through unchanged.
 */
public final class CharacterNames {

    public static final String KEY_PREFIX = "wandscape.character_name.";

    private static final String[] ZH_NAMES = {
        "王明","李华","张伟","刘洋","陈静","杨帆","赵磊","黄勇","周杰","吴婷",
        "徐浩然","孙丽","马超","朱志强","胡晓","郭峰","何俊","林芳","罗成","郑雪",
        "梁文","谢强","宋佳","唐杰","许静","韩梅","冯刚","邓丽","曹宇","彭泽",
        "王浩","李欣","张帆","刘欣怡","陈雨欣","杨子轩","赵睿","黄琪","周瑶","吴涵",
        "徐婷","孙浩然","马骏","朱航"
    };
    private static final String[] EN_NAMES = {
        "Wang Ming","Li Hua","Zhang Wei","Liu Yang","Chen Jing","Yang Fan","Zhao Lei","Huang Yong","Zhou Jie","Wu Ting",
        "Xu Haoran","Sun Li","Ma Chao","Zhu Zhiqiang","Hu Xiao","Guo Feng","He Jun","Lin Fang","Luo Cheng","Zheng Xue",
        "Liang Wen","Xie Qiang","Song Jia","Tang Jie","Xu Jing","Han Mei","Feng Gang","Deng Li","Cao Yu","Peng Ze",
        "Wang Hao","Li Xin","Zhang Fan","Liu Xinyi","Chen Yuxin","Yang Zixuan","Zhao Rui","Huang Qi","Zhou Yao","Wu Han",
        "Xu Ting","Sun Haoran","Ma Jun","Zhu Hang"
    };

    private static final Random RANDOM = new Random();

    private CharacterNames() {}

    /** Roll a random name lang key. */
    public static String generateRandomNameKey() {
        return KEY_PREFIX + RANDOM.nextInt(ZH_NAMES.length);
    }

    /**
     * Translatable component for a name key — the client renders it in its own
     * language. A legacy literal name (old saves) is wrapped as a translatable
     * key that falls back to itself, so it still displays unchanged.
     */
    public static Component displayComponent(String keyOrName) {
        return Component.translatable(keyOrName);
    }

    /**
     * Resolve a name key to the current language's string. A legacy literal name
     * passes through unchanged. Falls back to Chinese when the lang entry cannot
     * be resolved (e.g. dedicated server without the mod's lang loaded).
     */
    public static String localizedString(String keyOrName) {
        if (keyOrName == null || keyOrName.isEmpty()) return keyOrName;
        if (!keyOrName.startsWith(KEY_PREFIX)) {
            return keyOrName;
        }
        int idx = indexOfKey(keyOrName);
        String resolved = Component.translatable(keyOrName).getString();
        if (idx >= 0 && resolved.equals(keyOrName)) {
            return ZH_NAMES[idx]; // lang entry missing — Chinese fallback
        }
        return resolved;
    }

    private static int indexOfKey(String key) {
        try {
            return Integer.parseInt(key.substring(KEY_PREFIX.length()));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return -1;
        }
    }
}
