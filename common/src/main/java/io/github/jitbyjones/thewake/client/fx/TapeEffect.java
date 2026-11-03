package io.github.jitbyjones.thewake.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.client.ClientPhantoms;
import io.github.jitbyjones.thewake.client.ClientState;
import io.github.jitbyjones.thewake.config.WakeConfig;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.PostChain;
import net.minecraft.util.Mth;

/**
 * The world as a worn videotape: grain, scanlines, colour bleeding sideways, a tracking band
 * rolling down the picture, and a tear when it attacks. Applied to the level only (after the level
 * and hand, before the HUD and menus), and only when there is a reason: it is on screen, it is
 * moving, the tape is paused, the morning after, the Stopped.
 */
public final class TapeEffect {
    @Nullable
    private static PostChain chain;
    private static boolean failed;
    private static int width = -1;
    private static int height = -1;
    private static float tear;
    private static float smoothed;

    private TapeEffect() {}

    /** A burst of damage to the picture (decays by itself). */
    public static void tear(float amount) {
        tear = Math.max(tear, amount);
    }

    public static void tick() {
        tear *= 0.86F;
        if (tear < 0.01F) {
            tear = 0.0F;
        }
        float target = target();
        smoothed += (target - smoothed) * (target > smoothed ? 0.35F : 0.08F);
    }

    /** How worn the picture is right now, 0..1, before the tear. */
    private static float target() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return 0.0F;
        }
        float amount = Overlays.morningAmount();
        if (StoppedClient.active()) {
            amount = Math.max(amount, 0.55F);
        }
        if (ClientState.hunting) {
            amount = Math.max(amount, 0.5F);
        }
        if (mc.screen instanceof PauseScreen && PauseTape.tapeShowing()) {
            amount = Math.max(amount, 0.65F);
        }
        for (ClientPhantom p : ClientPhantoms.all()) {
            if (p.gaze.visible && !p.dissolving) {
                double reach = 30.0 * Math.max(1.0F, p.scale);
                float near = (float) Mth.clamp(1.15 - p.gaze.distance / reach, 0.0, 1.0);
                float strength = p.scale > 1.0F ? 0.3F : 0.55F;
                amount = Math.max(amount, 0.1F + near * strength);
            } else if (!p.dissolving && p.scale == 1.0F && p.gaze.distance < 8.0) {
                // Close, and you cannot see it: the picture knows before you do.
                amount = Math.max(amount, (float) (0.3 * (1.0 - p.gaze.distance / 8.0)));
            }
            if (p.dissolving) {
                amount = Math.max(amount, 0.8F);
            }
        }
        return Mth.clamp(amount, 0.0F, 1.0F);
    }

    public static void process(float partial) {
        if (failed || !WakeConfig.get().tapeEffect) {
            return;
        }
        float amount = smoothed;
        float burst = tear;
        if (amount <= 0.004F && burst <= 0.004F) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(),
                        TheWake.id("shaders/post/tape.json"));
                width = -1;
            }
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            if (w != width || h != height) {
                chain.resize(w, h);
                width = w;
                height = h;
            }
            chain.setUniform("Amount", amount);
            chain.setUniform("Tear", burst);
            chain.setUniform("Time", (Util.getMillis() % 1_000_000L) / 1000.0F);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(partial);
            mc.getMainRenderTarget().bindWrite(false);
        } catch (Exception e) {
            failed = true;
            TheWake.LOG.warn("Tape effect disabled: {}", e.toString());
            close();
        }
    }

    /** Called on resource reload and disconnect. */
    public static void close() {
        if (chain != null) {
            chain.close();
            chain = null;
        }
        width = height = -1;
    }

    public static void reloaded() {
        close();
        failed = false;
    }

    public static void reset() {
        tear = 0.0F;
        smoothed = 0.0F;
    }
}
