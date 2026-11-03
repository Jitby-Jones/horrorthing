package io.github.jitbyjones.thewake.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.client.ClientPhantoms;
import io.github.jitbyjones.thewake.client.creature.CreatureModel;
import io.github.jitbyjones.thewake.client.creature.CreatureRenderer;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Your inventory shows you, in a little window. When it is behind you, it is behind you there too:
 * its face rising over your shoulder, larger the closer it is. That one is real.
 */
public final class Paperdoll {
    private static final double REACH = 7.0;

    private Paperdoll() {}

    /** Before the doll is drawn, so the doll is in front of it. */
    public static void render(GuiGraphics graphics, int x1, int y1, int x2, int y2, int scale, LivingEntity entity) {
        Minecraft mc = Minecraft.getInstance();
        if (entity != mc.player || mc.level == null) {
            return;
        }
        ClientPhantom behind = behind(mc);
        if (behind == null) {
            return;
        }
        CreatureModel model = CreatureRenderer.model();
        if (model == null) {
            return;
        }
        double distance = behind.pos.distanceTo(mc.player.position());
        float closeness = (float) Mth.clamp(1.0 - distance / REACH, 0.0, 1.0);
        // Which shoulder: the side of you it is on.
        Vec3 look = mc.player.getViewVector(1.0F);
        Vec3 to = behind.pos.subtract(mc.player.position());
        float side = (float) Math.signum(look.x * to.z - look.z * to.x);
        if (side == 0.0F) {
            side = 1.0F;
        }
        CreaturePose poseId = closeness > 0.6F ? CreaturePose.REACH : CreaturePose.STAND;
        CreatureModel.Pose pose = model.pose(poseId);
        Vector3f head = pose.pivot(CreatureModel.HEAD);
        float cx = (x1 + x2) / 2.0F;
        float cy = (y1 + y2) / 2.0F;
        float pixels = scale * (0.9F + 0.7F * closeness);
        // Its head just over your doll's shoulder, rising as it comes closer.
        float headX = cx - side * scale * (0.42F - 0.12F * closeness);
        float headY = cy - scale * (0.55F + 0.35F * closeness);

        graphics.flush();
        graphics.enableScissor(x1, y1, x2, y2);
        Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(graphics.pose().last().pose())
                .translate(headX, headY, 20.0F)
                .scale(pixels, pixels, -pixels)
                .rotateZ((float) Math.PI);
        Vec3 relative = new Vec3(-head.x, -head.y, -head.z);
        float jaw = closeness > 0.8F ? 8.0F : 0.0F;
        CreatureRenderer.Head h = new CreatureRenderer.Head(side * 12.0F, -8.0F, side * 14.0F, jaw);
        CreatureRenderer.draw(poseId, view, RenderSystem.getProjectionMatrix(), relative, 180.0F, 1.0F, h,
                CreatureRenderer.lightAt(behind), null, Phantom.FLAG_NO_TWITCH);
        RenderSystem.clear(256, Minecraft.ON_OSX);
        graphics.disableScissor();
    }

    @Nullable
    private static ClientPhantom behind(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1.0F);
        ClientPhantom best = null;
        double bestSq = REACH * REACH;
        for (ClientPhantom p : ClientPhantoms.all()) {
            if (p.dissolving || p.mode != Phantom.Mode.STILL || p.scale != 1.0F) {
                continue;
            }
            Vec3 to = p.pos.add(0.0, 1.6, 0.0).subtract(eye);
            double sq = to.lengthSqr();
            if (sq > bestSq) {
                continue;
            }
            double cos = look.dot(to.normalize());
            if (cos < -0.35) {
                bestSq = sq;
                best = p;
            }
        }
        return best;
    }
}
