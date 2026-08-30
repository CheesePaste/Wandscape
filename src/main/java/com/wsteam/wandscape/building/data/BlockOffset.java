package com.wsteam.wandscape.building.data;

import com.google.gson.*;

import java.lang.reflect.Type;
/**
 * A relative offset in a building's structure pattern.
 * Serialized as {@code [x, y, z]} in JSON.
 */
public record BlockOffset(int x, int y, int z) {
    /** Return the key used in block_mapping (e.g. "0,0,0"). */
    public String toKey() {
        return x + "," + y + "," + z;
    }

    public static BlockOffset of(int x, int y, int z) {
        return new BlockOffset(x, y, z);
    }

    /** Gson deserializer that reads {@code [x, y, z]} arrays. */
    public static class Deserializer implements JsonDeserializer<BlockOffset> {
        @Override
        public BlockOffset deserialize(JsonElement json, Type typeOfT,
                                        JsonDeserializationContext context) throws JsonParseException {
            JsonArray arr = json.getAsJsonArray();
            if (arr.size() != 3) {
                throw new JsonParseException("BlockOffset requires exactly 3 elements [x, y, z], got " + arr.size());
            }
            return new BlockOffset(arr.get(0).getAsInt(), arr.get(1).getAsInt(), arr.get(2).getAsInt());
        }
    }
}
