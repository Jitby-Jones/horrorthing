package io.github.jitbyjones.thewake.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.phantom.Phantom;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.core.Direction;
import net.minecraft.util.FastColor;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Draws the Wake: the player's own silhouette (their arm width), filled with night, as a long
 * exposure. Several overlapping copies make the image. Smear spreads them along the motion; low
 * focus scatters them; a head-only smear doubles just the head as it turns.
 */
public final class Silhouette {
    @Nullable
    private static PlayerModel<LivingEntity> wide;
    @Nullable
    private static PlayerModel<LivingEntity> slim;

    private Silhouette() {}

    private static PlayerModel<LivingEntity> model() {
        Minecraft mc = Minecraft.getInstance();
        boolean isSlim = mc.player != null && mc.player.getSkin().model() == PlayerSkin.Model.SLIM;
        if (isSlim) {
            if (slim == null) {
                slim = build(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER_SLIM), true);
            }
            return slim;
        }
        if (wide == null) {
            wide = build(mc.getEntityModels().bakeLayer(ModelLayers.PLAYER), false);
        }
        return wide;
    }

    private static PlayerModel<LivingEntity> build(ModelPart root, boolean isSlim) {
        PlayerModel<LivingEntity> model = new PlayerModel<>(root, isSlim);
        model.young = false;
        model.crouching = false;
        model.riding = false;
        // A silhouette has no outer layer.
        model.hat.visible = false;
        model.jacket.visible = false;
        model.leftSleeve.visible = false;
        model.rightSleeve.visible = false;
        model.leftPants.visible = false;
        model.rightPants.visible = false;
        return model;
    }

    /** Resource reloads rebuild model layers; drop ours too. */
    public static void invalidate() {
        wide = null;
        slim = null;
    }

    /**
     * One image of the Wake at a world position (camera-relative pose stack). Returns the render type
     * used for its face, if any, so the caller can flush it.
     */
    @Nullable
    public static RenderType draw(PoseStack poseStack, MultiBufferSource buffers, RenderType figureType, ClientPhantom phantom,
            Vec3 cameraPos, float partial) {
        Vec3 pos = phantom.lerpPos(partial);
        float opacity = phantom.lerpOpacity(partial);
        if (opacity <= 0.003F) {
            return null;
        }
        float yaw = phantom.lerpYaw(partial);
        float headYaw = Mth.lerp(partial, phantom.prevHeadYaw, phantom.headYaw);
        float headPitch = Mth.lerp(partial, phantom.prevHeadPitch, phantom.headPitch);
        float walk = Mth.lerp(partial, phantom.prevWalkPos, phantom.walkPos);
        Vec3 relative = pos.subtract(cameraPos);
        draw(poseStack, buffers.getBuffer(figureType), relative, yaw, phantom.stance, phantom.bedDirection, opacity,
                phantom.smear, phantom.focus, headYaw, headPitch, phantom.headSmear, walk, phantom.walkSpeed, phantom.motion, phantom.id,
                1.0F);
        float face = Mth.lerp(partial, phantom.prevFace, phantom.face) * opacity;
        Minecraft mc = Minecraft.getInstance();
        if (face <= 0.01F || mc.player == null || mc.level == null) {
            return null;
        }
        // Its face is yours. Only the head, lit like the room it stands in.
        RenderType faceType = RenderType.entityTranslucent(mc.player.getSkin().texture());
        PlayerModel<LivingEntity> model = model();
        int light = net.minecraft.client.renderer.LevelRenderer.getLightColor(mc.level, net.minecraft.core.BlockPos.containing(pos.add(0, 1.5, 0)));
        poseStack.pushPose();
        poseStack.translate(relative.x, relative.y, relative.z);
        transform(poseStack, yaw, phantom.stance, phantom.bedDirection, 1.0F);
        headOnly(model, true);
        model.hat.visible = true;
        pose(model, phantom.stance, headYaw, headPitch, walk, phantom.walkSpeed, false);
        model.renderToBuffer(poseStack, buffers.getBuffer(faceType), light, OverlayTexture.NO_OVERLAY,
                FastColor.ARGB32.color(Math.round(face * 235.0F), 200, 200, 210));
        model.hat.visible = false;
        headOnly(model, false);
        poseStack.popPose();
        return faceType;
    }

    public static void draw(PoseStack poseStack, VertexConsumer consumer, Vec3 relative, float yaw, Phantom.Stance stance, int bedDirection,
            float opacity, float smear, float focus, float headYaw, float headPitch, float headSmear, float walkPos, float walkSpeed,
            Vec3 motion, int seed, float scale) {
        PlayerModel<LivingEntity> model = model();
        float time = (net.minecraft.Util.getMillis() % 1_000_000L) / 1000.0F;
        float blur = Math.max(smear, 1.0F - focus);
        int copies = 1 + Math.round(5.0F * blur);
        // Overlapping copies compound: the core reaches the target opacity, the fringe stays faint.
        float each = 1.0F - (float) Math.pow(1.0F - Math.min(opacity, 0.985F), 1.0F / copies);

        Vec3 dir = motion.horizontalDistanceSqr() > 1.0E-5 ? new Vec3(motion.x, 0, motion.z).normalize()
                : Vec3.directionFromRotation(0.0F, yaw).multiply(1, 0, 1);
        if (dir.lengthSqr() < 1.0E-5) {
            dir = new Vec3(0, 0, 1);
        }

        for (int i = 0; i < copies; i++) {
            float u = copies == 1 ? 0.0F : i / (float) (copies - 1) - 0.5F;
            float jx = noise(seed, i, 0, time);
            float jz = noise(seed, i, 1, time);
            float jy = noise(seed, i, 2, time);
            float jyaw = noise(seed, i, 3, time);
            float jhead = noise(seed, i, 4, time);
            double unfocused = (1.0F - focus) * 0.3F;
            Vec3 offset = dir.scale(-u * smear * 1.5)
                    .add(jx * unfocused, jy * unfocused * 0.4, jz * unfocused);
            float copyYaw = yaw + jyaw * (1.0F - focus) * 18.0F;
            float copyHeadYaw = headYaw + jhead * (1.0F - focus) * 45.0F;
            poseStack.pushPose();
            poseStack.translate(relative.x + offset.x, relative.y + offset.y, relative.z + offset.z);
            transform(poseStack, copyYaw, stance, bedDirection, scale);
            pose(model, stance, copyHeadYaw, headPitch, walkPos + u * smear * 2.0F, walkSpeed, true);
            model.renderToBuffer(poseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                    FastColor.ARGB32.color(Math.round(each * 255.0F), 255, 255, 255));
            poseStack.popPose();
        }

        // Rule 2 breaking: only the head smears into a double image, sweeping where it turned from.
        if (headSmear > 0.01F) {
            int ghosts = 3;
            for (int g = 1; g <= ghosts; g++) {
                float back = g / (float) ghosts;
                poseStack.pushPose();
                poseStack.translate(relative.x, relative.y, relative.z);
                transform(poseStack, yaw, stance, bedDirection, scale);
                headOnly(model, true);
                pose(model, stance, headYaw - Math.signum(headYaw) * back * 70.0F * headSmear, headPitch + back * 10.0F, walkPos, walkSpeed, false);
                model.renderToBuffer(poseStack, consumer, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY,
                        FastColor.ARGB32.color(Math.round(opacity * headSmear * (1.0F - back * 0.6F) * 150.0F), 255, 255, 255));
                headOnly(model, false);
                poseStack.popPose();
            }
        }
    }

    /** Same transform LivingEntityRenderer gives a player, including the sleeping pose. */
    private static void transform(PoseStack poseStack, float yaw, Phantom.Stance stance, int bedDirection, float scale) {
        if (stance == Phantom.Stance.LIE && bedDirection >= 0) {
            Direction direction = Direction.from2DDataValue(bedDirection);
            float eye = 1.62F - 0.1F;
            poseStack.translate(-direction.getStepX() * eye, 0.0F, -direction.getStepZ() * eye);
            poseStack.mulPose(Axis.YP.rotationDegrees(sleepRotation(direction)));
            poseStack.mulPose(Axis.ZP.rotationDegrees(90.0F));
            poseStack.mulPose(Axis.YP.rotationDegrees(270.0F));
        } else {
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - yaw));
        }
        poseStack.scale(-scale, -scale, scale);
        poseStack.scale(0.9375F, 0.9375F, 0.9375F);
        poseStack.translate(0.0F, -1.501F, 0.0F);
    }

    private static float sleepRotation(Direction direction) {
        return switch (direction) {
            case SOUTH -> 90.0F;
            case NORTH -> 270.0F;
            case EAST -> 180.0F;
            default -> 0.0F;
        };
    }

    private static void pose(PlayerModel<LivingEntity> model, Phantom.Stance stance, float headYaw, float headPitch, float walkPos,
            float walkSpeed, boolean resetVisibility) {
        float rad = Mth.DEG_TO_RAD;
        model.head.resetPose();
        model.body.resetPose();
        model.rightArm.resetPose();
        model.leftArm.resetPose();
        model.rightLeg.resetPose();
        model.leftLeg.resetPose();
        model.head.yRot = headYaw * rad;
        model.head.xRot = headPitch * rad;
        model.rightArm.zRot = 0.05F;
        model.leftArm.zRot = -0.05F;
        switch (stance) {
            case LOOK_DOWN -> {
                if (headPitch == 0.0F) {
                    model.head.xRot = 0.75F;
                }
                model.body.xRot = 0.08F;
                model.rightArm.xRot = 0.08F;
                model.leftArm.xRot = 0.08F;
            }
            case WALK -> {
                float amount = walkSpeed > 0.05F ? Math.min(1.0F, walkSpeed) : 0.7F;
                float swing = Mth.cos(walkPos * 0.6662F) * 1.1F * amount;
                model.rightArm.xRot = swing;
                model.leftArm.xRot = -swing;
                model.rightLeg.xRot = -swing * 1.2F;
                model.leftLeg.xRot = swing * 1.2F;
            }
            default -> {
            }
        }
    }

    private static void headOnly(PlayerModel<LivingEntity> model, boolean only) {
        model.body.visible = !only;
        model.rightArm.visible = !only;
        model.leftArm.visible = !only;
        model.rightLeg.visible = !only;
        model.leftLeg.visible = !only;
    }

    /** Slow, smooth pseudo-random drift in [-1, 1] for one copy and channel: the grain of a long exposure. */
    private static float noise(int seed, int copy, int channel, float time) {
        float a = hash(seed * 31 + copy * 7 + channel * 131);
        float b = hash(seed * 17 + copy * 13 + channel * 71 + 5);
        return (float) (Math.sin(time * (0.35 + b * 0.6) + a * 6.2831) * 0.7 + (a * 2.0 - 1.0) * 0.3);
    }

    private static float hash(int n) {
        n = (n << 13) ^ n;
        int m = n * (n * n * 15731 + 789221) + 1376312589;
        return (m & 0x7fffffff) / (float) 0x7fffffff;
    }
}
