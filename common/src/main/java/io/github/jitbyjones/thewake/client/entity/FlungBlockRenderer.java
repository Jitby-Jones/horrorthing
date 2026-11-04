package io.github.jitbyjones.thewake.client.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import io.github.jitbyjones.thewake.entity.FlungBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;

/** The door in the air: both halves, turning over about its middle. */
public class FlungBlockRenderer extends EntityRenderer<FlungBlock> {
    private final BlockRenderDispatcher blocks;

    public FlungBlockRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.4F;
        this.blocks = context.getBlockRenderDispatcher();
    }

    @Override
    public void render(FlungBlock entity, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockState lower = entity.lower();
        BlockState upper = entity.upper();
        boolean tall = !upper.isAir();
        pose.pushPose();
        float spin = entity.roll(partial);
        float centre = tall ? 1.0F : 0.5F;
        pose.translate(0.0F, 0.15F, 0.0F);
        pose.mulPose(Axis.YP.rotationDegrees(entity.getId() * 37 % 360));
        pose.mulPose(Axis.XP.rotationDegrees(spin));
        pose.translate(-0.5F, -centre * Mth.cos(spin * Mth.DEG_TO_RAD) * 0.0F - centre, -0.5F);
        blocks.renderSingleBlock(lower, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        if (tall) {
            pose.translate(0.0F, 1.0F, 0.0F);
            blocks.renderSingleBlock(upper, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        }
        pose.popPose();
        super.render(entity, yaw, partial, pose, buffers, light);
    }

    @Override
    public ResourceLocation getTextureLocation(FlungBlock entity) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** Unused accessor kept for parity with vanilla renderers. */
    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
