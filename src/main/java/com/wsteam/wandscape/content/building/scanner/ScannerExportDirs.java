package com.wsteam.wandscape.content.building.scanner;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.wsteam.wandscape.content.building.data.BuildingPackage;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.SharedConstants;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.PackType;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 扫描器导出落盘目录的<b>唯一事实源</b>。
 *
 * <p>导出物固定写进<b>世界数据包</b> {@code <world>/datapacks/<pack>/data/wandscape/<category>/}：
 * 世界数据包每次启动都会自动加载，玩家在游戏里导出的建筑/道路退出重进后依然在
 * （开发环境写源码目录会被 {@code build/resources/main} 盖掉，所以不走那条路）。
 *
 * <p>{@link #ensureSkeleton} 在服务器启动时把两个包的空骨架连 {@code pack.mcmeta} 一起建出来——
 * 哪怕一条内容都没有。玩家因此不必去猜目录，进存档就能看见该往哪儿放文件。
 */
public final class ScannerExportDirs {
    private static final String TAG = "ScannerExportDirs";

    /** 建筑导出包（世界数据包名）。 */
    public static final String PACK_BUILDINGS = "wandscape_builds";
    /** 道路预设导出包（世界数据包名）。 */
    public static final String PACK_ROADS = "wandscape_roads";

    /** 建筑在数据包里的 category 目录名。 */
    public static final String CATEGORY_BUILDINGS = "buildings";
    /** 道路预设在数据包里的 category 目录名。 */
    public static final String CATEGORY_ROAD_PRESETS = "road_presets";

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ScannerExportDirs() {}

    /** 某个导出包在指定世界里的根目录，例：{@code <world>/datapacks/wandscape_builds}。 */
    public static Path packRoot(MinecraftServer server, String pack) {
        return server.getWorldPath(LevelResource.DATAPACK_DIR).resolve(pack);
    }

    /**
     * 某个 category 在该包里的导出目录，
     * 例：{@code <world>/datapacks/wandscape_builds/data/wandscape/buildings}。
     */
    public static Path categoryDir(MinecraftServer server, String pack, String category) {
        return packRoot(server, pack).resolve("data/wandscape/" + category);
    }

    /**
     * 写出 {@code pack.mcmeta}，让这个文件夹成为游戏能识别并自动启用的数据包。
     * 没有它，游戏会完全忽略该文件夹。
     */
    public static void ensurePackMeta(Path packRoot) throws IOException {
        Path metaFile = packRoot.resolve("pack.mcmeta");
        if (Files.exists(metaFile)) return;
        Files.createDirectories(packRoot);
        int format = SharedConstants.getCurrentVersion().getPackVersion(PackType.SERVER_DATA);
        JsonObject pack = new JsonObject();
        pack.addProperty("pack_format", format);
        pack.addProperty("description", "Wandscape exported buildings & road presets");
        JsonObject root = new JsonObject();
        root.add("pack", pack);
        Files.writeString(metaFile, GSON.toJson(root));
    }

    /**
     * 取 category 导出目录，并保证它是个<b>已生效</b>的数据包：{@code pack.mcmeta} 与目录都建好。
     * 导出前调一次，就不依赖骨架是否成功建过。
     */
    public static Path prepareCategoryDir(MinecraftServer server, String pack, String category) throws IOException {
        ensurePackMeta(packRoot(server, pack));
        Path dir = categoryDir(server, pack, category);
        Files.createDirectories(dir);
        return dir;
    }

    /**
     * 建出两个导出包的空骨架并登记自定义包。
     *
     * <p>建的东西：两个包的 {@code pack.mcmeta}、各自的 category 目录，以及自定义包目录下的
     * {@code package.json}（玩家可以直接照抄当模板）。<b>幂等且永不覆盖</b>——已存在的文件一律不碰，
     * 玩家改过的 {@code package.json} 不会被冲掉；整段包在 try 里，任何失败只 {@code Log.warn}，
     * 绝不让服务器起不来。
     */
    public static void ensureSkeleton(MinecraftServer server) {
        if (server == null) return;
        try {
            Path buildingsDir = prepareCategoryDir(server, PACK_BUILDINGS, CATEGORY_BUILDINGS);
            prepareCategoryDir(server, PACK_ROADS, CATEGORY_ROAD_PRESETS);

            // 建筑那侧再往下建一层自定义包目录，玩家一眼看到该往哪放建筑 json
            Path customPkgDir = buildingsDir.resolve(BuildingPackage.CUSTOM_ID);
            Files.createDirectories(customPkgDir);
            writeCustomPackageTemplate(customPkgDir.resolve("package.json"));

            Log.info(TAG, "Export skeleton ready under {}", packRoot(server, PACK_BUILDINGS).getParent());
        } catch (Exception e) {
            Log.warn(TAG, "Failed to prepare scanner export skeleton", e);
        }
    }

    /**
     * 写自定义包的元数据模板。{@code name}/{@code description} 存的是 lang key 而非中文原文
     * ——与随 jar 发布的 {@code default} 包同构，读它的界面走 {@code I18n} 解析，中英各显示各的。
     */
    private static void writeCustomPackageTemplate(Path pkgJsonFile) throws IOException {
        if (Files.exists(pkgJsonFile)) return;
        BuildingPackage pkg = BuildingPackage.customPackage();
        JsonObject obj = new JsonObject();
        obj.addProperty("id", pkg.id());
        obj.addProperty("name", pkg.name());
        obj.addProperty("description", pkg.description());
        obj.addProperty("author", pkg.author());
        obj.addProperty("version", pkg.version());
        obj.addProperty("icon", pkg.iconItem());
        obj.addProperty("priority", pkg.priority());
        Files.writeString(pkgJsonFile, GSON.toJson(obj));
    }
}
