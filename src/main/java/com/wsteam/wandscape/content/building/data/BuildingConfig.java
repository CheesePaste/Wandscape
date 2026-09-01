package com.wsteam.wandscape.content.building.data;
import com.wsteam.wandscape.content.tourist.data.DecorationConfig;
import com.wsteam.wandscape.content.tourist.data.ServiceConfig;
import com.wsteam.wandscape.content.tourist.data.AtmConfig;
import com.wsteam.wandscape.content.tourist.data.WonderConfig;
import com.wsteam.wandscape.content.tourist.data.ShopConfig;
import com.wsteam.wandscape.content.tourist.data.RelaxConfig;
import com.wsteam.wandscape.content.tourist.data.Activity;

import com.google.gson.*;
import com.google.gson.annotations.SerializedName;
// data imports updated
import net.minecraft.core.Direction;

import javax.annotation.Nullable;
import java.lang.reflect.Type;
import java.util.*;
/**
 * Parsed from {@code data/wandscape/buildings/<id>.json}.
 * Immutable — created once at JSON load time.
 */
public record BuildingConfig(
        String id,
        @SerializedName("display_name") String displayName,
        @SerializedName("creator") String creator,
        String category,
        List<BlockOffset> pattern,
        List<String> palette,
        @SerializedName("block_indices") List<Integer> blockIndices,
        @SerializedName("block_nbt") Map<String, String> blockNbt,
        int comfort,
        int magic,
        int wonder,
        @SerializedName("unlock_requirement") UnlockRequirement unlockRequirement,
        @Nullable BoundaryBox boundary,
        @Nullable BlueprintRef blueprint,
        @Nullable NodeConfig nodeConfig,
        DecorationConfig decoration,
        @SerializedName("wonder_config") WonderConfig wonderConfig,
        ShopConfig shop,
        ServiceConfig service,
        RelaxConfig relax,
        AtmConfig atm,
        @SerializedName("door_offsets") List<BlockOffset> doorOffsets,
        @SerializedName("interact_spots") List<InteractSpot> interactSpots,
        @SerializedName("first_free") boolean firstFree,
        @SerializedName("deprecated") boolean deprecated,
        @SerializedName("entities") List<DecorationEntity> entities
) {
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

    /** 交互位：相对 anchor 的坐标 + 动作种类 + 朝向。spot 数量 = 该建筑同时交互的游客人数上限。 */
    public record InteractSpot(
            BlockOffset pos,
            Activity action,
            Direction facing
    ) {
        public InteractSpot {
            if (pos == null) {
                throw new IllegalArgumentException("interact spot pos must not be null");
            }
            if (action == null) action = Activity.BROWSE;
            if (facing == null || facing.getAxis() == Direction.Axis.Y) facing = Direction.SOUTH;
        }
    }

    /**
     * 装饰实体（物品展示框/画等悬挂实体）：相对 anchor 的偏移 + 实体类型 + 朝向 + 修剪后实体 NBT。
     * 由扫描器导出；建造时经 spawn_entity 步骤重建。offset 为实体所在的方块格。
     * facing 是 Direction 字符串（如 "north"），独立成字段以便旋转只动结构化字段、不碰 base64。
     */
    public record DecorationEntity(
            BlockOffset offset,
            String type,
            String facing,
            @Nullable String nbtBase64
    ) {
        public DecorationEntity {
            if (offset == null) {
                throw new IllegalArgumentException("decoration entity offset must not be null");
            }
        }
    }

    /** Block state string at {@code patternIndex} (parallel to {@link #pattern()}). */
    public String blockIdAt(int patternIndex) {
        return palette.get(blockIndices.get(patternIndex));
    }

    /**
     * Derived offset→blockstate map (key "x,y,z"). O(N) each call — prefer
     * {@link #blockIdAt(int)} in hot paths (material counting, renderers).
     */
    public Map<String, String> blockMapping() {
        Map<String, String> m = new HashMap<>(pattern.size());
        for (int i = 0; i < pattern.size(); i++) {
            m.put(pattern.get(i).toKey(), blockIdAt(i));
        }
        return Collections.unmodifiableMap(m);
    }

    /** 该建筑是不是游客交互目标（四类旅游 category 之一）。 */
    public boolean isTouristTarget() {
        return shop() != ShopConfig.NONE || service() != ServiceConfig.NONE
                || relax() != RelaxConfig.NONE || atm() != AtmConfig.NONE;
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
            String creator = getString(obj, "creator", "");
            String category = getString(obj, "category", "basic");

            if (obj.has("block_mapping")) {
                throw new JsonParseException("Building '" + id
                        + "' uses legacy block_mapping format — migrate to palette + block_indices");
            }

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

            // Palette + block_indices (parallel to pattern)
            List<String> palette = List.of();
            if (obj.has("palette")) {
                JsonArray pal = obj.getAsJsonArray("palette");
                List<String> p = new ArrayList<>(pal.size());
                for (JsonElement el : pal) {
                    p.add(el.getAsString());
                }
                palette = List.copyOf(p);
            }
            List<Integer> blockIndices = List.of();
            if (obj.has("block_indices")) {
                JsonArray idx = obj.getAsJsonArray("block_indices");
                List<Integer> li = new ArrayList<>(idx.size());
                for (JsonElement el : idx) {
                    li.add(el.getAsInt());
                }
                blockIndices = List.copyOf(li);
            }
            if (blockIndices.size() != pattern.size()) {
                throw new JsonParseException("Building '" + id + "': block_indices.size()="
                        + blockIndices.size() + " != pattern.size()=" + pattern.size());
            }
            for (int i : blockIndices) {
                if (i < 0 || i >= palette.size()) {
                    throw new JsonParseException("Building '" + id + "': block_indices value " + i
                            + " out of palette range [0," + palette.size() + ")");
                }
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

            // Door offsets: positions of the building doors relative to anchor.
            // New format "door_offsets" is a list; legacy single "door_offset" still loads.
            // When not specified, entry point is computed via heuristic spiral scan.
            List<BlockOffset> doorOffsets = List.of();
            if (obj.has("door_offsets")) {
                JsonArray doorsArr = obj.getAsJsonArray("door_offsets");
                BlockOffset.Deserializer doorDs = new BlockOffset.Deserializer();
                List<BlockOffset> doors = new ArrayList<>();
                for (JsonElement el : doorsArr) {
                    doors.add(doorDs.deserialize(el, BlockOffset.class, context));
                }
                doorOffsets = List.copyOf(doors);
            } else if (obj.has("door_offset")) {
                doorOffsets = List.of(new BlockOffset.Deserializer().deserialize(
                        obj.get("door_offset"), BlockOffset.class, context));
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
                    String facingStr = spotObj.has("facing") ? spotObj.get("facing").getAsString() : "";
                    Direction facing = Direction.byName(facingStr); // 非法/缺省 → compact 构造回退 SOUTH
                    spots.add(new InteractSpot(pos, Activity.fromJsonString(actionStr), facing));
                }
                interactSpots = List.copyOf(spots);
            }

            // Decoration entities: 装饰实体列表（物品展示框/画）。缺省空列表。
            List<DecorationEntity> entities = List.of();
            if (obj.has("entities")) {
                JsonArray entsArr = obj.getAsJsonArray("entities");
                List<DecorationEntity> ents = new ArrayList<>();
                BlockOffset.Deserializer entDs = new BlockOffset.Deserializer();
                for (JsonElement entEl : entsArr) {
                    JsonObject entObj = entEl.getAsJsonObject();
                    BlockOffset offset = entDs.deserialize(entObj.get("offset"), BlockOffset.class, context);
                    String type = getString(entObj, "type", "");
                    String facing = getString(entObj, "facing", "");
                    String nbt = entObj.has("nbt") ? entObj.get("nbt").getAsString() : null;
                    ents.add(new DecorationEntity(offset, type, facing, nbt));
                }
                entities = List.copyOf(ents);
            }

            return new BuildingConfig(id, displayName, creator, category,
                    pattern, palette, blockIndices, blockNbt,
                    comfort, magic, wonder,
                    unlockRequirement, boundary, blueprint, nodeConfig,
                    decoration, wonderConfig, shop, service, relax, atm,
                    doorOffsets, interactSpots, firstFree, deprecated, entities);
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
