package io.github.jitbyjones.thewake.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render type for the finale's ground. Extends RenderType only to reach the protected render-state
 * shards; the type itself is built from RenderType's public constructor, so no access wideners.
 */
public final class WakeRenderTypes extends RenderType {
    public static final ResourceLocation RIBBON_TEXTURE = TheWake.id("textures/misc/ribbon.png");

    /** Patches of static on the ground, in the Stopped. */
    public static final RenderType RIBBON = new RenderType("thewake_ribbon", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            262144, false, true, WakeRenderTypes::setup, WakeRenderTypes::clear) {
    };

    private WakeRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean crumbling, boolean sort,
            Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, crumbling, sort, setup, clear);
    }

    private static void setup() {
        WakeShaders.applyTime();
        RenderSystem.setShader(() -> WakeShaders.ribbon);
        TRANSLUCENT_TRANSPARENCY.setupRenderState();
        LEQUAL_DEPTH_TEST.setupRenderState();
        COLOR_WRITE.setupRenderState();
        NO_CULL.setupRenderState();
    }

    private static void clear() {
        NO_CULL.clearRenderState();
        COLOR_WRITE.clearRenderState();
        LEQUAL_DEPTH_TEST.clearRenderState();
        TRANSLUCENT_TRANSPARENCY.clearRenderState();
    }

    public static RenderType ribbon() {
        return WakeShaders.usable() ? RIBBON : RenderType.entityTranslucentEmissive(RIBBON_TEXTURE);
    }
}
