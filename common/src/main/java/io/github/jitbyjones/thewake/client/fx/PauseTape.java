package io.github.jitbyjones.thewake.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.jitbyjones.thewake.client.ClientPhantoms;
import io.github.jitbyjones.thewake.client.ClientState;
import io.github.jitbyjones.thewake.client.render.WorldRender;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * Menus. Behind the pause menu the world is blurred, behind a chest it is dimmed: but not the
 * creature. It is drawn again over whatever the menu put on the world, sharp and undimmed, so you
 * can watch it come while you sort your inventory. And the pause menu is a paused tape.
 */
public final class PauseTape {
    private static long playShownAt = -1L;
    private static boolean wasPaused;

    private PauseTape() {}

    /** After a menu has blurred or dimmed the world, before it draws itself. */
    public static void drawOverBackground(Screen screen, GuiGraphics graphics) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || !WorldRender.captured) {
            return;
        }
        if (!ClientPhantoms.isEmpty()) {
            graphics.flush();
            drawWorldCreatures(mc);
        }
        if (screen instanceof PauseScreen && tapeShowing()) {
            osd(graphics, "PAUSE", blink());
        }
    }

    private static void drawWorldCreatures(Minecraft mc) {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(WorldRender.PROJECTION, VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(WorldRender.VIEW);
        RenderSystem.applyModelViewMatrix();
        // With the world's depth back in place, walls still hide it; without, draw it over everything.
        if (!WorldRender.restoreDepth()) {
            RenderSystem.clear(256, Minecraft.ON_OSX);
        }
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        Matrix4f inverse = new Matrix4f(WorldRender.VIEW).invert();
        Vector3f look = inverse.transformDirection(new Vector3f(0.0F, 0.0F, -1.0F)).normalize();
        WorldRender.drawCreatures(WorldRender.VIEW, WorldRender.PROJECTION, WorldRender.cameraPos, look, 1.0F, buffers);
        buffers.endBatch();
        // Leave no depth behind for the menu to trip over.
        RenderSystem.clear(256, Minecraft.ON_OSX);
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    /** The tape layer exists once it is awake. */
    public static boolean tapeShowing() {
        return ClientState.stage >= 2 && !ClientState.ended;
    }

    private static boolean blink() {
        return (Util.getMillis() / 600L) % 2L == 0L;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean paused = mc.screen instanceof PauseScreen;
        if (wasPaused && !paused && tapeShowing()) {
            playShownAt = Util.getMillis();
        }
        wasPaused = paused;
    }

    /** "PLAY" for a moment after resuming. */
    public static void renderHud(GuiGraphics graphics) {
        if (playShownAt < 0) {
            return;
        }
        long age = Util.getMillis() - playShownAt;
        if (age > 2600L) {
            playShownAt = -1L;
            return;
        }
        osd(graphics, "PLAY ▶", age < 2000L || blink());
    }

    /** On-screen display in the corner, the way a VCR writes over the picture. */
    private static void osd(GuiGraphics graphics, String text, boolean visible) {
        if (!visible) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        int x = 14 + (int) ((Util.getMillis() / 90L) % 3L == 0L ? 1 : 0);
        int y = 12;
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 400.0F);
        graphics.pose().scale(2.0F, 2.0F, 1.0F);
        graphics.drawString(mc.font, text, x / 2, y / 2, 0xFFF2F2F2, true);
        graphics.pose().popPose();
    }

    public static void reset() {
        playShownAt = -1L;
        wasPaused = false;
    }
}
