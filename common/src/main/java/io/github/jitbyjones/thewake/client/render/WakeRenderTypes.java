package io.github.jitbyjones.thewake.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;

/**
 * Render types for the night. Extends RenderType only to reach the protected render-state shards;
 * the types themselves are built from RenderType's public constructor, so no access wideners.
 */
public final class WakeRenderTypes extends RenderType {
    public static final ResourceLocation NIGHT_TEXTURE = TheWake.id("textures/misc/night.png");
    public static final ResourceLocation RIBBON_TEXTURE = TheWake.id("textures/misc/ribbon.png");

    /** The silhouette in the world: depth-tested, no depth writes so smeared copies blend. */
    public static final RenderType NIGHT = new RenderType("thewake_night", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            1536, false, true, () -> setup(true, true), () -> clear(true, true)) {
    };

    /** The one unblurred thing behind the pause menu: drawn over everything. */
    public static final RenderType NIGHT_ON_TOP = new RenderType("thewake_night_on_top", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 1536, false, true, () -> setup(true, false), () -> clear(true, false)) {
    };

    /** Ribbons of night on the ground, in the Stopped. */
    public static final RenderType RIBBON = new RenderType("thewake_ribbon", DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS,
            262144, false, true, () -> setup(false, true), () -> clear(false, true)) {
    };

    private WakeRenderTypes(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize, boolean crumbling, boolean sort,
            Runnable setup, Runnable clear) {
        super(name, format, mode, bufferSize, crumbling, sort, setup, clear);
    }

    private static void setup(boolean figure, boolean depthTest) {
        WakeShaders.applyTime();
        RenderSystem.setShader(() -> figure ? WakeShaders.night : WakeShaders.ribbon);
        TRANSLUCENT_TRANSPARENCY.setupRenderState();
        (depthTest ? LEQUAL_DEPTH_TEST : NO_DEPTH_TEST).setupRenderState();
        COLOR_WRITE.setupRenderState();
        (figure ? CULL : NO_CULL).setupRenderState();
    }

    private static void clear(boolean figure, boolean depthTest) {
        (figure ? CULL : NO_CULL).clearRenderState();
        COLOR_WRITE.clearRenderState();
        (depthTest ? LEQUAL_DEPTH_TEST : NO_DEPTH_TEST).clearRenderState();
        TRANSLUCENT_TRANSPARENCY.clearRenderState();
    }

    /** Silhouette render type for this frame: the shader if usable, otherwise a starfield texture. */
    public static RenderType figure(boolean onTop) {
        if (WakeShaders.usable()) {
            return onTop ? NIGHT_ON_TOP : NIGHT;
        }
        return onTop ? RenderType.entityTranslucentEmissive(NIGHT_TEXTURE, false) : RenderType.entityTranslucentEmissive(NIGHT_TEXTURE);
    }

    public static RenderType ribbon() {
        return WakeShaders.usable() ? RIBBON : RenderType.entityTranslucentEmissive(RIBBON_TEXTURE);
    }
}
