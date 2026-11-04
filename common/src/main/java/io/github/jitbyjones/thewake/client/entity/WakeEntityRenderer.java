package io.github.jitbyjones.thewake.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Vanilla's entity pass draws nothing for it: its body is drawn with the images, after the
 * entities, from the same skeleton and in the same stop-motion (see {@code WorldRender}). This only
 * keeps the hitbox, the name and the shadow out of it.
 */
public class WakeEntityRenderer extends EntityRenderer<WakeEntity> {
    private static final ResourceLocation TEXTURE = TheWake.id("textures/entity/creature_body.png");

    public WakeEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public boolean shouldRender(WakeEntity entity, Frustum frustum, double x, double y, double z) {
        return false;
    }

    @Override
    public void render(WakeEntity entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
    }

    @Override
    public ResourceLocation getTextureLocation(WakeEntity entity) {
        return TEXTURE;
    }
}
