package com.wsteam.wandscape.content.building.data;

import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Metadata and catalog entry for a Building Package.
 * A package represents a folder under {@code data/<namespace>/buildings/<package_id>/}.
 */
public record BuildingPackage(
        String id,
        String name,
        String description,
        String author,
        String version,
        String iconItem,
        int priority,
        List<String> buildingIds
) {
    public static final String DEFAULT_ID = "default";

    /**
     * 扫描器导出建筑默认落进的自定义包。与 {@link #DEFAULT_ID} 的区别是定位：
     * {@code default} 是模组随 jar 发布的核心包，玩家不该往里写；{@code custom} 是玩家的地盘，
     * 导出与手写都进这里，世界数据包里那一份空目录骨架也指向它。
     */
    public static final String CUSTOM_ID = "custom";

    /** 包名会直接当作 {@code data/<ns>/buildings/<package_id>/} 的文件夹名，故只放行这些字符。 */
    private static final Pattern ILLEGAL_ID_CHARS = Pattern.compile("[^a-z0-9_-]");

    /**
     * 把任意来源的字符串归一化成合法包 id：转小写、非法字符换成 {@code _}、
     * 空则回落 {@link #DEFAULT_ID}。
     *
     * <p>包 id 可能来自客户端网络包（扫描器导出）或存档 NBT，都是外部输入；
     * 未净化时 {@code Path.resolve} 会让 {@code ../} 逃出数据包目录。
     */
    public static String sanitizeId(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_ID;
        return ILLEGAL_ID_CHARS.matcher(raw.trim().toLowerCase(Locale.ROOT)).replaceAll("_");
    }

    public BuildingPackage {
        if (buildingIds == null) {
            buildingIds = List.of();
        } else {
            buildingIds = List.copyOf(buildingIds);
        }
    }

    public static BuildingPackage defaultPackage() {
        return new BuildingPackage(
                DEFAULT_ID,
                "wandscape.pack.default.name",
                "wandscape.pack.default.desc",
                "Wandscape",
                "1.0.0",
                "wandscape:building_scanner",
                0,
                List.of()
        );
    }

    /**
     * 自定义（Custom）包的兜底元数据：世界数据包里没有 {@code custom/package.json} 时用它，
     * 保证建筑包列表里永远有一个可选的「自定义」入口。落盘的那份文件优先。
     */
    public static BuildingPackage customPackage() {
        return new BuildingPackage(
                CUSTOM_ID,
                "wandscape.pack.custom.name",
                "wandscape.pack.custom.desc",
                "Wandscape",
                "1.0.0",
                "wandscape:creative_building_scanner",
                100,
                List.of()
        );
    }

    public static BuildingPackage fromJson(String id, JsonObject obj) {
        String packId = obj.has("id") ? obj.get("id").getAsString() : id;
        String name = obj.has("name") ? obj.get("name").getAsString() : packId;
        String desc = obj.has("description") ? obj.get("description").getAsString() : "";
        String author = obj.has("author") ? obj.get("author").getAsString() : "";
        String version = obj.has("version") ? obj.get("version").getAsString() : "1.0.0";
        String icon = obj.has("icon") ? obj.get("icon").getAsString() : "minecraft:stone_bricks";
        int priority = obj.has("priority") ? obj.get("priority").getAsInt() : 100;
        return new BuildingPackage(packId, name, desc, author, version, icon, priority, List.of());
    }

    public BuildingPackage withBuildingIds(List<String> newBuildingIds) {
        return new BuildingPackage(id, name, description, author, version, iconItem, priority, newBuildingIds);
    }

    public void writeToBuf(RegistryFriendlyByteBuf buf) {
        buf.writeUtf(id);
        buf.writeUtf(name);
        buf.writeUtf(description);
        buf.writeUtf(author);
        buf.writeUtf(version);
        buf.writeUtf(iconItem);
        buf.writeVarInt(priority);
        buf.writeVarInt(buildingIds.size());
        for (String bId : buildingIds) {
            buf.writeUtf(bId);
        }
    }

    public static BuildingPackage readFromBuf(RegistryFriendlyByteBuf buf) {
        String id = buf.readUtf();
        String name = buf.readUtf();
        String description = buf.readUtf();
        String author = buf.readUtf();
        String version = buf.readUtf();
        String iconItem = buf.readUtf();
        int priority = buf.readVarInt();
        int count = buf.readVarInt();
        List<String> bIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            bIds.add(buf.readUtf());
        }
        return new BuildingPackage(id, name, description, author, version, iconItem, priority, bIds);
    }
}
