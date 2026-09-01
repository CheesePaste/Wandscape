package com.wsteam.wandscape.content.building.scanner;
import com.wsteam.wandscape.content.task.component.Position;
import com.wsteam.wandscape.content.task.ecs.World;

import com.wsteam.wandscape.content.building.data.BlockOffset;
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

import javax.annotation.Nullable;
import java.util.*;

/**
 * Block entity for the Building Scanner block.
 * Stores all scanner state and syncs to client for wireframe rendering.
 */
public class CreativeScannerBlockEntity extends BlockEntity {

    private static final String TAG = "ScannerBE";

    // ── NBT keys ──
    private static final String KEY_MODE = "mode";
    private static final String KEY_BOUNDARY_MIN = "boundary_min";
    private static final String KEY_BOUNDARY_MAX = "boundary_max";
    private static final String KEY_DOOR_OFFSETS = "door_offsets";
    private static final String KEY_DOOR_OFFSET = "door_offset"; // legacy single door
    private static final String KEY_BUILDING_ID = "building_id";
    private static final String KEY_DISPLAY_NAME = "display_name";
    private static final String KEY_CREATOR = "creator";
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
    private static final String KEY_RELAX_ENERGY = "relax_energy_restore";
    private static final String KEY_RELAX_DURATION = "relax_duration";
    private static final String KEY_ATM_WITHDRAW = "atm_withdraw_amount";
    private static final String KEY_ATM_DURATION = "atm_duration";

    // ── New field NBT keys ──
    private static final String KEY_NODE_CONFIG = "node_config";
    private static final String KEY_SHOP_GOODS = "shop_goods";
    private static final String KEY_SERVICE_ELEMENT_OUTPUT = "service_element_output";

    // Sub-keys for node_config compound
    private static final String KEY_NC_BLUEPRINT = "blueprint";
    private static final String KEY_NC_ELEMENT = "element";
    private static final String KEY_NC_AMOUNT = "amount_per_harvest";
    private static final String KEY_NC_CHANNEL_TICKS = "channel_ticks";

    // Sub-keys for cost/good list entries
    private static final String KEY_ENTRY_ELEMENT = "element";
    private static final String KEY_ENTRY_AMOUNT = "amount";
    private static final String KEY_GOOD_ITEM_ID = "item_id";
    @SuppressWarnings("unused")

    public enum BlockMode { SAVE, CORNER }
    private BlockMode blockMode = BlockMode.SAVE;
    private String structureName = "";

    public enum TargetMode { BUILDING, ROAD }
    private TargetMode targetMode = TargetMode.BUILDING;

    // ── State ──
    private ScannerMode mode = ScannerMode.BOUNDARY;
    private BlockOffset boundaryMin = BlockOffset.of(0, 0, 0);
    private BlockOffset boundaryMax = BlockOffset.of(1, 1, 1);
    private final List<BlockOffset> doorOffsets = new ArrayList<>();
    private String buildingId = "";
    private String displayName = "";
    private String creator = "";
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

    // ── Relax config ──
    private int relaxEnergyRestore, relaxInteractionDurationTicks;

    // ── Atm config ──
    private int atmWithdrawAmount, atmInteractionDurationTicks;

    // ── Node config (only for category=node) ──
    private String nodeBlueprint = "";
    private String nodeElement = "earth";
    private int nodeAmountPerHarvest = 5;
    private int nodeChannelTicks = 200;

    // ── Shop goods (only for category=shop) ──
    private final List<ShopGoodData> shopGoods = new ArrayList<>();

    // ── Service element output (only for category=service) ──
    private final Map<String, Integer> serviceElementOutput = new HashMap<>();

    public CreativeScannerBlockEntity(BlockPos pos, BlockState state) {
        this(com.wsteam.wandscape.Wandscape.CREATIVE_BUILDING_SCANNER_BE.get(), pos, state);
    }

    public CreativeScannerBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    // ── Getters / Setters ──

    public BlockMode getBlockMode() { return blockMode; }
    public void setBlockMode(BlockMode bm) {
        this.blockMode = bm;
        setChangedAndSync();
    }

    public String getStructureName() { return structureName; }
    public void setStructureName(String name) {
        this.structureName = name;
        setChangedAndSync();
    }

    public TargetMode getTargetMode() { return targetMode; }
    public void setTargetMode(TargetMode tm) {
        this.targetMode = tm;
        setChangedAndSync();
    }

    /**
     * Searches for matching CORNER scanner blocks with the same structureName within 64 blocks,
     * and calculates the minimum bounding box covering this SAVE scanner and all matching CORNER scanners.
     */
    public boolean detectBoundaryFromCorners(@Nullable net.minecraft.world.level.Level level) {
        if (blockMode != BlockMode.SAVE || level == null || structureName.isBlank()) {
            return false;
        }

        BlockPos myPos = getBlockPos();
        int radius = 64;
        List<BlockPos> cornerPositions = new ArrayList<>();
        cornerPositions.add(myPos);

        BlockPos.betweenClosedStream(myPos.offset(-radius, -radius, -radius), myPos.offset(radius, radius, radius))
                .forEach(pos -> {
                    if (pos.equals(myPos)) return;
                    net.minecraft.world.level.block.entity.BlockEntity be = level.getBlockEntity(pos);
                    if (be instanceof CreativeScannerBlockEntity other) {
                        if (other.getBlockMode() == BlockMode.CORNER
                                && structureName.equalsIgnoreCase(other.getStructureName())) {
                            cornerPositions.add(pos.immutable());
                        }
                    }
                });

        if (cornerPositions.size() <= 1) {
            return false;
        }

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (BlockPos p : cornerPositions) {
            minX = Math.min(minX, p.getX());
            minY = Math.min(minY, p.getY());
            minZ = Math.min(minZ, p.getZ());
            maxX = Math.max(maxX, p.getX());
            maxY = Math.max(maxY, p.getY());
            maxZ = Math.max(maxZ, p.getZ());
        }

        BlockOffset newMin = BlockOffset.of(minX - myPos.getX(), minY - myPos.getY(), minZ - myPos.getZ());
        BlockOffset newMax = BlockOffset.of(maxX - myPos.getX(), maxY - myPos.getY(), maxZ - myPos.getZ());

        setBoundary(newMin, newMax);
        return true;
    }

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

    public void adjustBoundary(int dMinX, int dMinY, int dMinZ, int dMaxX, int dMaxY, int dMaxZ) {
        this.boundaryMin = BlockOffset.of(boundaryMin.x() + dMinX, boundaryMin.y() + dMinY, boundaryMin.z() + dMinZ);
        this.boundaryMax = BlockOffset.of(boundaryMax.x() + dMaxX, boundaryMax.y() + dMaxY, boundaryMax.z() + dMaxZ);
        setChangedAndSync();
    }

    /**
     * Scans the current 3D boundary box for all Door blocks (DoorBlock or BlockTags.DOORS),
     * returning their relative BlockOffsets from this scanner block.
     * Only counts lower halves (DoubleBlockHalf.LOWER) to avoid duplicates.
     */
    public List<BlockOffset> detectDoors(@Nullable net.minecraft.world.level.Level level) {
        if (level == null) return List.of();
        BlockPos wMin = getWorldMin();
        BlockPos wMax = getWorldMax();
        if (wMin == null || wMax == null) return List.of();

        List<BlockOffset> list = new ArrayList<>();
        BlockPos myPos = getBlockPos();

        for (BlockPos pos : BlockPos.betweenClosed(wMin, wMax)) {
            net.minecraft.world.level.block.state.BlockState st = level.getBlockState(pos);
            if (st.is(net.minecraft.tags.BlockTags.DOORS) || st.getBlock() instanceof net.minecraft.world.level.block.DoorBlock) {
                if (st.hasProperty(net.minecraft.world.level.block.DoorBlock.HALF)) {
                    if (st.getValue(net.minecraft.world.level.block.DoorBlock.HALF) != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) {
                        continue;
                    }
                }
                list.add(BlockOffset.of(pos.getX() - myPos.getX(), pos.getY() - myPos.getY(), pos.getZ() - myPos.getZ()));
            }
        }
        return list;
    }

    /** All recorded door offsets (relative to this scanner block). */
    public List<BlockOffset> getDoorOffsets() { return Collections.unmodifiableList(doorOffsets); }

    public void setDoorOffsets(@Nullable List<BlockOffset> offs) {
        doorOffsets.clear();
        if (offs != null) doorOffsets.addAll(offs);
        setChangedAndSync();
    }

    public void clearDoorOffsets() {
        doorOffsets.clear();
        setChangedAndSync();
    }

    /** Replace a single door offset in place (keeps the rest). */
    public void updateDoorOffset(int index, BlockOffset off) {
        if (index >= 0 && index < doorOffsets.size()) {
            doorOffsets.set(index, off);
            setChangedAndSync();
        }
    }

    /** First door offset, or null when none — single-door convenience for UI/edit boxes. */
    @Nullable
    public BlockOffset getDoorOffset() { return doorOffsets.isEmpty() ? null : doorOffsets.get(0); }

    public String getBuildingId() { return buildingId; }
    public void setBuildingId(String id) { this.buildingId = id; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String name) { this.displayName = name; }

    public String getCreator() { return creator; }
    public void setCreator(String value) { this.creator = value; }

    public String getCategory() { return category; }
    public void setCategory(String cat) { this.category = cat; }

    /**
     * 导出保真分级。默认 false（创造扫描器）：完整导出方块/实体 NBT，供创作者编辑与分享。
     * 生存扫描器覆写为 true：导出为"纯建筑"——不携带任何可复制物品的 NBT（容器内容、
     * 展示框内物品），从导出来源上堵死用建筑扫描器打印刷物品的路径。
     * 只有创造扫描器位于创造栏、生存玩家无法获得，故生存导出的无损保真才有保障。
     */
    public boolean isSafeExport() { return false; }

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

    // ── Relax config ──

    public int getRelaxEnergyRestore() { return relaxEnergyRestore; }
    public void setRelaxEnergyRestore(int v) { this.relaxEnergyRestore = Math.max(0, v); }
    public int getRelaxInteractionDurationTicks() { return relaxInteractionDurationTicks; }
    public void setRelaxInteractionDurationTicks(int v) { this.relaxInteractionDurationTicks = Math.max(0, v); }

    // ── Atm config ──

    public int getAtmWithdrawAmount() { return atmWithdrawAmount; }
    public void setAtmWithdrawAmount(int v) { this.atmWithdrawAmount = Math.max(0, v); }
    public int getAtmInteractionDurationTicks() { return atmInteractionDurationTicks; }
    public void setAtmInteractionDurationTicks(int v) { this.atmInteractionDurationTicks = Math.max(0, v); }

    // ── Node config ──

    public String getNodeBlueprint() { return nodeBlueprint; }
    public void setNodeBlueprint(String v) { this.nodeBlueprint = v; }
    public String getNodeElement() { return nodeElement; }
    public void setNodeElement(String v) { this.nodeElement = v; }
    public int getNodeAmountPerHarvest() { return nodeAmountPerHarvest; }
    public void setNodeAmountPerHarvest(int v) { this.nodeAmountPerHarvest = v; }
    public int getNodeChannelTicks() { return nodeChannelTicks; }
    public void setNodeChannelTicks(int v) { this.nodeChannelTicks = v; }

    // ── Shop goods ──

    public List<ShopGoodData> getShopGoods() { return Collections.unmodifiableList(shopGoods); }
    public void addShopGood(ShopGoodData good) { shopGoods.add(good); }
    public void removeShopGood(int index) {
        if (index >= 0 && index < shopGoods.size()) shopGoods.remove(index);
    }
    public void updateShopGood(int index, ShopGoodData good) {
        if (index >= 0 && index < shopGoods.size()) shopGoods.set(index, good);
    }
    public void clearShopGoods() { shopGoods.clear(); }

    // ── Service element output ──

    public Map<String, Integer> getServiceElementOutput() { return Collections.unmodifiableMap(serviceElementOutput); }
    public void setServiceElementOutput(Map<String, Integer> map) {
        serviceElementOutput.clear();
        if (map != null) serviceElementOutput.putAll(map);
    }
    public void addServiceElementOutput(String element, int amount) {
        serviceElementOutput.put(element, amount);
    }
    public void removeServiceElementOutput(String element) {
        serviceElementOutput.remove(element);
    }

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

    /** World-space position of the first door (or null). */
    @Nullable
    public BlockPos getWorldDoor() {
        return doorOffsets.isEmpty()
                ? null
                : worldPosition.offset(doorOffsets.get(0).x(), doorOffsets.get(0).y(), doorOffsets.get(0).z());
    }

    // ── NBT ──

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("blockMode", blockMode.name());
        tag.putString("structureName", structureName);
        tag.putString("targetMode", targetMode.name());
        tag.putString(KEY_MODE, mode.name());
        writeOffsetArray(tag, KEY_BOUNDARY_MIN, boundaryMin);
        writeOffsetArray(tag, KEY_BOUNDARY_MAX, boundaryMax);
        if (!doorOffsets.isEmpty()) {
            ListTag doorList = new ListTag();
            for (BlockOffset off : doorOffsets) {
                doorList.add(new net.minecraft.nbt.IntArrayTag(
                        new int[]{off.x(), off.y(), off.z()}));
            }
            tag.put(KEY_DOOR_OFFSETS, doorList);
        }
        tag.putString(KEY_BUILDING_ID, buildingId);
        tag.putString(KEY_DISPLAY_NAME, displayName);
        tag.putString(KEY_CREATOR, creator);
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
        tag.putInt(KEY_RELAX_ENERGY, relaxEnergyRestore);
        tag.putInt(KEY_RELAX_DURATION, relaxInteractionDurationTicks);
        tag.putInt(KEY_ATM_WITHDRAW, atmWithdrawAmount);
        tag.putInt(KEY_ATM_DURATION, atmInteractionDurationTicks);

        // Node config
        CompoundTag ncTag = new CompoundTag();
        ncTag.putString(KEY_NC_BLUEPRINT, nodeBlueprint);
        ncTag.putString(KEY_NC_ELEMENT, nodeElement);
        ncTag.putInt(KEY_NC_AMOUNT, nodeAmountPerHarvest);
        ncTag.putInt(KEY_NC_CHANNEL_TICKS, nodeChannelTicks);
        tag.put(KEY_NODE_CONFIG, ncTag);

        // Shop goods
        ListTag goodsList = new ListTag();
        for (ShopGoodData good : shopGoods) {
            CompoundTag gt = new CompoundTag();
            gt.putString(KEY_GOOD_ITEM_ID, good.itemId());
            gt.putInt("comfort", good.comfort());
            gt.putInt("magic", good.magic());
            gt.putInt("wonder", good.wonder());
            goodsList.add(gt);
        }
        tag.put(KEY_SHOP_GOODS, goodsList);

        // Service element output
        ListTag seoList = new ListTag();
        for (var entry : serviceElementOutput.entrySet()) {
            CompoundTag et = new CompoundTag();
            et.putString(KEY_ENTRY_ELEMENT, entry.getKey());
            et.putInt(KEY_ENTRY_AMOUNT, entry.getValue());
            seoList.add(et);
        }
        tag.put(KEY_SERVICE_ELEMENT_OUTPUT, seoList);
    }

    @Override
    public void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("blockMode")) {
            try {
                blockMode = BlockMode.valueOf(tag.getString("blockMode"));
            } catch (Exception e) {
                blockMode = BlockMode.SAVE;
            }
        }
        if (tag.contains("structureName")) {
            structureName = tag.getString("structureName");
        }
        if (tag.contains("targetMode")) {
            try {
                targetMode = TargetMode.valueOf(tag.getString("targetMode"));
            } catch (Exception e) {
                targetMode = TargetMode.BUILDING;
            }
        }
        try {
            mode = ScannerMode.valueOf(tag.getString(KEY_MODE));
        } catch (Exception e) {
            mode = ScannerMode.BOUNDARY;
        }
        boundaryMin = readOffsetArray(tag, KEY_BOUNDARY_MIN);
        boundaryMax = readOffsetArray(tag, KEY_BOUNDARY_MAX);
        doorOffsets.clear();
        if (tag.contains(KEY_DOOR_OFFSETS, Tag.TAG_LIST)) {
            ListTag doorList = tag.getList(KEY_DOOR_OFFSETS, Tag.TAG_INT_ARRAY);
            for (int i = 0; i < doorList.size(); i++) {
                int[] arr = doorList.getIntArray(i);
                if (arr.length == 3) {
                    doorOffsets.add(BlockOffset.of(arr[0], arr[1], arr[2]));
                }
            }
        } else if (tag.contains(KEY_DOOR_OFFSET, Tag.TAG_INT_ARRAY)) {
            doorOffsets.add(readOffsetArray(tag, KEY_DOOR_OFFSET));
        }
        buildingId = tag.getString(KEY_BUILDING_ID);
        displayName = tag.getString(KEY_DISPLAY_NAME);
        creator = tag.getString(KEY_CREATOR);
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
        relaxEnergyRestore = tag.getInt(KEY_RELAX_ENERGY);
        relaxInteractionDurationTicks = tag.getInt(KEY_RELAX_DURATION);
        atmWithdrawAmount = tag.getInt(KEY_ATM_WITHDRAW);
        atmInteractionDurationTicks = tag.getInt(KEY_ATM_DURATION);

        // Node config
        if (tag.contains(KEY_NODE_CONFIG, Tag.TAG_COMPOUND)) {
            CompoundTag ncTag = tag.getCompound(KEY_NODE_CONFIG);
            nodeBlueprint = ncTag.getString(KEY_NC_BLUEPRINT);
            nodeElement = ncTag.contains(KEY_NC_ELEMENT) ? ncTag.getString(KEY_NC_ELEMENT) : "earth";
            nodeAmountPerHarvest = ncTag.getInt(KEY_NC_AMOUNT);
            nodeChannelTicks = ncTag.getInt(KEY_NC_CHANNEL_TICKS);
        }

        // Shop goods
        shopGoods.clear();
        if (tag.contains(KEY_SHOP_GOODS, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_SHOP_GOODS, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag gt = list.getCompound(i);
                shopGoods.add(new ShopGoodData(
                        gt.getString(KEY_GOOD_ITEM_ID),
                        gt.getInt("comfort"),
                        gt.getInt("magic"),
                        gt.getInt("wonder")));
            }
        }

        // Service element output
        serviceElementOutput.clear();
        if (tag.contains(KEY_SERVICE_ELEMENT_OUTPUT, Tag.TAG_LIST)) {
            ListTag list = tag.getList(KEY_SERVICE_ELEMENT_OUTPUT, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag et = list.getCompound(i);
                serviceElementOutput.put(et.getString(KEY_ENTRY_ELEMENT), et.getInt(KEY_ENTRY_AMOUNT));
            }
        }
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

    // ── Shop good data record ──

    public record ShopGoodData(
            String itemId,
            int comfort,
            int magic,
            int wonder
    ) {
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
