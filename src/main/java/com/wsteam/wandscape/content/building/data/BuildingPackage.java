package com.wsteam.wandscape.content.building.data;

import com.google.gson.JsonObject;
import net.minecraft.network.RegistryFriendlyByteBuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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
