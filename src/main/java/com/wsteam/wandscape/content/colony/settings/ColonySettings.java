package com.wsteam.wandscape.content.colony.settings;

import com.wsteam.wandscape.content.colony.ownership.ColonyOwnership;
import com.wsteam.wandscape.content.colony.roster.ColonyRole;

import com.wsteam.wandscape.api.ColonyApi;
import com.wsteam.wandscape.api.WandscapeApis;
import com.wsteam.wandscape.content.colony.ColonySavedData;
import com.wsteam.wandscape.foundation.log.Log;
import com.wsteam.wandscape.foundation.util.NameStyle;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 本镇设置的唯一命名类：key、默认值、合法值校验与服务端写入全收敛在这里。
 *
 * <p>这些设置随殖民地走（ColonySavedData），与 {@code Config} 里的全局配置是两回事：
 * 面板上它们由 {@code SettingsRegistry} 登记在「本镇」页，只有该镇 OWNER 能改。
 *
 * <p><b>目标恒为「玩家当前操作的小镇」</b>：更新包不带 colonyId，服务端只按
 * {@link ColonyOwnership#activeColony} 解析，所以伪造他人殖民地 id 这条攻击面不存在；
 * 但档位必须另行把关——多殖民地下一个玩家可能是别人镇的 MEMBER，不足 OWNER 一律拒。
 *
 * <p>Per-colony settings, resolved against the requesting player's active colony rather than any
 * colony id sent from the client, and gated on the OWNER role in that colony.
 */
public final class ColonySettings {

    private static final String TAG = "ColonySettings";

    /** 游客与法师的起名风格：西幻 / 中文 / 英文。 */
    public static final String KEY_NAMING_STYLE = "colony.namingStyle";
    /** 本镇市政厅的「生成游客」开关。 */
    public static final String KEY_TOURIST_SPAWN = "colony.touristSpawning";

    public static final NameStyle DEFAULT_NAMING_STYLE = NameStyle.FANTASY;
    public static final boolean DEFAULT_TOURIST_SPAWN = true;

    private ColonySettings() {}

    /** key 的默认值（raw 形式，与面板的往返读写同一套编码）；未知 key 返回空串。 */
    public static String defaultRaw(String key) {
        if (KEY_NAMING_STYLE.equals(key)) return String.valueOf(DEFAULT_NAMING_STYLE.ordinal());
        if (KEY_TOURIST_SPAWN.equals(key)) return String.valueOf(DEFAULT_TOURIST_SPAWN);
        return "";
    }

    /**
     * 服务端应用一次本镇设置改动。
     *
     * @return 是否真的写下去了；false（无小镇 / 未知 key / 值非法）时调用方应回推权威值，
     *         让客户端的乐观改动撤回。
     */
    public static boolean apply(ServerPlayer player, String key, String value) {
        if (player == null || player.isRemoved() || key == null) return false;

        ColonyApi colonyApi = WandscapeApis.getColonyApiSilently();
        if (colonyApi == null) return false;

        UUID colonyId = ColonyOwnership.activeColony(player);
        if (colonyId == null) {
            Log.warn(TAG, "Player {} has no active colony — rejecting setting {}",
                    player.getGameProfile().getName(), key);
            return false;
        }
        // 档位：改本镇设置 = OWNER（映射「OWNER：改小镇设置」），其余档位一律拒止并反馈。
        if (!ColonyOwnership.hasRole(player, colonyId, ColonyRole.OWNER)) {
            ColonyOwnership.deny(player, "town", "小镇");
            Log.warn(TAG, "Player {} is not OWNER of colony {} — rejecting setting {}",
                    player.getGameProfile().getName(), colonyId.toString().substring(0, 8), key);
            return false;
        }

        return switch (key) {
            case KEY_NAMING_STYLE -> applyNamingStyle(colonyApi, colonyId, value);
            case KEY_TOURIST_SPAWN -> applyTouristSpawn(player, colonyId, value);
            default -> {
                Log.warn(TAG, "Unknown colony setting key: {}", key);
                yield false;
            }
        };
    }

    private static boolean applyNamingStyle(ColonyApi colonyApi, UUID colonyId, String value) {
        int ordinal;
        try {
            ordinal = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Log.warn(TAG, "Invalid naming style value: {}", value);
            return false;
        }
        NameStyle[] styles = NameStyle.values();
        if (ordinal < 0 || ordinal >= styles.length) {
            Log.warn(TAG, "Naming style ordinal out of range: {}", ordinal);
            return false;
        }
        colonyApi.setNamingStyle(colonyId, styles[ordinal]);
        return true;
    }

    private static boolean applyTouristSpawn(ServerPlayer player, UUID colonyId, String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            Log.warn(TAG, "Invalid tourist spawn value: {}", value);
            return false;
        }
        ColonySavedData.getOrCreate(player.serverLevel())
                .setTouristSpawningEnabled(colonyId, Boolean.parseBoolean(value));
        return true;
    }
}
