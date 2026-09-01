package com.wsteam.wandscape.foundation.ui.markdown.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.wsteam.wandscape.foundation.ui.markdown.gif.GifDecoder;
import com.wsteam.wandscape.foundation.ui.markdown.gif.GifDecoder.GifAnimation;
import com.wsteam.wandscape.foundation.ui.markdown.gif.GifDecoder.GifFrame;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Universal Texture Manager for Wandscape Markdown UI.
 * Seamlessly supports PNG, GIF, JPG, JPEG, and BMP image formats.
 */
public final class MarkdownTextureManager {

    private static final Map<ResourceLocation, LoadedImage> CACHE = new ConcurrentHashMap<>();

    private record LoadedImage(
            List<ResourceLocation> frameLocations,
            GifAnimation animation,
            long startTimeMs
    ) {}

    private MarkdownTextureManager() {}

    /**
     * Resolve active texture location for rendering in MarkdownRenderWidget.
     * Automatically handles PNG, GIF animations, and JPG/JPEG/BMP auto-conversions.
     */
    public static ResourceLocation getActiveTexture(ResourceLocation location) {
        if (location == null) {
            return null;
        }

        String path = location.getPath().toLowerCase();

        // 1. GIF animations
        if (path.endsWith(".gif")) {
            LoadedImage loaded = CACHE.computeIfAbsent(location, MarkdownTextureManager::loadGif);
            if (loaded != null && loaded.animation() != null && !loaded.frameLocations().isEmpty()) {
                long now = System.currentTimeMillis();
                long elapsed = now - loaded.startTimeMs();
                GifFrame frame = loaded.animation().getFrameAt(elapsed);
                if (frame != null) {
                    int idx = loaded.animation().frames().indexOf(frame);
                    if (idx >= 0 && idx < loaded.frameLocations().size()) {
                        return loaded.frameLocations().get(idx);
                    }
                }
                return loaded.frameLocations().get(0);
            }
            return location;
        }

        // 2. JPG / JPEG / BMP formats -> auto decode & register as DynamicTexture
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".bmp")) {
            LoadedImage loaded = CACHE.computeIfAbsent(location, MarkdownTextureManager::loadNonPngImage);
            if (loaded != null && !loaded.frameLocations().isEmpty()) {
                return loaded.frameLocations().get(0);
            }
            return location;
        }

        // 3. PNG (Standard MC texture with auto fallback for non-standard/disguised formats)
        LoadedImage loaded = CACHE.computeIfAbsent(location, loc -> {
            try {
                Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
                if (res.isPresent()) {
                    try (InputStream is = res.get().open()) {
                        byte[] header = is.readNBytes(8);
                        // PNG magic header: 0x89 'P' 'N' 'G' '\r' '\n' 0x1A '\n'
                        if (header.length >= 8 && header[0] == (byte) 0x89 && header[1] == (byte) 'P'
                                && header[2] == (byte) 'N' && header[3] == (byte) 'G') {
                            return new LoadedImage(List.of(loc), null, System.currentTimeMillis());
                        }
                    }
                }
                // Disguised format (e.g. JPEG saved as PNG): fallback decode via ImageIO
                return loadNonPngImage(loc);
            } catch (Exception e) {
                return new LoadedImage(List.of(loc), null, System.currentTimeMillis());
            }
        });
        if (loaded != null && !loaded.frameLocations().isEmpty()) {
            return loaded.frameLocations().get(0);
        }
        return location;
    }

    private static LoadedImage loadGif(ResourceLocation loc) {
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
                return new LoadedImage(frameLocs, anim, System.currentTimeMillis());
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static LoadedImage loadNonPngImage(ResourceLocation loc) {
        try {
            Optional<Resource> res = Minecraft.getInstance().getResourceManager().getResource(loc);
            if (res.isEmpty()) {
                return null;
            }

            try (InputStream is = res.get().open()) {
                BufferedImage img = ImageIO.read(is);
                if (img == null) {
                    return null;
                }

                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", baos);
                NativeImage nativeImage = NativeImage.read(baos.toByteArray());

                ResourceLocation dynLoc = ResourceLocation.fromNamespaceAndPath(
                        loc.getNamespace(),
                        loc.getPath() + "_dyn.png"
                );

                DynamicTexture dynTex = new DynamicTexture(nativeImage);
                Minecraft.getInstance().getTextureManager().register(dynLoc, dynTex);

                return new LoadedImage(List.of(dynLoc), null, System.currentTimeMillis());
            }
        } catch (Exception e) {
            return null;
        }
    }
}
