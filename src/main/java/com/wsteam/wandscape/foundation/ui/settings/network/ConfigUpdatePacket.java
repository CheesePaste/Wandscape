package com.wsteam.wandscape.foundation.ui.settings.network;

import com.wsteam.wandscape.Config;
import com.wsteam.wandscape.foundation.log.Log;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Arrays;
import java.util.List;

import static com.wsteam.wandscape.Wandscape.MODID;

/**
 * Client->Server: Requests updating a common config value.
 * Validates player permissions (OP level 2 or singleplayer host) before applying.
 */
public record ConfigUpdatePacket(String path, String value) implements CustomPacketPayload {

    private static final String TAG = "ConfigUpdatePacket";

    public static final Type<ConfigUpdatePacket> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(MODID, "config_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ConfigUpdatePacket> STREAM_CODEC =
            StreamCodec.of(ConfigUpdatePacket::write, ConfigUpdatePacket::read);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    static void write(RegistryFriendlyByteBuf buf, ConfigUpdatePacket pkt) {
        buf.writeUtf(pkt.path);
        buf.writeUtf(pkt.value);
    }

    static ConfigUpdatePacket read(RegistryFriendlyByteBuf buf) {
        return new ConfigUpdatePacket(buf.readUtf(), buf.readUtf());
    }

    public static void handleServer(ConfigUpdatePacket packet, ServerPlayer player) {
        if (player == null || player.isRemoved()) return;

        boolean isOp = player.hasPermissions(2)
                || (player.getServer() != null && player.getServer().isSingleplayerOwner(player.getGameProfile()));
        if (!isOp) {
            Log.warn(TAG, "Player {} attempted to modify config {} without permissions",
                    player.getName().getString(), packet.path);
            return;
        }

        boolean applied = applyConfig(packet.path, packet.value);
        if (applied) {
            Config.SPEC.save();
            Log.info(TAG, "Config {} updated to {} by player {}",
                    packet.path, packet.value, player.getName().getString());
            PacketDistributor.sendToAllPlayers(new ConfigSyncPacket(packet.path, packet.value));
        }
    }

    public static boolean applyConfig(String path, String value) {
        try {
            if (path.startsWith("building.package.")) {
                String packId = path.substring("building.package.".length());
                boolean enabled = Boolean.parseBoolean(value);
                Config.setPackageEnabled(packId, enabled);
                return true;
            }
            switch (path) {
                case "building.disabledPackages" -> {
                    List<String> list = value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
                    Config.setDisabledPackages(list);
                    return true;
                }
                case "general.debug" -> {
                    boolean val = Boolean.parseBoolean(value);
                    Config.DEBUG.set(val);
                    com.wsteam.wandscape.foundation.log.LogConfig.setRootLevel(
                            val ? com.wsteam.wandscape.foundation.log.LogLevel.DEBUG
                                : com.wsteam.wandscape.foundation.log.LogLevel.INFO);
                    return true;
                }
                case "colony.offlineIncomeMultiplier" -> {
                    Config.COLONY_OFFLINE_INCOME_MULTIPLIER.set(Double.parseDouble(value));
                    return true;
                }
                case "warehouse.itemCapacity" -> {
                    Config.WAREHOUSE_ITEM_CAPACITY.set(Integer.parseInt(value));
                    return true;
                }
                case "element.autoGatherOnShortage" -> {
                    Config.AUTO_GATHER_ON_ELEMENT_SHORTAGE.set(Boolean.parseBoolean(value));
                    return true;
                }
                case "element.decomposeDivisor" -> {
                    Config.ELEMENT_DECOMPOSE_DIVISOR.set(Double.parseDouble(value));
                    return true;
                }
                case "element.craftCostMultiplier" -> {
                    Config.ELEMENT_CRAFT_COST_MULTIPLIER.set(Double.parseDouble(value));
                    return true;
                }
                case "tavern.recruitCostPerElement" -> {
                    Config.TAVERN_RECRUIT_COST_PER_ELEMENT.set(Integer.parseInt(value));
                    return true;
                }
                case "tourist.spawnEnabled" -> {
                    Config.TOURIST_SPAWN_ENABLED.set(Boolean.parseBoolean(value));
                    return true;
                }
                case "tourist.maxPerColony" -> {
                    Config.TOURIST_MAX_PER_COLONY.set(Integer.parseInt(value));
                    return true;
                }
                case "tourist.baseSpawnCount" -> {
                    Config.TOURIST_BASE_SPAWN_COUNT.set(Integer.parseInt(value));
                    return true;
                }
                case "tourist.stayMinDays" -> {
                    Config.TOURIST_STAY_MIN_DAYS.set(Integer.parseInt(value));
                    return true;
                }
                case "tourist.stayMaxDays" -> {
                    Config.TOURIST_STAY_MAX_DAYS.set(Integer.parseInt(value));
                    return true;
                }
                case "tourist.baseWallet" -> {
                    Config.TOURIST_BASE_WALLET.set(Integer.parseInt(value));
                    return true;
                }
                case "tourist.maxEnergy" -> {
                    Config.TOURIST_MAX_ENERGY.set(Integer.parseInt(value));
                    return true;
                }
                case "particle.level" -> {
                    Config.PARTICLE_LEVEL.set(value);
                    return true;
                }
                case "building.noSpawnInBuildingArea" -> {
                    Config.BUILDING_NO_SPAWN_IN_AREA.set(Boolean.parseBoolean(value));
                    return true;
                }
                case "npc.friendlyFireProtection" -> {
                    Config.NPC_FRIENDLY_FIRE_PROTECTION.set(Boolean.parseBoolean(value));
                    return true;
                }
                case "npc.deathMessageGlobal" -> {
                    Config.NPC_DEATH_MESSAGE_GLOBAL.set(Boolean.parseBoolean(value));
                    return true;
                }
                case "npc.pvp" -> {
                    Config.PVP.set(Boolean.parseBoolean(value));
                    return true;
                }
                default -> {
                    Log.warn(TAG, "Unknown config path: {}", path);
                    return false;
                }
            }
        } catch (Exception e) {
            Log.warn(TAG, "Failed to apply config {}={}: {}", path, value, e.getMessage());
            return false;
        }
    }
}
