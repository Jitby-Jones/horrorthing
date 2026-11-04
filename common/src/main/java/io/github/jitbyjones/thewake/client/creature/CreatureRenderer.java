package io.github.jitbyjones.thewake.client.creature;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.phantom.Phantom;
import java.util.HashMap;
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
 * Draws the creature from its skeleton. Every frame the pose is rebuilt from a clip frame plus
 * procedural layers (the head turning, the jaw, twitches in the corner of your eye); the body is
 * skinned on the CPU only when that pose actually changed, which in stop-motion is a few times a
 * second at most. Rigid parts ride on their joints by matrix.
 *
 * <p>Uses vanilla's entity shader and lightmap, so it is exactly as dark as the corner it stands
 * in, and shader packs see an ordinary entity draw.
 */
public final class CreatureRenderer {
    public static final ResourceLocation GLINT = TheWake.id("textures/entity/creature_glint.png");
    private static final Vector3f LIGHT_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LIGHT_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f UP = new Vector3f(0.0F, 1.0F, 0.0F);
    private static final int MAX_SKINNED = 6;

    /**
     * One model per temper (see tools/creature/build_form.py): the WATCHER's, and UNEASY's, ANGRY's
     * and FURIOUS's own, each with its own skeleton, parts and clips. A temper whose model is
     * missing falls back to the WATCHER's, wearing that temper's face.
     */
    private static final class Slot {
        final int index;
        @Nullable
        CreatureModel model;
        boolean failed;
        @Nullable
        CreatureSkeleton skeleton;
        @Nullable
        CreatureSkeleton probe;
        Joints joints;

        Slot(int index) {
            this.index = index;
        }
    }

    private static final String[] FORM_MODELS = {null, "uneasy", "angry", "furious"};
    private static final Slot[] SLOTS = {new Slot(0), new Slot(1), new Slot(2), new Slot(3)};
    /** The slot being drawn or asked about (set from {@link #face}). */
    private static Slot slot = SLOTS[0];
    @Nullable
    private static CreatureSkeleton skeleton;
    @Nullable
    private static CreatureSkeleton probe;
    private static Joints joints;
    /** Skinned bodies by pose fingerprint and light. Least recently used first. */
    private static final LinkedHashMap<Long, VertexBuffer> SKINNED = new LinkedHashMap<>(8, 0.75F, true);
    /** Rigid parts by group and light. */
    private static final Map<Long, VertexBuffer> RIGID = new HashMap<>();

    private CreatureRenderer() {}

    /** Its temper, as a face: the card it wears is worse in each. */
    private static int face;
    private static final ResourceLocation[] FACES = {
            null, TheWake.id("textures/entity/creature_head_uneasy.png"), TheWake.id("textures/entity/creature_head_angry.png"),
            TheWake.id("textures/entity/creature_head_furious.png")};
    private static final Map<ResourceLocation, Boolean> PRESENT = new HashMap<>();

    /** Which temper is drawn next: its own model, or the WATCHER's with its face. */
    public static void face(int form) {
        face = Mth.clamp(form, 0, FACES.length - 1);
        slot = resolve(face);
        skeleton = slot.skeleton;
        probe = slot.probe;
        joints = slot.joints;
    }

    private static Slot resolve(int form) {
        Slot s = SLOTS[form];
        if (s.model == null && !s.failed) {
            load(s);
        }
        if (s.model == null && form != 0) {
            s = SLOTS[0];
            if (s.model == null && !s.failed) {
                load(s);
            }
        }
        return s;
    }

    private static void load(Slot s) {
        try {
            ResourceLocation at = s.index == 0 ? CreatureModel.LOCATION
                    : TheWake.id("models/creature/creature_" + FORM_MODELS[s.index] + ".bin.gz");
            s.model = CreatureModel.load(Minecraft.getInstance().getResourceManager(), at);
            s.skeleton = new CreatureSkeleton(s.model);
            s.probe = new CreatureSkeleton(s.model);
            s.joints = Joints.of(s.model);
        } catch (Exception e) {
            s.failed = true;
            if (s.index == 0) {
                TheWake.LOG.error("Could not load the creature model", e);
            } else {
                TheWake.LOG.warn("No model for {}; it will wear its face on the WATCHER's", FORM_MODELS[s.index]);
            }
        }
    }

    private static ResourceLocation texture(CreatureModel.Group group) {
        if (face > 0 && slot.index == 0 && group.texture.getPath().endsWith("creature_head.png")) {
            ResourceLocation alt = FACES[face];
            boolean present = PRESENT.computeIfAbsent(alt, id -> Minecraft.getInstance().getResourceManager().getResource(id).isPresent());
            if (present) {
                return alt;
            }
        }
        return group.texture;
    }

    /** The light at a point and a height above it: its own corner, not yours. */
    public static int lightAt(net.minecraft.world.level.Level level, Vec3 at, float height) {
        BlockPos head = BlockPos.containing(at.x, at.y + Math.min(height * 0.85, height - 0.2), at.z);
        int atHead = LevelRenderer.getLightColor(level, head);
        int atBody = LevelRenderer.getLightColor(level, BlockPos.containing(at.x, at.y + 0.5, at.z));
        return LightTexture.pack(Math.max(LightTexture.block(atHead), LightTexture.block(atBody)),
                Math.max(LightTexture.sky(atHead), LightTexture.sky(atBody)));
    }

    /** Joints the procedural layers need, looked up once by name. */
    private record Joints(int[] neck, int head, int jaw, int shoulderL, int shoulderR, int[] fingersL, int[] fingersR,
            int eyeJointL, int eyeJointR, int lidL, int lidR,
            CreatureModel.Attachment headCentre, CreatureModel.Attachment right, CreatureModel.Attachment forward,
            CreatureModel.Attachment up, CreatureModel.Attachment eyeL, CreatureModel.Attachment eyeR) {
        static Joints of(CreatureModel m) {
            // The head turns on the last three joints of the neck, however long the neck is.
            int top = m.joint("neck2");
            int mid = top >= 0 ? m.parents[top] : -1;
            int low = mid >= 0 ? m.parents[mid] : -1;
            int[] neck = java.util.stream.IntStream.of(low, mid, top).filter(j -> j >= 0).toArray();
            int head = -1;
            int jaw = -1;
            for (CreatureModel.Group g : m.groups) {
                if (g.rigid && "head".equals(g.name)) {
                    head = g.joint;
                } else if (g.rigid && "jaw".equals(g.name)) {
                    jaw = g.joint;
                }
            }
            return new Joints(neck, head, jaw, m.joint("shoulder_L"), m.joint("shoulder_R"), fingers(m, "L"), fingers(m, "R"),
                    m.joint("eye_L"), m.joint("eye_R"), m.joint("lid_L"), m.joint("lid_R"),
                    m.attachment("head"), m.attachment("headRight"), m.attachment("headForward"), m.attachment("headUp"),
                    m.attachment("eyeLeft"), m.attachment("eyeRight"));
        }

        private static int[] fingers(CreatureModel m, String side) {
            return java.util.stream.IntStream.range(0, m.jointCount())
                    .filter(j -> m.jointNames[j].matches("f\\dk[123]_" + side)).toArray();
        }
    }

    @Nullable
    public static CreatureModel model() {
        if (slot.model == null && !slot.failed || slot.skeleton == null) {
            face(face);
        }
        return slot.model;
    }

    /** Resource reload: drop the mesh and every buffer; they are rebuilt on demand. */
    public static void invalidate() {
        RenderSystem.assertOnRenderThread();
        SKINNED.values().forEach(VertexBuffer::close);
        SKINNED.clear();
        RIGID.values().forEach(VertexBuffer::close);
        RIGID.clear();
        for (int i = 0; i < SLOTS.length; i++) {
            SLOTS[i] = new Slot(i);
        }
        slot = SLOTS[0];
        skeleton = null;
        probe = null;
        joints = null;
        PRESENT.clear();
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
        int block = Math.max(LightTexture.block(atHead), LightTexture.block(atBody));
        int sky = Math.max(LightTexture.sky(atHead), LightTexture.sky(atBody));
        return LightTexture.pack(block, sky);
    }

    /**
     * Procedural offsets for this frame on top of the clip: the head (degrees, relative to the
     * pose), the jaw, a curl of the fingers and a hitch of the shoulders.
     */
    public record Head(float yaw, float pitch, float roll, float jaw, float fingers, float shrug, float lids, float eyesOnYou,
            float eyesApart) {
        public static final Head NONE = new Head(0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.2F, 1.0F, 0.0F);

        public Head(float yaw, float pitch, float roll, float jaw) {
            this(yaw, pitch, roll, jaw, 0.0F, 0.0F, 0.2F, 1.0F, 0.0F);
        }

        public Head withLids(float closed) {
            return new Head(yaw, pitch, roll, jaw, fingers, shrug, closed, eyesOnYou, eyesApart);
        }

        /** Its body: the server turns the head; the temper and the pins hang the card. */
        public static Head of(io.github.jitbyjones.thewake.entity.WakeEntity body, float partial) {
            int form = body.form().ordinal();
            int pins = body.pins();
            boolean shut = body.hasFlag(io.github.jitbyjones.thewake.entity.WakeEntity.FLAG_EYES_SHUT);
            float roll = form * 6.0F + pins * 8.0F;
            float jaw = body.jaw() + form * 5.0F + (pins >= 3 ? 18.0F : 0.0F);
            float twitch = form >= 2 && body.clientAge % 37 < 2 ? (body.clientAge % 2 == 0 ? 6.0F : -6.0F) : 0.0F;
            return new Head(body.headYaw() + twitch, body.headPitch() + pins * 4.0F, roll, jaw, form * 8.0F, form * 3.0F,
                    shut ? 1.0F : 0.2F + form * 0.05F, 1.0F, form >= 3 ? 6.0F : 0.0F);
        }

        public static Head of(ClientPhantom p, float partial) {
            float yaw = p.lerpHeadYaw(partial);
            float pitch = p.lerpHeadPitch(partial);
            float jaw = p.lerpJaw(partial);
            Gaze g = p.gaze;
            if (g.looking && !p.scriptedHead()) {
                yaw = g.lookYaw;
                pitch = g.lookPitch;
            }
            return new Head(yaw + g.twitchYaw, pitch + g.twitchPitch, g.twitchRoll, jaw + g.twitchJaw, g.twitchFingers, g.twitchShrug,
                    g.lids, g.eyesOnYou, g.eyesApart);
        }
    }

    public static void render(ClientPhantom p, Matrix4f view, Matrix4f projection, Vec3 relative, float partial, int light,
            @Nullable MultiBufferSource.BufferSource glints) {
        draw(p.pose, p.frame, view, projection, relative, p.yaw, p.scale, Head.of(p, partial), light, glints, p.flags);
    }

    /**
     * Where an attachment ("head", "eyeLeft", "headForward"...) is in model space for a pose and
     * frame, before any head turning. Null if the model or attachment is missing.
     */
    @Nullable
    public static Vector3f attachment(CreaturePose pose, int frame, String name) {
        CreatureModel m = model();
        if (m == null || probe == null) {
            return null;
        }
        CreatureModel.Attachment a = m.attachment(name);
        if (a == null) {
            return null;
        }
        probe.setFrame(clip(m, pose), frame);
        return probe.attachment(a, new Vector3f());
    }

    private static CreatureModel.Clip clip(CreatureModel m, CreaturePose pose) {
        CreatureModel.Clip c = m.clip(pose.clipName());
        return c != null ? c : m.clips.getOrDefault("stand", m.clips.values().iterator().next());
    }

    public static void draw(CreaturePose poseId, int frame, Matrix4f view, Matrix4f projection, Vec3 relative, float yaw, float scale,
            Head head, int light, @Nullable MultiBufferSource.BufferSource glints, int flags) {
        CreatureModel m = model();
        CreatureSkeleton s = skeleton;
        if (m == null || s == null) {
            return;
        }
        s.setFrame(clip(m, poseId), frame);
        Matrix4f toModel = new Matrix4f().translation((float) relative.x, (float) relative.y, (float) relative.z)
                .rotateY((float) Math.toRadians(-yaw)).scale(scale).invert();
        bend(s, head, toModel.transformPosition(new Vector3f()));
        if ((poseId == CreaturePose.LIE || poseId == CreaturePose.RISE) && joints.headCentre() != null) {
            // Lying down, it is placed by its head: on the pillow, the rest of it too long for the
            // bed, past the foot. Getting up, it keeps that place while it sits up.
            Vector3f anchor = poseId == CreaturePose.LIE ? s.attachment(joints.headCentre(), new Vector3f())
                    : attachment(CreaturePose.LIE, 0, "head");
            if (anchor == null) {
                anchor = new Vector3f();
            }
            float rad = (float) Math.toRadians(-yaw);
            double hx = (anchor.x * Mth.cos(rad) + anchor.z * Mth.sin(rad)) * scale;
            double hz = (-anchor.x * Mth.sin(rad) + anchor.z * Mth.cos(rad)) * scale;
            relative = relative.subtract(hx, 0.0, hz);
        }
        Matrix4f base = new Matrix4f().translation((float) relative.x, (float) relative.y, (float) relative.z)
                .rotateY((float) Math.toRadians(-yaw)).scale(scale);
        long fingerprint = s.fingerprint();
        for (int g = 0; g < m.groups.length; g++) {
            CreatureModel.Group group = m.groups[g];
            Matrix4f model = new Matrix4f(base);
            VertexBuffer buffer;
            if (group.rigid) {
                model.mul(s.matrix(group.joint, new Matrix4f()));
                buffer = rigid(group, g, light);
            } else {
                buffer = skinned(group, g, s, fingerprint, light);
            }
            RenderType type = RenderType.entityCutoutNoCull(texture(group));
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
            drawGlints(s, base, view, glints, flags);
        }
    }

    /** The procedural layers, applied to the clip's pose. */
    private static void bend(CreatureSkeleton s, Head head, Vector3f camera) {
        Joints j = joints;
        if (head.shrug() != 0.0F) {
            s.rotate(j.shoulderL(), (float) Math.toRadians(head.shrug()), new Vector3f(0.0F, 0.0F, 1.0F));
            s.rotate(j.shoulderR(), (float) Math.toRadians(-head.shrug() * 0.4F), new Vector3f(0.0F, 0.0F, 1.0F));
        }
        if (head.fingers() != 0.0F) {
            // Curl: about each finger's own sideways axis, which for hanging fingers is mostly Z.
            float a = (float) Math.toRadians(head.fingers());
            for (int f : j.fingersL()) {
                s.rotate(f, a, new Vector3f(0.0F, 0.0F, 1.0F));
            }
            for (int f : j.fingersR()) {
                s.rotate(f, -a * 0.6F, new Vector3f(0.0F, 0.0F, 1.0F));
            }
        }
        if (j.right() == null || j.forward() == null || j.neck().length == 0) {
            return;
        }
        if (head.yaw() != 0.0F || head.pitch() != 0.0F || head.roll() != 0.0F) {
            // Spread over the neck, most of it at the top.
            float[] share = j.neck().length == 3 ? new float[] {0.2F, 0.3F, 0.5F} : evenShares(j.neck().length);
            for (int i = 0; i < j.neck().length; i++) {
                int joint = j.neck()[i];
                Vector3f right = s.attachment(j.right(), new Vector3f());
                s.rotate(joint, (float) Math.toRadians(-head.yaw() * share[i]), UP);
                s.rotate(joint, (float) Math.toRadians(head.pitch() * share[i]), right);
            }
            Vector3f forward = s.attachment(j.forward(), new Vector3f());
            s.rotate(j.neck()[j.neck().length - 1], (float) Math.toRadians(head.roll()), forward);
        }
        if (head.jaw() != 0.0F && j.jaw() >= 0 && j.up() != null) {
            // A dummy's chin piece: it drops straight down its two cuts, tipping a little.
            float degrees = Mth.clamp(head.jaw(), 0.0F, 120.0F);
            Vector3f right = s.attachment(j.right(), new Vector3f());
            Vector3f down = s.attachment(j.up(), new Vector3f()).mul(-degrees / 60.0F * 0.07F);
            s.rotate(j.jaw(), (float) Math.toRadians(Math.min(degrees, 60.0F) * 0.25F), right);
            s.translate(j.jaw(), down);
        }
        eyes(s, head, camera);
    }

    /** Eyes find you (as far as an eye turns), unless this face's eyes are not quite right; lids close over them. */
    private static void eyes(CreatureSkeleton s, Head head, Vector3f camera) {
        Joints j = joints;
        if (j.eyeL() == null || j.forward() == null || j.up() == null) {
            return;
        }
        Vector3f forward = s.attachment(j.forward(), new Vector3f());
        Vector3f up = s.attachment(j.up(), new Vector3f());
        Vector3f right = s.attachment(j.right(), new Vector3f());
        int[] eyeJoints = {j.eyeJointL(), j.eyeJointR()};
        CreatureModel.Attachment[] eyes = {j.eyeL(), j.eyeR()};
        for (int i = 0; i < 2; i++) {
            if (eyeJoints[i] < 0) {
                continue;
            }
            Vector3f at = s.attachment(eyes[i], new Vector3f());
            Vector3f to = new Vector3f(camera).sub(at).normalize();
            Vector3f want = new Vector3f(forward).lerp(to, Mth.clamp(head.eyesOnYou(), 0.0F, 1.0F)).normalize();
            float angle = forward.angle(want);
            float max = (float) Math.toRadians(28.0);
            if (angle > max) {
                want = new Vector3f(forward).lerp(want, max / angle).normalize();
            }
            s.rotate(eyeJoints[i], new org.joml.Quaternionf().rotationTo(forward, want));
            if (head.eyesApart() != 0.0F) {
                s.rotate(eyeJoints[i], (float) Math.toRadians(head.eyesApart() * (i == 0 ? 1.0F : -0.8F)), up);
            }
        }
        float closed = Mth.clamp(head.lids(), 0.0F, 1.0F);
        if (j.lidL() >= 0 && closed > 0.0F) {
            // From the modelled rest (a quarter closed) down over the eye.
            float a = (float) Math.toRadians((closed - 0.2F) * 75.0F);
            s.rotate(j.lidL(), a, right);
            s.rotate(j.lidR(), a, right);
        }
    }

    private static float[] evenShares(int n) {
        float[] out = new float[n];
        java.util.Arrays.fill(out, 1.0F / n);
        return out;
    }

    private static VertexBuffer rigid(CreatureModel.Group group, int g, int light) {
        long key = ((long) slot.index << 56) | ((long) g << 40) | (light & 0xFFFFFFFFL);
        VertexBuffer buffer = RIGID.get(key);
        if (buffer == null) {
            if (RIGID.size() > 600) {
                RIGID.values().forEach(VertexBuffer::close);
                RIGID.clear();
            }
            buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
            CreatureSkin.uploadRigid(buffer, group, light);
            RIGID.put(key, buffer);
        }
        return buffer;
    }

    private static VertexBuffer skinned(CreatureModel.Group group, int g, CreatureSkeleton s, long fingerprint, int light) {
        long key = fingerprint * 31L + ((long) slot.index << 58) + ((long) g << 40) + (light & 0xFFFFFFFFL);
        VertexBuffer buffer = SKINNED.get(key);
        if (buffer != null) {
            return buffer;
        }
        if (SKINNED.size() >= MAX_SKINNED) {
            Iterator<Map.Entry<Long, VertexBuffer>> it = SKINNED.entrySet().iterator();
            Map.Entry<Long, VertexBuffer> eldest = it.next();
            buffer = eldest.getValue();
            it.remove();
        } else {
            buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        CreatureSkin.uploadSkinned(buffer, group, s, light);
        SKINNED.put(key, buffer);
        return buffer;
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

    /** Two pinpoints: something wet in each eye, catching whatever light there is. */
    private static void drawGlints(CreatureSkeleton s, Matrix4f base, Matrix4f view, MultiBufferSource.BufferSource buffers, int flags) {
        Joints j = joints;
        if (j.eyeL() == null || j.eyeR() == null || j.forward() == null || j.headCentre() == null) {
            return;
        }
        Vector3f forward = base.transformDirection(s.attachment(j.forward(), new Vector3f())).normalize();
        Vector3f eyeCenter = base.transformPosition(s.attachment(j.headCentre(), new Vector3f()));
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
        Matrix4f inverse = new Matrix4f(view).invert();
        Vector3f right = inverse.transformDirection(new Vector3f(1.0F, 0.0F, 0.0F));
        Vector3f up = inverse.transformDirection(new Vector3f(0.0F, 1.0F, 0.0F));
        float size = Mth.clamp(0.0045F * (float) Math.sqrt(Math.max(0.05F, distance)), 0.0025F, 0.03F);
        VertexConsumer consumer = buffers.getBuffer(RenderType.eyes(GLINT));
        for (CreatureModel.Attachment eye : new CreatureModel.Attachment[] {j.eyeL(), j.eyeR()}) {
            Vector3f c = base.transformPosition(s.attachment(eye, new Vector3f()));
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
}
