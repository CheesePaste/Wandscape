package com.wsteam.wandscape.shared.ui.markdown.gif;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Robust, lightweight GIF animation decoder for Minecraft Wandscape UI.
 * Handles GIF frame sequences, Disposal Methods, and frame delays.
 */
public final class GifDecoder {

    public record GifFrame(NativeImage image, int delayMs) {}

    public record GifAnimation(List<GifFrame> frames, int width, int height, int totalDurationMs) {
        public GifFrame getFrameAt(long timeMs) {
            if (frames.isEmpty()) {
                return null;
            }
            if (totalDurationMs <= 0) {
                return frames.get(0);
            }
            long cur = timeMs % totalDurationMs;
            long elapsed = 0;
            for (GifFrame frame : frames) {
                elapsed += frame.delayMs();
                if (cur < elapsed) {
                    return frame;
                }
            }
            return frames.get(frames.size() - 1);
        }
    }

    private GifDecoder() {}

    /**
     * Decode GIF InputStream into a list of NativeImage frames with delay metrics.
     */
    public static GifAnimation decode(InputStream is) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(is)) {
            ImageReader reader = ImageIO.getImageReadersByFormatName("gif").next();
            reader.setInput(iis);

            int numFrames = reader.getNumImages(true);
            if (numFrames == 0) {
                return null;
            }

            List<GifFrame> frames = new ArrayList<>();
            int canvasW = reader.getWidth(0);
            int canvasH = reader.getHeight(0);
            int totalDuration = 0;

            BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = canvas.createGraphics();
            g.setBackground(new Color(0, 0, 0, 0));

            BufferedImage previousCanvas = null;

            for (int i = 0; i < numFrames; i++) {
                BufferedImage frameImage = reader.read(i);
                IIOMetadata metadata = reader.getImageMetadata(i);
                IIOMetadataNode root = (IIOMetadataNode) metadata.getAsTree("javax_imageio_gif_image_1.0");
                IIOMetadataNode gce = getChildNode(root, "GraphicControlExtension");

                int delay100ths = gce != null ? Integer.parseInt(gce.getAttribute("delayTime")) : 10;
                int delayMs = delay100ths > 0 ? delay100ths * 10 : 100;
                String disposalMethod = gce != null ? gce.getAttribute("disposalMethod") : "none";

                int imageLeft = 0;
                int imageTop = 0;

                IIOMetadataNode imgDesc = getChildNode(root, "ImageDescriptor");
                if (imgDesc != null) {
                    imageLeft = Integer.parseInt(imgDesc.getAttribute("imageLeftPosition"));
                    imageTop = Integer.parseInt(imgDesc.getAttribute("imageTopPosition"));
                }

                if ("restoreToPrevious".equals(disposalMethod) && previousCanvas != null) {
                    g.setComposite(AlphaComposite.Src);
                    g.drawImage(previousCanvas, 0, 0, null);
                } else if ("restoreToBackgroundColor".equals(disposalMethod)) {
                    g.setComposite(AlphaComposite.Clear);
                    g.fillRect(imageLeft, imageTop, frameImage.getWidth(), frameImage.getHeight());
                }

                if ("restoreToPrevious".equals(disposalMethod)) {
                    previousCanvas = copyImage(canvas);
                }

                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(frameImage, imageLeft, imageTop, null);

                NativeImage nativeImage = bufferedImageToNativeImage(canvas);
                frames.add(new GifFrame(nativeImage, delayMs));
                totalDuration += delayMs;
            }

            g.dispose();
            reader.dispose();
            return new GifAnimation(frames, canvasW, canvasH, totalDuration);
        } catch (Exception e) {
            return null;
        }
    }

    private static IIOMetadataNode getChildNode(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) {
                return (IIOMetadataNode) root.item(i);
            }
        }
        return null;
    }

    private static BufferedImage copyImage(BufferedImage bi) {
        BufferedImage cm = new BufferedImage(bi.getWidth(), bi.getHeight(), bi.getType());
        Graphics2D g = cm.createGraphics();
        g.drawImage(bi, 0, 0, null);
        g.dispose();
        return cm;
    }

    private static NativeImage bufferedImageToNativeImage(BufferedImage img) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return NativeImage.read(baos.toByteArray());
    }
}
