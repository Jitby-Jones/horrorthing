package io.github.jitbyjones.thewake.client.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.client.ClientPhantoms;
import io.github.jitbyjones.thewake.client.fx.PauseFigure;
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

/** Called once per frame from the level render, after entities. */
public final class WorldRender {
    private WorldRender() {}

    public static void afterEntities(Camera camera, float partial) {
        PauseFigure.capture(camera);
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        boolean ribbons = StoppedClient.active() && !StoppedClient.ribbons().isEmpty();
        if (ClientPhantoms.isEmpty() && !ribbons) {
            return;
        }
        Vec3 cam = camera.getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        PoseStack poseStack = new PoseStack();

        if (ribbons) {
            RenderType type = WakeRenderTypes.ribbon();
            VertexConsumer consumer = buffers.getBuffer(type);
            Matrix4f matrix = poseStack.last().pose();
            for (Long2ByteMap.Entry e : StoppedClient.ribbons().long2ByteEntrySet()) {
                quad(consumer, matrix, e.getLongKey(), cam, 0.22F + 0.6F * Math.min(1.0F, (e.getByteValue() & 0xFF) / 10.0F));
            }
            LongIterator it = StoppedClient.fresh().iterator();
            while (it.hasNext()) {
                quad(consumer, matrix, it.nextLong(), cam, 0.16F);
            }
            buffers.endBatch(type);
        }

        if (!ClientPhantoms.isEmpty()) {
            RenderType type = WakeRenderTypes.figure(false);
            VertexConsumer consumer = buffers.getBuffer(type);
            for (ClientPhantom phantom : ClientPhantoms.all()) {
                if (phantom.pos.distanceToSqr(cam) < 192.0 * 192.0) {
                    Silhouette.draw(poseStack, consumer, phantom, cam, partial);
                }
            }
            buffers.endBatch(type);
        }
    }

    /** A soft-edged patch of night lying on the block under a footprint. */
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
