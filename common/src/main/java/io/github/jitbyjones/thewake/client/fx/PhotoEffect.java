package io.github.jitbyjones.thewake.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.jitbyjones.thewake.TheWake;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.PostChain;

/**
 * The old-photograph look (desaturated, warm, grained, vignetted): the first seconds after waking
 * from a night it walked, and the whole of the Stopped. A vanilla post chain, applied after the
 * level and hand, before the HUD.
 */
public final class PhotoEffect {
    @Nullable
    private static PostChain chain;
    private static boolean failed;
    private static int width = -1;
    private static int height = -1;

    private PhotoEffect() {}

    public static void process(float partial) {
        float amount = Overlays.photoAmount(partial);
        if (amount <= 0.003F || failed) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        try {
            if (chain == null) {
                chain = new PostChain(mc.getTextureManager(), mc.getResourceManager(), mc.getMainRenderTarget(),
                        TheWake.id("shaders/post/exposure.json"));
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
            chain.setUniform("Seed", (Util.getMillis() % 100_000L) / 1000.0F);
            RenderSystem.disableBlend();
            RenderSystem.disableDepthTest();
            RenderSystem.resetTextureMatrix();
            chain.process(partial);
            mc.getMainRenderTarget().bindWrite(false);
        } catch (Exception e) {
            failed = true;
            TheWake.LOG.warn("Photo effect disabled: {}", e.toString());
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
}
