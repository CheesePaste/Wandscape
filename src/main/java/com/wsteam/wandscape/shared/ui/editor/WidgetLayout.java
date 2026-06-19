package com.wsteam.wandscape.shared.ui.editor;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Serializable layout data for a single widget position.
 */
record WidgetLayout(String id, int x, int y, int width, int height) {

    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("id", id);
        obj.addProperty("x", x);
        obj.addProperty("y", y);
        obj.addProperty("width", width);
        obj.addProperty("height", height);
        return obj;
    }

    public static WidgetLayout fromJson(JsonObject obj) {
        return new WidgetLayout(
            obj.get("id").getAsString(),
            obj.get("x").getAsInt(),
            obj.get("y").getAsInt(),
            obj.get("width").getAsInt(),
            obj.get("height").getAsInt()
        );
    }

    /**
     * Full screen layout: panel size + list of widget positions.
     */
    public record ScreenLayout(String name, int panelWidth, int panelHeight, List<WidgetLayout> widgets) {

        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

        public String toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("name", name);
            JsonObject panel = new JsonObject();
            panel.addProperty("width", panelWidth);
            panel.addProperty("height", panelHeight);
            root.add("panel", panel);
            JsonArray arr = new JsonArray();
            for (WidgetLayout w : widgets) {
                arr.add(w.toJson());
            }
            root.add("widgets", arr);
            return GSON.toJson(root);
        }

        public static ScreenLayout fromJson(String json) {
            JsonObject root = GSON.fromJson(json, JsonObject.class);
            String name = root.get("name").getAsString();
            JsonObject panel = root.getAsJsonObject("panel");
            int pw = panel.get("width").getAsInt();
            int ph = panel.get("height").getAsInt();
            List<WidgetLayout> widgets = new ArrayList<>();
            JsonArray arr = root.getAsJsonArray("widgets");
            for (int i = 0; i < arr.size(); i++) {
                widgets.add(WidgetLayout.fromJson(arr.get(i).getAsJsonObject()));
            }
            return new ScreenLayout(name, pw, ph, widgets);
        }
    }
}
