package io.github.jitbyjones.thewake.client.creature;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws the creature. Each pose's body, and the rigid head and jaw, live in static vertex buffers
 * (built on first use, rebuilt if the light where it stands changes); per frame only matrices
 * change. The head turns about the neck and the jaw opens about its hinge on top of any pose.
 *
 * <p>Uses vanilla's entity shader and lightmap, so it is exactly as dark as the corner it stands
 * in, and shader packs see an ordinary entity draw.
 */
public final class CreatureRenderer {
    public static final ResourceLocation GLINT = TheWake.id("textures/entity/creature_glint.png");
    private static final Vector3f LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final int MAX_BUFFERS = 28;

    @Nullable
    private static CreatureModel model;
    private static boolean failed;
    /** Keyed by group, pose (or -1 for rigid groups) and packed light. Least recently used first. */
    private static final LinkedHashMap<Long, VertexBuffer> BUFFERS = new LinkedHashMap<>(32, 0.75F, true);

    private CreatureRenderer() {}

    @Nullable
    public static CreatureModel model() {
        if (model == null && !failed) {
            try {
                model = CreatureModel.load(Minecraft.getInstance().getResourceManager());
            } catch (Exception e) {
                failed = true;
                TheWake.LOG.error("Could not load the creature model", e);
            }
        }
        return model;
    }

    /** Resource reload: drop the mesh and every buffer; they are rebuilt on demand. */
    public static void invalidate() {
        RenderSystem.assertOnRenderThread();
        for (VertexBuffer buffer : BUFFERS.values()) {
            buffer.close();
        }
        BUFFERS.clear();
        model = null;
        failed = false;
    }

    /** The light at the creature: its own corner, not yours. */
    public static int lightAt(ClientPhantom p) {
        Minecraft mc = Minecraft.getInstance();
        if ((p.flags & Phantom.FLAG_LIT) != 0 || mc.level == null) {
            return LightTexture.FULL_BRIGHT;
        }
        BlockPos head = BlockPos.containing(p.pos.x, p.pos.y + Math.min(p.height() * 0.85, p.height() - 0.2), p.pos.z);
        int atHead = LevelRenderer.getLightColor(mc.level, head);
        int atBody = LevelRenderer.getLightColor(mc.level, BlockPos.containing(p.pos.x, p.pos.y + 0.5, p.pos.z));
        // Inside a wall the head reads as dark; take the brighter of the two.
        int block = Math.max(LightTexture.block(atHead), LightTexture.block(atBody));
        int sky = Math.max(LightTexture.sky(atHead), LightTexture.sky(atBody));
        return LightTexture.pack(block, sky);
    }

    /** Head and jaw offsets for this frame, combining the server's and the client's own. */
    public record Head(float yaw, float pitch, float roll, float jaw) {
        public static final Head NONE = new Head(0.0F, 0.0F, 0.0F, 0.0F);

        public static Head of(ClientPhantom p, float partial) {
            float yaw = p.lerpHeadYaw(partial);
            float pitch = p.lerpHeadPitch(partial);
            float jaw = p.lerpJaw(partial);
            Gaze g = p.gaze;
            if (g.looking && !p.scriptedHead()) {
                yaw = g.lookYaw;
                pitch = g.lookPitch;
            }
            return new Head(yaw + g.twitchYaw, pitch + g.twitchPitch, g.twitchRoll, jaw + g.twitchJaw);
        }
    }

    /**
     * Draws one creature. {@code view} is the camera's model-view (rotation) matrix; {@code relative}
     * is its position minus the camera's.
     */
    public static void render(ClientPhantom p, Matrix4f view, Matrix4f projection, Vec3 relative, float partial, int light,
            @Nullable MultiBufferSource.BufferSource glints) {
        draw(p.pose, view, projection, relative, p.yaw, p.scale, Head.of(p, partial), light, glints, p.flags);
    }

    public static void draw(CreaturePose poseId, Matrix4f view, Matrix4f projection, Vec3 relative, float yaw, float scale, Head head,
            int light, @Nullable MultiBufferSource.BufferSource glints, int flags) {
        CreatureModel m = model();
        if (m == null) {
            return;
        }
        CreatureModel.Pose pose = m.pose(poseId);
        if (poseId == CreaturePose.LIE) {
            // Lying down, it is placed by its head: on the pillow, the rest of it too long for the
            // bed, past the foot.
            Vector3f anchor = pose.pivot(CreatureModel.HEAD);
            float rad = (float) Math.toRadians(-yaw);
            double hx = (anchor.x * Mth.cos(rad) + anchor.z * Mth.sin(rad)) * scale;
            double hz = (-anchor.x * Mth.sin(rad) + anchor.z * Mth.cos(rad)) * scale;
            relative = relative.subtract(hx, 0.0, hz);
        }
        Matrix4f base = new Matrix4f().translation((float) relative.x, (float) relative.y, (float) relative.z)
                .rotateY((float) Math.toRadians(-yaw)).scale(scale);
        Matrix4f headM = headMatrix(pose, head);
        Matrix4f jawM = new Matrix4f(headM).mul(jawMatrix(pose, head.jaw()));
        for (int g = 0; g < m.groups.length; g++) {
            CreatureModel.Group group = m.groups[g];
            Matrix4f model = new Matrix4f(base);
            if (group.rigid) {
                model.mul("jaw".equals(group.name) ? jawM : headM).mul(pose.transforms[g]);
            }
            VertexBuffer buffer = buffer(m, g, pose, light);
            if (buffer == null) {
                continue;
            }
            RenderType type = RenderType.entityCutoutNoCull(group.texture);
            type.setupRenderState();
            setLights(model);
            ShaderInstance shader = RenderSystem.getShader();
            if (shader != null) {
                buffer.bind();
                buffer.drawWithShader(new Matrix4f(view).mul(model), projection, shader);
                VertexBuffer.unbind();
            }
            type.clearRenderState();
        }
        restoreLights();
        if (glints != null) {
            drawGlints(pose, base, headM, view, glints, flags);
        }
    }

    /** The neck: yaw about the vertical, pitch about the head's own sideways axis, roll about its facing. */
    private static Matrix4f headMatrix(CreatureModel.Pose pose, Head head) {
        if (head.yaw() == 0.0F && head.pitch() == 0.0F && head.roll() == 0.0F) {
            return new Matrix4f();
        }
        Vector3f neck = pose.pivot(CreatureModel.NECK);
        Vector3f right = pose.pivot(CreatureModel.HEAD_RIGHT).normalize();
        Vector3f forward = pose.pivot(CreatureModel.HEAD_FORWARD).normalize();
        return new Matrix4f().translation(neck)
                .rotateY((float) Math.toRadians(-head.yaw()))
                .rotate((float) Math.toRadians(head.pitch()), right)
                .rotate((float) Math.toRadians(head.roll()), forward)
                .translate(-neck.x, -neck.y, -neck.z);
    }

    private static Matrix4f jawMatrix(CreatureModel.Pose pose, float degrees) {
        if (degrees == 0.0F) {
            return new Matrix4f();
        }
        Vector3f hinge = pose.pivot(CreatureModel.JAW_HINGE);
        Vector3f right = pose.pivot(CreatureModel.HEAD_RIGHT).normalize();
        return new Matrix4f().translation(hinge).rotate((float) Math.toRadians(Mth.clamp(degrees, -5.0F, 80.0F)), right)
                .translate(-hinge.x, -hinge.y, -hinge.z);
    }

    /** Vanilla's two fixed lights, turned into this group's model space (the shader does not rotate normals). */
    private static void setLights(Matrix4f model) {
        Matrix3f rotation = new Matrix3f(model).normal().transpose();
        RenderSystem.setShaderLights(rotation.transform(new Vector3f(LIGHT_0)).normalize(), rotation.transform(new Vector3f(LIGHT_1)).normalize());
    }

    private static void restoreLights() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && mc.level.effects().constantAmbientLight()) {
            Lighting.setupNetherLevel();
        } else {
            Lighting.setupLevel();
        }
    }

    /** Two pinpoints: something wet at the bottom of each socket, catching whatever light there is. */
    private static void drawGlints(CreatureModel.Pose pose, Matrix4f base, Matrix4f headM, Matrix4f view,
            MultiBufferSource.BufferSource buffers, int flags) {
        Matrix4f toWorld = new Matrix4f(base).mul(headM);
        Vector3f forward = toWorld.transformDirection(pose.pivot(CreatureModel.HEAD_FORWARD)).normalize();
        Vector3f eyeCenter = toWorld.transformPosition(pose.pivot(CreatureModel.HEAD));
        // Camera is at the origin of this space.
        Vector3f toCamera = new Vector3f(eyeCenter).negate();
        float distance = toCamera.length();
        toCamera.normalize();
        float facing = forward.dot(toCamera);
        if (facing < 0.25F || distance > 40.0F) {
            return;
        }
        float strength = Mth.clamp((facing - 0.25F) / 0.5F, 0.0F, 1.0F) * Mth.clamp(1.4F - distance / 30.0F, 0.0F, 1.0F);
        if ((flags & Phantom.FLAG_LIT) != 0) {
            strength = Math.max(strength, 0.8F);
        }
        int alpha = Math.round(255.0F * 0.55F * strength);
        if (alpha <= 2) {
            return;
        }
        Matrix4f rot = new Matrix4f(view);
        Matrix4f inverse = new Matrix4f(rot).invert();
        Vector3f right = inverse.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = inverse.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        // A pinpoint at any distance: shrink it up close, grow it a little far away.
        float size = Mth.clamp(0.0045F * (float) Math.sqrt(Math.max(0.05F, distance)), 0.0025F, 0.03F);
        VertexConsumer consumer = buffers.getBuffer(RenderType.eyes(GLINT));
        for (int eyeOffset : new int[] {CreatureModel.EYE_LEFT, CreatureModel.EYE_RIGHT}) {
            Vector3f c = toWorld.transformPosition(pose.pivot(eyeOffset));
            // Pulled toward the camera a little so the socket rim does not hide it.
            c.add(new Vector3f(toCamera).mul(0.02F));
            quad(consumer, c, right, up, size, alpha);
        }
        buffers.endBatch(RenderType.eyes(GLINT));
    }

    private static void quad(VertexConsumer consumer, Vector3f c, Vector3f right, Vector3f up, float size, int alpha) {
        float[][] corners = {{-1, -1, 0, 1}, {1, -1, 1, 1}, {1, 1, 1, 0}, {-1, 1, 0, 0}};
        for (float[] k : corners) {
            consumer.addVertex(c.x + (right.x * k[0] + up.x * k[1]) * size, c.y + (right.y * k[0] + up.y * k[1]) * size,
                            c.z + (right.z * k[0] + up.z * k[1]) * size)
                    .setColor(alpha, alpha, alpha, alpha).setUv(k[2], k[3]).setOverlay(OverlayTexture.NO_OVERLAY)
                    .setLight(LightTexture.FULL_BRIGHT).setNormal(0.0F, 0.0F, 1.0F);
        }
    }

    @Nullable
    private static VertexBuffer buffer(CreatureModel m, int g, CreatureModel.Pose pose, int light) {
        CreatureModel.Group group = m.groups[g];
        long key = ((long) g << 56) | ((long) (group.rigid ? 255 : pose.id.ordinal()) << 48) | (light & 0xFFFFFFFFL);
        VertexBuffer buffer = BUFFERS.get(key);
        if (buffer != null) {
            return buffer;
        }
        float[] positions = group.rigid ? group.restPositions : pose.positions[g];
        float[] normals = group.rigid ? group.restNormals : pose.normals[g];
        if (positions == null) {
            return null;
        }
        buffer = upload(group, positions, normals, light);
        BUFFERS.put(key, buffer);
        if (BUFFERS.size() > MAX_BUFFERS) {
            Iterator<Map.Entry<Long, VertexBuffer>> it = BUFFERS.entrySet().iterator();
            Map.Entry<Long, VertexBuffer> eldest = it.next();
            eldest.getValue().close();
            it.remove();
        }
        return buffer;
    }

    private static VertexBuffer upload(CreatureModel.Group group, float[] positions, float[] normals, int light) {
        int[] indices = group.indices;
        float[] uv = group.uv;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(indices.length * DefaultVertexFormat.NEW_ENTITY.getVertexSize())) {
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            for (int i : indices) {
                builder.addVertex(positions[i * 3], positions[i * 3 + 1], positions[i * 3 + 2]).setColor(255, 255, 255, 255)
                        .setUv(uv[i * 2], 1.0F - uv[i * 2 + 1]).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light)
                        .setNormal(normals[i * 3], normals[i * 3 + 1], normals[i * 3 + 2]);
            }
            MeshData mesh = builder.buildOrThrow();
            VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            buffer.bind();
            buffer.upload(mesh);
            VertexBuffer.unbind();
            return buffer;
        }
    }
}
