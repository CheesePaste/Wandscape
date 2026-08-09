package com.wsteam.wandscape.building.data;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import com.wsteam.wandscape.shared.data.Activity;
import com.wsteam.wandscape.shared.data.AtmConfig;
import com.wsteam.wandscape.shared.data.DecorationConfig;
import com.wsteam.wandscape.shared.data.ElementType;
import com.wsteam.wandscape.shared.data.MaintenanceCostConfig;
import com.wsteam.wandscape.shared.data.RelaxConfig;
import com.wsteam.wandscape.shared.data.ServiceConfig;
import com.wsteam.wandscape.shared.data.ShopConfig;
import com.wsteam.wandscape.shared.data.WonderConfig;
/**
 * Parsed from {@code data/wandscape/buildings/<id>.json}.
 * Immutable — created once at JSON load time.
 */
public record BuildingConfig(
        String id,
        @SerializedName("display_name") String displayName,
        String category,
        List<BlockOffset> pattern,
        @SerializedName("block_mapping") Map<String, String> blockMapping,
        @SerializedName("block_nbt") Map<String, String> blockNbt,
        int comfort,
        int magic,
        int wonder,
        QueueDef queue,
        @SerializedName("unlock_requirement") UnlockRequirement unlockRequirement,
        @Nullable BoundaryBox boundary,
        @Nullable BlueprintRef blueprint,
        @Nullable NodeConfig nodeConfig,
        @SerializedName("maintenance_cost") MaintenanceCostConfig maintenanceCost,
        DecorationConfig decoration,
        @SerializedName("wonder_config") WonderConfig wonderConfig,
        ShopConfig shop,
        ServiceConfig service,
        RelaxConfig relax,
        AtmConfig atm,
        @SerializedName("door_offset") @Nullable BlockOffset doorOffset,
        @SerializedName("interact_spots") List<InteractSpot> interactSpots,
        @SerializedName("first_free") boolean firstFree,
        @SerializedName("deprecated") boolean deprecated
) {
    public record QueueDef(
            int capacity,
            @SerializedName("task_types") List<String> taskTypes
    ) {
        public static final QueueDef DEFAULT = new QueueDef(5, List.of("building"));
    }

    public record UnlockRequirement(
            @SerializedName("min_colony_level") int minColonyLevel
    ) {
        public static final UnlockRequirement NONE = new UnlockRequirement(1);
    }

    /**
     * Node building configuration. Only present when {@code category == "node"}.
     */
    public record NodeConfig(
            String blueprint,
            String element,
            @SerializedName("amount_per_harvest") int amountPerHarvest,
            @SerializedName("channel_ticks") int channelTicks
    ) {
    }

    /**
     * Reference to a Blueprint DSL JSON that defines task logic for this building.
     *
     * @param id   the blueprint ID (e.g. "build:place_structure")
     * @param bind key = blueprint param name, value = {@code $field_name}
     *             bare variable reference to a building JSON field
     */
    public record BlueprintRef(
            String id,
            Map<String, String> bind
    ) {
        public BlueprintRef {
            if (bind == null) bind = Collections.emptyMap();
        }
    }

    /** AABB 包围盒，角点相对于 anchor。 */
    public record BoundaryBox(
            BlockOffset min,
            BlockOffset max
    ) {
        public BoundaryBox {
            if (min == null || max == null) {
                throw new IllegalArgumentException("boundary min and max must not be null");
            }
        }

        /** 遍历 AABB 内所有坐标，Y 从低到高（由下往上建造）。 */
        public List<BlockOffset> allPositions() {
            List<BlockOffset> result = new ArrayList<>();
            for (int y = min.y(); y <= max.y(); y++) {
                for (int x = min.x(); x <= max.x(); x++) {
                    for (int z = min.z(); z <= max.z(); z++) {
                        result.add(new BlockOffset(x, y, z));
                    }
                }
            }
            return result;
        }
    }

    /** 交互位：相对 anchor 的坐标 + 动作种类。spot 数量 = 该建筑同时交互的游客人数上限。 */
    public record InteractSpot(
            BlockOffset pos,
            Activity action
    ) {
        public InteractSpot {
            if (pos == null) {
                throw new IllegalArgumentException("interact spot pos must not be null");
            }
            if (action == null) action = Activity.BROWSE;
        }
    }

    /** 该建筑是不是游客交互目标（四类旅游 category 之一）。 */
    public boolean isTouristTarget() {
        return shop() != ShopConfig.NONE || service() != ServiceConfig.NONE
                || relax() != RelaxConfig.NONE || atm() != AtmConfig.NONE;
    }

    /** 派生视图：由 interactSpots 算出退化 AABB（一阶段兼容旧消费者；二阶段删）。 */
    public List<BoundaryBox> touristInteractAabb() {
        return interactSpots.stream().map(s -> new BoundaryBox(s.pos(), s.pos())).toList();
    }

    /**
     * Custom Gson deserializer that applies defaults for missing optional sections.
     */
    public static class Deserializer implements JsonDeserializer<BuildingConfig> {
        @Override
        public BuildingConfig deserialize(JsonElement json, Type typeOfT,
                                           JsonDeserializationContext context) throws JsonParseException {
            JsonObject obj = json.getAsJsonObject();

            String id = getString(obj, "id", "");
            String displayName = getString(obj, "display_name", "");
            String category = getString(obj, "category", "basic");

            // Pattern
            List<BlockOffset> pattern = List.of();
            if (obj.has("pattern")) {
                List<BlockOffset> list = new ArrayList<>();
                JsonArray arr = obj.getAsJsonArray("pattern");
                BlockOffset.Deserializer offsetDs = new BlockOffset.Deserializer();
                for (JsonElement el : arr) {
                    list.add(offsetDs.deserialize(el, BlockOffset.class, context));
                }
                pattern = List.copyOf(list);
            }

            // Block mapping
            Map<String, String> blockMapping = Map.of();
            if (obj.has("block_mapping")) {
                JsonObject map = obj.getAsJsonObject("block_mapping");
                Map<String, String> m = new HashMap<>();
                for (var entry : map.entrySet()) {
                    m.put(entry.getKey(), entry.getValue().getAsString());
                }
                blockMapping = Map.copyOf(m);
            }

            // Block NBT (base64-encoded BlockEntity data)
            Map<String, String> blockNbt = Map.of();
            if (obj.has("block_nbt")) {
                JsonObject nbtMap = obj.getAsJsonObject("block_nbt");
                Map<String, String> m = new HashMap<>();
                for (var entry : nbtMap.entrySet()) {
                    m.put(entry.getKey(), entry.getValue().getAsString());
                }
                blockNbt = Map.copyOf(m);
            }

            int comfort = getInt(obj, "comfort", 0);
            int magic = getInt(obj, "magic", 0);
            int wonder = getInt(obj, "wonder", 0);

            QueueDef queue = QueueDef.DEFAULT;
            if (obj.has("queue")) {
                queue = context.deserialize(obj.get("queue"), QueueDef.class);
            }

            UnlockRequirement unlockRequirement = UnlockRequirement.NONE;
            if (obj.has("unlock_requirement")) {
                unlockRequirement = context.deserialize(
                        obj.get("unlock_requirement"), UnlockRequirement.class);
            }

            // Boundary
            BoundaryBox boundary = null;
            if (obj.has("boundary")) {
                JsonObject bObj = obj.getAsJsonObject("boundary");
                BlockOffset.Deserializer offsetDs2 = new BlockOffset.Deserializer();
                BlockOffset bMin = offsetDs2.deserialize(bObj.get("min"), BlockOffset.class, context);
                BlockOffset bMax = offsetDs2.deserialize(bObj.get("max"), BlockOffset.class, context);
                boundary = new BoundaryBox(bMin, bMax);
            }

            // Blueprint reference
            BlueprintRef blueprint = null;
            if (obj.has("blueprint")) {
                JsonObject bpObj = obj.getAsJsonObject("blueprint");
                String bpId = bpObj.get("id").getAsString();
                Map<String, String> bind = Collections.emptyMap();
                if (bpObj.has("bind")) {
                    JsonObject bindObj = bpObj.getAsJsonObject("bind");
                    Map<String, String> m = new HashMap<>();
                    for (var entry : bindObj.entrySet()) {
                        m.put(entry.getKey(), entry.getValue().getAsString());
                    }
                    bind = Map.copyOf(m);
                }
                blueprint = new BlueprintRef(bpId, bind);
            }

            // Node config (only for category=node buildings)
            NodeConfig nodeConfig = null;
            if (obj.has("node_config")) {
                nodeConfig = context.deserialize(obj.get("node_config"), NodeConfig.class);
            }

            // Maintenance cost (all buildings, defaults to NONE)
            MaintenanceCostConfig maintenanceCost = MaintenanceCostConfig.NONE;
            if (obj.has("maintenance_cost")) {
                JsonObject mcObj = obj.getAsJsonObject("maintenance_cost");
                Map<ElementType, Integer> costs = new HashMap<>();
                if (mcObj.has("costs")) {
                    JsonObject costsObj = mcObj.getAsJsonObject("costs");
                    for (var entry : costsObj.entrySet()) {
                        costs.put(ElementType.fromId(entry.getKey()), entry.getValue().getAsInt());
                    }
                }
                maintenanceCost = new MaintenanceCostConfig(Map.copyOf(costs));
            }

            // Decoration config (only for category=decoration)
            DecorationConfig decoration = null;
            if (obj.has("decoration")) {
                decoration = context.deserialize(obj.get("decoration"), DecorationConfig.class);
            }

            // Wonder config (only for category=wonder)
            WonderConfig wonderConfig = WonderConfig.NONE;
            if (obj.has("wonder_config")) {
                wonderConfig = context.deserialize(obj.get("wonder_config"), WonderConfig.class);
            }

            // Shop config (only for category=shop)
            ShopConfig shop = ShopConfig.NONE;
            if (obj.has("shop")) {
                shop = context.deserialize(obj.get("shop"), ShopConfig.class);
            }

            // Service config (only for category=service)
            ServiceConfig service = ServiceConfig.NONE;
            if (obj.has("service")) {
                service = context.deserialize(obj.get("service"), ServiceConfig.class);
            }

            // Relax config (only for category=relax): 回复精力
            RelaxConfig relax = RelaxConfig.NONE;
            if (obj.has("relax")) {
                relax = context.deserialize(obj.get("relax"), RelaxConfig.class);
            }

            // Atm config (only for category=atm): 取现
            AtmConfig atm = AtmConfig.NONE;
            if (obj.has("atm")) {
                atm = context.deserialize(obj.get("atm"), AtmConfig.class);
            }

            // Door offset: position of the building door relative to anchor.
            // When not specified, entry point is computed via heuristic spiral scan.
            BlockOffset doorOffset = null;
            if (obj.has("door_offset")) {
                doorOffset = new BlockOffset.Deserializer().deserialize(
                        obj.get("door_offset"), BlockOffset.class, context);
            }

            // First-free build flag: when true, the first build of this building
            // type in a colony does not consume warehouse materials.
            boolean firstFree = getBoolean(obj, "first_free", false);

            // Deprecated flag: config still loads (old-map buildings keep working),
            // but the building is hidden from the placement panel (BUILD_PROJECTION bar).
            boolean deprecated = getBoolean(obj, "deprecated", false);

            // Interact spots: 交互位列表（相对 anchor 坐标 + 动作种类）。
            // 旧 tourist_interact_aabb 顶层字段不再解析；0-spot 建筑对游客无效（Block 3 过滤，无兜底）。
            List<InteractSpot> interactSpots = List.of();
            if (obj.has("interact_spots")) {
                JsonArray spotsArr = obj.getAsJsonArray("interact_spots");
                List<InteractSpot> spots = new ArrayList<>();
                BlockOffset.Deserializer spotDs = new BlockOffset.Deserializer();
                for (JsonElement spotEl : spotsArr) {
                    JsonObject spotObj = spotEl.getAsJsonObject();
                    BlockOffset pos = spotDs.deserialize(spotObj.get("pos"), BlockOffset.class, context);
                    String actionStr = spotObj.has("action") ? spotObj.get("action").getAsString() : "";
                    spots.add(new InteractSpot(pos, Activity.fromJsonString(actionStr)));
                }
                interactSpots = List.copyOf(spots);
            }

            return new BuildingConfig(id, displayName, category,
                    pattern, blockMapping, blockNbt,
                    comfort, magic, wonder,
                    queue, unlockRequirement, boundary, blueprint, nodeConfig,
                    maintenanceCost, decoration, wonderConfig, shop, service, relax, atm,
                    doorOffset, interactSpots, firstFree, deprecated);
        }

        private static String getString(JsonObject obj, String key, String def) {
            return obj.has(key) ? obj.get(key).getAsString() : def;
        }

        private static int getInt(JsonObject obj, String key, int def) {
            return obj.has(key) ? obj.get(key).getAsInt() : def;
        }

        private static boolean getBoolean(JsonObject obj, String key, boolean def) {
            return obj.has(key) ? obj.get(key).getAsBoolean() : def;
        }
    }
}
