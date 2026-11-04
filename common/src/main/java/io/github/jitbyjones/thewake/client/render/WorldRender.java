package io.github.jitbyjones.thewake.client.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.client.ClientPhantoms;
import io.github.jitbyjones.thewake.client.creature.CreatureRenderer;
import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Called once per frame from the level render, after entities. */
public final class WorldRender {
    /** The world's matrices from the last level render, for drawing it again over a menu. */
    public static final Matrix4f VIEW = new Matrix4f();
    public static final Matrix4f PROJECTION = new Matrix4f();
    public static Vec3 cameraPos = Vec3.ZERO;
    public static boolean captured;
    /** The world's depth, kept while a menu is open, so walls still hide it behind the menu. */
    @javax.annotation.Nullable
    private static TextureTarget savedDepth;
    private static boolean depthSaved;

    private WorldRender() {}

    public static void afterEntities(Camera camera, float partial) {
        VIEW.set(RenderSystem.getModelViewMatrix());
        PROJECTION.set(RenderSystem.getProjectionMatrix());
        cameraPos = camera.getPosition();
        captured = true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        boolean ribbons = StoppedClient.active() && !StoppedClient.ribbons().isEmpty();
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        if (ribbons) {
            PoseStack poseStack = new PoseStack();
            RenderType type = WakeRenderTypes.ribbon();
            VertexConsumer consumer = buffers.getBuffer(type);
            Matrix4f matrix = poseStack.last().pose();
            for (Long2ByteMap.Entry e : StoppedClient.ribbons().long2ByteEntrySet()) {
                quad(consumer, matrix, e.getLongKey(), cam, 0.34F + 0.6F * Math.min(1.0F, (e.getByteValue() & 0xFF) / 8.0F));
            }
            LongIterator it = StoppedClient.fresh().iterator();
            while (it.hasNext()) {
                quad(consumer, matrix, it.nextLong(), cam, 0.24F);
            }
            buffers.endBatch(type);
        }

        if (!ClientPhantoms.isEmpty()) {
            buffers.endBatch();
            drawCreatures(VIEW, PROJECTION, cam, camera.getLookVector(), partial, buffers);
        }
        drawBodies(mc, VIEW, PROJECTION, cam, camera.getLookVector(), partial, buffers);
    }

    // ---- its body -----------------------------------------------------------------------------

    private static float wear;

    /** How much its body near you wears the picture down, 0..1. */
    public static float bodyWear() {
        return wear;
    }

    public static void forgetBodies() {
        wear = 0.0F;
    }

    /**
     * Its body, when it is out: drawn from the same skeleton as the images, in stop-motion. The
     * picture of it only changes on a new frame of its clip (or every few ticks if it slides), so
     * even running at you it comes in jerks, a few drawings a second.
     */
    private static void drawBodies(Minecraft mc, Matrix4f view, Matrix4f projection, Vec3 cam, Vector3f look, float partial,
            MultiBufferSource.BufferSource buffers) {
        float nearest = 0.0F;
        boolean any = false;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof io.github.jitbyjones.thewake.entity.WakeEntity body) || body.isRemoved()) {
                continue;
            }
            if (!any) {
                buffers.endBatch();
                any = true;
            }
            Vec3 actual = body.getPosition(partial);
            boolean smooth = body.hasFlag(io.github.jitbyjones.thewake.entity.WakeEntity.FLAG_SMOOTH);
            boolean cut = body.shownPos == null || body.clip().ordinal() != body.shownClip || body.frame() != body.shownFrame || smooth
                    || actual.distanceToSqr(body.shownPos) > 9.0 || body.clientAge % 4 == 0 && actual.distanceToSqr(body.shownPos) > 0.04;
            if (cut) {
                body.shownPos = actual;
                body.shownYaw = net.minecraft.util.Mth.rotLerp(partial, body.yRotO, body.getYRot());
                body.shownClip = body.clip().ordinal();
                body.shownFrame = body.frame();
            }
            if (body.hasFlag(io.github.jitbyjones.thewake.entity.WakeEntity.FLAG_LEAVING) && body.clientAge % 2 == 1) {
                continue;
            }
            Vec3 at = body.shownPos;
            double distance = at.distanceTo(cam);
            float height = body.clip().height;
            Vec3 centre = at.add(0.0, height * 0.5, 0.0).subtract(cam);
            double along = centre.x * look.x() + centre.y * look.y() + centre.z * look.z();
            nearest = Math.max(nearest, (float) net.minecraft.util.Mth.clamp(1.0 - distance / 24.0, 0.0, 1.0) * 0.5F);
            if (along < -(height * 0.75 + 1.0) || distance > 160.0) {
                continue;
            }
            CreatureRenderer.face(body.form().ordinal());
            CreatureRenderer.draw(body.clip(), body.frame(), view, projection, at.subtract(cam), body.shownYaw, 1.0F,
                    CreatureRenderer.Head.of(body, partial), CreatureRenderer.lightAt(mc.level, at, height), buffers, 0);
        }
        wear += (nearest - wear) * 0.1F;
        CreatureRenderer.face(io.github.jitbyjones.thewake.client.ClientState.form);
    }

    /** Right after the level is drawn: keep its depth if a menu will draw the creature again. */
    public static void afterLevel() {
        Minecraft mc = Minecraft.getInstance();
        depthSaved = false;
        if (mc.screen == null || ClientPhantoms.isEmpty()) {
            return;
        }
        RenderTarget main = mc.getMainRenderTarget();
        if (savedDepth == null || savedDepth.width != main.width || savedDepth.height != main.height) {
            if (savedDepth != null) {
                savedDepth.destroyBuffers();
            }
            savedDepth = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
        }
        savedDepth.copyDepthFrom(main);
        main.bindWrite(false);
        depthSaved = true;
    }

    /** Puts the world's depth back into the main target. False if there is none to put back. */
    public static boolean restoreDepth() {
        Minecraft mc = Minecraft.getInstance();
        if (!depthSaved || savedDepth == null) {
            return false;
        }
        RenderTarget main = mc.getMainRenderTarget();
        if (savedDepth.width != main.width || savedDepth.height != main.height) {
            return false;
        }
        main.copyDepthFrom(savedDepth);
        main.bindWrite(false);
        return true;
    }

    public static void releaseDepth() {
        if (savedDepth != null) {
            savedDepth.destroyBuffers();
            savedDepth = null;
        }
        depthSaved = false;
    }

    /** Every creature this client knows about, with the given camera. */
    public static void drawCreatures(Matrix4f view, Matrix4f projection, Vec3 cam, Vector3f look, float partial,
            MultiBufferSource.BufferSource buffers) {
        CreatureRenderer.face(io.github.jitbyjones.thewake.client.ClientState.form);
        for (ClientPhantom phantom : ClientPhantoms.all()) {
            if (!shouldDraw(phantom, cam, look)) {
                continue;
            }
            CreatureRenderer.render(phantom, view, projection, phantom.pos.subtract(cam), partial, CreatureRenderer.lightAt(phantom), buffers);
        }
    }

    private static boolean shouldDraw(ClientPhantom p, Vec3 cam, Vector3f look) {
        // Leaving: it flickers out like a tape skipping, a frame there, a frame not.
        if (p.dissolving && (p.dissolveAge % 2 == 1 || p.dissolveAge > 4)) {
            return false;
        }
        double radius = p.height() * 0.75 + 1.0;
        Vec3 center = p.pos.add(0.0, p.height() * 0.5, 0.0).subtract(cam);
        double distanceSq = center.lengthSqr();
        double far = 256.0 * Math.max(1.0F, p.scale);
        if (distanceSq > far * far) {
            return false;
        }
        double along = center.x * look.x() + center.y * look.y() + center.z * look.z();
        return along > -radius;
    }

    /** A soft-edged patch lying on the block under a footprint. */
    private static void quad(VertexConsumer consumer, Matrix4f matrix, long packed, Vec3 cam, float alpha) {
        int x = BlockPos.getX(packed);
        int y = BlockPos.getY(packed);
        int z = BlockPos.getZ(packed);
        double dx = x + 0.5 - cam.x;
        double dz = z + 0.5 - cam.z;
        if (dx * dx + dz * dz > 96.0 * 96.0) {
            return;
        }
        float x0 = (float) (x - 0.3 - cam.x);
        float x1 = (float) (x + 1.3 - cam.x);
        float z0 = (float) (z - 0.3 - cam.z);
        float z1 = (float) (z + 1.3 - cam.z);
        float yy = (float) (y + 0.015 - cam.y);
        int a = Math.round(alpha * 255.0F);
        vertex(consumer, matrix, x0, yy, z0, 0.0F, 0.0F, a);
        vertex(consumer, matrix, x0, yy, z1, 0.0F, 1.0F, a);
        vertex(consumer, matrix, x1, yy, z1, 1.0F, 1.0F, a);
        vertex(consumer, matrix, x1, yy, z0, 1.0F, 0.0F, a);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, float x, float y, float z, float u, float v, int alpha) {
        consumer.addVertex(matrix, x, y, z).setColor(255, 255, 255, alpha).setUv(u, v).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 1.0F, 0.0F);
    }
}
