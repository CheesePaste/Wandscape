package com.wsteam.wandscape.building.scanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import com.wsteam.wandscape.building.data.BlockOffset;
import com.wsteam.wandscape.building.data.BuildingConfig.BoundaryBox;
import com.wsteam.wandscape.shared.log.Log;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Building Scanner block.
 * Stores all scanner state and syncs to client for wireframe rendering.
 */
public class BuildingScannerBlockEntity extends BlockEntity {

    private static final String TAG = "ScannerBE";

    // ── NBT keys ──
    private static final String KEY_MODE = "mode";
    private static final String KEY_BOUNDARY_MIN = "boundary_min";
    private static final String KEY_BOUNDARY_MAX = "boundary_max";
    private static final String KEY_DOOR_OFFSET = "door_offset";
    private static final String KEY_INTERACT_ZONES = "interact_zones";
    private static final String KEY_ZONE_MIN = "min";
    private static final String KEY_ZONE_MAX = "max";
    private static final String KEY_BUILDING_ID = "building_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_CATEGORY = "category";
    private static final String KEY_COMFORT = "comfort";
    private static final String KEY_MAGIC = "magic";
    private static final String KEY_WONDER = "wonder";
    private static final String KEY_SCANNED = "scanned";
    private static final String KEY_UNLOCK_LEVEL = "unlock_min_level";
    private static final String KEY_SHOP_PROFIT = "shop_profit";
    private static final String KEY_SHOP_DURATION = "shop_duration";
    private static final String KEY_SERVICE_ENERGY = "service_energy";
    private static final String KEY_SERVICE_MAX_OCC = "service_max_occ";
    private static final String KEY_SERVICE_DURATION = "service_duration";

    // ── State ──
    private ScannerMode mode = ScannerMode.BOUNDARY;
    private BlockOffset boundaryMin = BlockOffset.of(0, 0, 0);
    private BlockOffset boundaryMax = BlockOffset.of(1, 1, 1);
    @Nullable
    private BlockOffset doorOffset = null;
    private final List<BoundaryBox> interactZones = new ArrayList<>();
    private String buildingId = "";
    private String displayName = "";
    private String category = "basic";
    private int comfort, magic, wonder;
    /** Whether blocks have been scanned for pattern/mapping. */
    private boolean scanned;

    // ── Unlock requirement ──
    private int unlockMinLevel = 1;

    // ── Shop config ──
    private double shopProfitRate;
    private int shopInteractionDurationTicks;

    // ── Service config ──
    private int serviceEnergyPerUse, serviceMaxOccupancy, serviceInteractionDurationTicks;

    public BuildingScannerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Getters / Setters ──

    public ScannerMode getMode() { return mode; }
    public void setMode(ScannerMode m) {
        this.mode = m;
        setChangedAndSync();
    }

    public BlockOffset getBoundaryMin() { return boundaryMin; }
    public BlockOffset getBoundaryMax() { return boundaryMax; }
    public void setBoundary(BlockOffset min, BlockOffset max) {
        this.boundaryMin = min;
        this.boundaryMax = max;
        setChangedAndSync();
    }

    @Nullable
    public BlockOffset getDoorOffset() { return doorOffset; }
    public void setDoorOffset(@Nullable BlockOffset off) {
        this.doorOffset = off;
        setChangedAndSync();
    }

    public List<BoundaryBox> getInteractZones() {
        return Collections.unmodifiableList(interactZones);
    }
    public void addInteractZone(BoundaryBox zone) {
        interactZones.add(zone);
        setChangedAndSync();
    }
    public void removeInteractZone(int index) {
        if (index >= 0 && index < interactZones.size()) {
            interactZones.remove(index);
            setChangedAndSync();
        }
    }
    public void updateInteractZone(int index, BoundaryBox zone) {
        if (index >= 0 && index < interactZones.size()) {
            interactZones.set(index, zone);
            setChangedAndSync();
        }
    }
    public void clearInteractZones() {
        interactZones.clear();
        setChangedAndSync();
    }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String id) { this.buildingId = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String name) { this.displayName = name; }

    public String getCategory() { return category; }
    public void setCategory(String cat) { this.category = cat; }

    public int getComfort() { return comfort; }
    public void setComfort(int v) { this.comfort = v; }
    public int getMagic() { return magic; }
    public void setMagic(int v) { this.magic = v; }
    public int getWonder() { return wonder; }
    public void setWonder(int v) { this.wonder = v; }

    public boolean isScanned() { return scanned; }
    public void setScanned(boolean v) { this.scanned = v; }

    // ── Unlock requirement ──

    public int getUnlockMinLevel() { return unlockMinLevel; }
    public void setUnlockMinLevel(int v) { this.unlockMinLevel = Math.max(1, v); }

    // ── Shop config ──

    public double getShopProfitRate() { return shopProfitRate; }
    public void setShopProfitRate(double v) { this.shopProfitRate = v; }
    public int getShopInteractionDurationTicks() { return shopInteractionDurationTicks; }
    public void setShopInteractionDurationTicks(int v) { this.shopInteractionDurationTicks = v; }

    // ── Service config ──

    public int getServiceEnergyPerUse() { return serviceEnergyPerUse; }
    public void setServiceEnergyPerUse(int v) { this.serviceEnergyPerUse = v; }
    public int getServiceMaxOccupancy() { return serviceMaxOccupancy; }
    public void setServiceMaxOccupancy(int v) { this.serviceMaxOccupancy = v; }
    public int getServiceInteractionDurationTicks() { return serviceInteractionDurationTicks; }
    public void setServiceInteractionDurationTicks(int v) { this.serviceInteractionDurationTicks = v; }

    /** Mark all meta fields dirty and sync to client. Call after batch updates. */
    public void syncMeta() {
        setChangedAndSync();
    }

    // ── BlockPos helpers (world-space) ──

    /** World-space min corner of the boundary. */
    public BlockPos getWorldMin() {
        return worldPosition.offset(boundaryMin.x(), boundaryMin.y(), boundaryMin.z());
    }

    /** World-space max corner of the boundary. */
    public BlockPos getWorldMax() {
        return worldPosition.offset(boundaryMax.x(), boundaryMax.y(), boundaryMax.z());
    }

    /** World-space door position (or null). */
    @Nullable
    public BlockPos getWorldDoor() {
        return doorOffset != null
                ? worldPosition.offset(doorOffset.x(), doorOffset.y(), doorOffset.z())
                : null;
    }

    // ── NBT ──

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(KEY_MODE, mode.name());
        writeOffsetArray(tag, KEY_BOUNDARY_MIN, boundaryMin);
        writeOffsetArray(tag, KEY_BOUNDARY_MAX, boundaryMax);
        if (doorOffset != null) {
            writeOffsetArray(tag, KEY_DOOR_OFFSET, doorOffset);
        }
        // Interact zones
        ListTag zonesTag = new ListTag();
        for (BoundaryBox zone : interactZones) {
            CompoundTag zt = new CompoundTag();
            writeOffsetArray(zt, KEY_ZONE_MIN, zone.min());
            writeOffsetArray(zt, KEY_ZONE_MAX, zone.max());
            zonesTag.add(zt);
        }
        tag.put(KEY_INTERACT_ZONES, zonesTag);
        tag.putString(KEY_BUILDING_ID, buildingId);
        tag.putString(KEY_DISPLAY_NAME, displayName);
        tag.putString(KEY_CATEGORY, category);
        tag.putInt(KEY_COMFORT, comfort);
        tag.putInt(KEY_MAGIC, magic);
        tag.putInt(KEY_WONDER, wonder);
        tag.putBoolean(KEY_SCANNED, scanned);
        tag.putInt(KEY_UNLOCK_LEVEL, unlockMinLevel);
        tag.putDouble(KEY_SHOP_PROFIT, shopProfitRate);
        tag.putInt(KEY_SHOP_DURATION, shopInteractionDurationTicks);
        tag.putInt(KEY_SERVICE_ENERGY, serviceEnergyPerUse);
        tag.putInt(KEY_SERVICE_MAX_OCC, serviceMaxOccupancy);
        tag.putInt(KEY_SERVICE_DURATION, serviceInteractionDurationTicks);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        try {
            mode = ScannerMode.valueOf(tag.getString(KEY_MODE));
        } catch (Exception e) {
            mode = ScannerMode.BOUNDARY;
        }
        boundaryMin = readOffsetArray(tag, KEY_BOUNDARY_MIN);
        boundaryMax = readOffsetArray(tag, KEY_BOUNDARY_MAX);
        if (tag.contains(KEY_DOOR_OFFSET, Tag.TAG_INT_ARRAY)) {
            doorOffset = readOffsetArray(tag, KEY_DOOR_OFFSET);
        } else {
            doorOffset = null;
        }
        interactZones.clear();
        if (tag.contains(KEY_INTERACT_ZONES, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_INTERACT_ZONES, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag zt = list.getCompound(i);
                BlockOffset min = readOffsetArray(zt, KEY_ZONE_MIN);
                BlockOffset max = readOffsetArray(zt, KEY_ZONE_MAX);
                interactZones.add(new BoundaryBox(min, max));
            }
        }
        buildingId = tag.getString(KEY_BUILDING_ID);
        displayName = tag.getString(KEY_DISPLAY_NAME);
        category = tag.contains(KEY_CATEGORY) ? tag.getString(KEY_CATEGORY) : "basic";
        comfort = tag.getInt(KEY_COMFORT);
        magic = tag.getInt(KEY_MAGIC);
        wonder = tag.getInt(KEY_WONDER);
        scanned = tag.getBoolean(KEY_SCANNED);
        unlockMinLevel = Math.max(1, tag.getInt(KEY_UNLOCK_LEVEL));
        shopProfitRate = tag.getDouble(KEY_SHOP_PROFIT);
        shopInteractionDurationTicks = tag.getInt(KEY_SHOP_DURATION);
        serviceEnergyPerUse = tag.getInt(KEY_SERVICE_ENERGY);
        serviceMaxOccupancy = tag.getInt(KEY_SERVICE_MAX_OCC);
        serviceInteractionDurationTicks = tag.getInt(KEY_SERVICE_DURATION);
    }

    // ── Client sync ──

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, registries);
        return tag;
    }

    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        loadAdditional(tag, registries);
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ── Helpers ──

    private void setChangedAndSync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 3);
        }
    }

    private static void writeOffsetArray(CompoundTag tag, String key, BlockOffset off) {
        tag.putIntArray(key, new int[]{off.x(), off.y(), off.z()});
    }

    private static BlockOffset readOffsetArray(CompoundTag tag, String key) {
        int[] arr = tag.getIntArray(key);
        if (arr.length == 3) {
            return BlockOffset.of(arr[0], arr[1], arr[2]);
        }
        return BlockOffset.of(0, 0, 0);
    }
}
