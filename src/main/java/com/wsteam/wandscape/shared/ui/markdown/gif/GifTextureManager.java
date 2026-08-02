package com.wsteam.wandscape.shared.ui.markdown.gif;

import com.wsteam.wandscape.shared.ui.markdown.gif.GifDecoder.GifAnimation;
import com.wsteam.wandscape.shared.ui.markdown.gif.GifDecoder.GifFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Texture manager for dynamic GIF animations in Minecraft Wandscape UI.
 */
public final class GifTextureManager {

    private static final Map<ResourceLocation, LoadedGif> CACHE = new ConcurrentHashMap<>();

    private record LoadedGif(List<ResourceLocation> frameLocations, GifAnimation animation, long startTimeMs) {}

    private GifTextureManager() {}

    /**
     * Get active frame ResourceLocation for a GIF resource location.
     * Returns the original location if it's not a GIF or failed to load.
     */
    public static ResourceLocation getActiveFrameTexture(ResourceLocation gifLocation) {
        if (!gifLocation.getPath().endsWith(".gif")) {
            return gifLocation;
        }

        LoadedGif loaded = CACHE.computeIfAbsent(gifLocation, GifTextureManager::loadGif);
        if (loaded == null || loaded.animation() == null || loaded.frameLocations().isEmpty()) {
            return gifLocation;
        }

        long now = System.currentTimeMillis();
        long elapsed = now - loaded.startTimeMs();
        GifFrame currentFrame = loaded.animation().getFrameAt(elapsed);

        if (currentFrame != null) {
            int frameIdx = loaded.animation().frames().indexOf(currentFrame);
            if (frameIdx >= 0 && frameIdx < loaded.frameLocations().size()) {
                return loaded.frameLocations().get(frameIdx);
            }
        }
        return loaded.frameLocations().get(0);
    }

    private static LoadedGif loadGif(ResourceLocation loc) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                return null;
            }

            try (InputStream is = res.get().open()) {
                GifAnimation anim = GifDecoder.decode(is);
                if (anim == null || anim.frames().isEmpty()) {
                    return null;
                }

                List<ResourceLocation> frameLocs = new ArrayList<>();
                int idx = 0;
                for (GifFrame frame : anim.frames()) {
                    ResourceLocation frameLoc = ResourceLocation.fromNamespaceAndPath(
                            loc.getNamespace(),
                            loc.getPath().replace(".gif", "_frame_" + idx + ".png")
                    );
                    DynamicTexture dynTex = new DynamicTexture(frame.image());
                    Minecraft.getInstance().getTextureManager().register(frameLoc, dynTex);
                    frameLocs.add(frameLoc);
                    idx++;
                }
                return new LoadedGif(frameLocs, anim, System.currentTimeMillis());
            }
        } catch (Exception e) {
            return null;
        }
    }
}
