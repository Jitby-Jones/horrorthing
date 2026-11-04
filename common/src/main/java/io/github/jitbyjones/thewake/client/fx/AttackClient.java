package io.github.jitbyjones.thewake.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import io.github.jitbyjones.thewake.client.creature.CreatureModel;
import io.github.jitbyjones.thewake.client.creature.CreatureRenderer;
import io.github.jitbyjones.thewake.client.render.WorldRender;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.haunt.Attack;
import io.github.jitbyjones.thewake.net.AttackPayload;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;
import org.joml.Vector3f;

/**
 * The client half of an attack. The menu is torn away; your view is wrenched round to where it
 * was; its face comes at you until it is all there is, the jaw opening wider than a jaw opens; the
 * picture tears. A short distorted scream (the one loud sound in the mod). Black. Gone.
 */
public final class AttackClient {
    private static final int FACE_FROM = 2;
    /** Ticks after its face arrives: it blinks, slowly, then the jaw goes. */
    private static final int BLINK_FROM = 2;
    private static final int BLINK_SHUT = 7;
    private static final int BLINK_OPEN = 9;
    private static final int JAW_FROM = 10;
    private static final int BLACK_AT = 34;
    private static final int END = 46;
    private static final RandomSource RANDOM = RandomSource.create();

    private static int age = -1;
    private static Vec3 face = Vec3.ZERO;
    private static float fromYaw;
    private static float fromPitch;
    private static float toYaw;
    private static float toPitch;
    private static float roll;

    private AttackClient() {}

    public static boolean active() {
        return age >= 0 && age < BLACK_AT;
    }

    public static void begin(AttackPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.screen != null && !(mc.screen instanceof DeathScreen)) {
            mc.setScreen(null);
        }
        age = 0;
        face = new Vec3(payload.x(), payload.y(), payload.z());
        fromYaw = mc.player.getYRot();
        fromPitch = mc.player.getXRot();
        Vec3 d = face.subtract(mc.player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        toYaw = fromYaw + Mth.wrapDegrees(yaw - fromYaw);
        toPitch = Mth.clamp((float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))), -25.0F, 25.0F);
        roll = (RANDOM.nextFloat() - 0.5F) * 40.0F;
        play(WakeRegistry.STATIC.get(), 0.9F * WakeConfig.get().soundVolume, 0.8F);
        TapeEffect.tear(1.0F);
    }

    public static void tick() {
        if (age < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            age = -1;
            return;
        }
        age++;
        if (age < BLACK_AT && mc.screen != null && !(mc.screen instanceof DeathScreen)) {
            mc.setScreen(null);
        }
        if (age == FACE_FROM + JAW_FROM) {
            play(WakeRegistry.SCREAM.get(), WakeConfig.get().screamVolume, 0.92F + RANDOM.nextFloat() * 0.12F);
        }
        if (age < BLACK_AT) {
            // Quiet while it blinks; the tape tears when the jaw goes, and worst at the bite.
            float tear = age < FACE_FROM + JAW_FROM ? 0.25F : 0.55F;
            TapeEffect.tear(age == Attack.BITE_TICK ? 1.0F : tear);
        }
        if (age == BLACK_AT) {
            Overlays.blackout(9);
            play(WakeRegistry.STATIC.get(), 0.6F * WakeConfig.get().soundVolume, 1.1F);
        }
        if (age >= END) {
            age = -1;
        }
    }

    /** Each frame, before the world renders: the wrench. Your view is not yours for a moment. */
    public static void frame(float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (age < 0 || age >= BLACK_AT || mc.player == null) {
            return;
        }
        float t = Mth.clamp((age + partial) / 3.0F, 0.0F, 1.0F);
        float eased = 1.0F - (1.0F - t) * (1.0F - t) * (1.0F - t);
        float shake = age >= FACE_FROM ? 1.2F : 0.0F;
        float yaw = Mth.lerp(eased, fromYaw, toYaw) + (RANDOM.nextFloat() - 0.5F) * shake;
        float pitch = Mth.lerp(eased, fromPitch, toPitch) + (RANDOM.nextFloat() - 0.5F) * shake;
        mc.player.setYRot(yaw);
        mc.player.setXRot(pitch);
        mc.player.yRotO = yaw;
        mc.player.xRotO = pitch;
        mc.player.setYHeadRot(yaw);
        mc.player.yHeadRotO = yaw;
    }

    /** Its face, over the finished world, in front of everything. */
    public static void renderOverWorld(float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (age < FACE_FROM || age >= BLACK_AT || !WorldRender.captured || mc.level == null) {
            return;
        }
        // Whichever temper it is in now: its own model.
        CreatureRenderer.face(io.github.jitbyjones.thewake.client.ClientState.form);
        CreatureModel model = CreatureRenderer.model();
        if (model == null) {
            return;
        }
        Camera camera = mc.gameRenderer.getMainCamera();
        float t = age - FACE_FROM + partial;
        float near = Mth.clamp(t / 8.0F, 0.0F, 1.0F);
        // Close enough to fill the screen, not so close that the jaw drops out of the picture.
        float dist = Mth.lerp(near * near * (3.0F - 2.0F * near), 1.7F, 0.52F);
        Vector3f look = camera.getLookVector();
        int frame = Mth.clamp((int) (t * 0.9F), 0, CreaturePose.LUNGE.frames - 1);
        Vector3f head = CreatureRenderer.attachment(CreaturePose.LUNGE, frame, "head");
        Vector3f mouth = CreatureRenderer.attachment(CreaturePose.LUNGE, frame, "mouth");
        if (head != null && mouth != null) {
            // Aim between the eyes and the mouth, so the whole face and the chin as it drops are in view.
            head.lerp(mouth, 0.45F);
        }
        Vector3f forward = CreatureRenderer.attachment(CreaturePose.LUNGE, frame, "headForward");
        if (head == null || forward == null) {
            return;
        }
        float yaw = camera.getYRot() + 180.0F;
        // Its head where your eyes are looking.
        float rad = (float) Math.toRadians(-yaw);
        float hx = head.x * Mth.cos(rad) + head.z * Mth.sin(rad);
        float hz = -head.x * Mth.sin(rad) + head.z * Mth.cos(rad);
        float jitter = 0.012F;
        Vec3 relative = new Vec3(look.x * dist - hx + (RANDOM.nextFloat() - 0.5F) * jitter,
                look.y * dist - head.y + (RANDOM.nextFloat() - 0.5F) * jitter,
                look.z * dist - hz + (RANDOM.nextFloat() - 0.5F) * jitter);
        // Face toward the camera: level out the pose's own tilt against where you are looking from.
        float have = (float) Math.toDegrees(Math.atan2(forward.y, Math.sqrt(forward.x * forward.x + forward.z * forward.z)));
        float want = (float) Math.toDegrees(Math.asin(Mth.clamp(-look.y, -1.0F, 1.0F)));
        // One slow blink. Then the eyes, wide. Then the stitches go and the chin keeps coming.
        float lids;
        if (t < BLINK_FROM) {
            lids = 0.15F;
        } else if (t < BLINK_SHUT) {
            lids = Mth.lerp((t - BLINK_FROM) / (BLINK_SHUT - BLINK_FROM), 0.15F, 1.0F);
        } else if (t < BLINK_OPEN) {
            lids = 1.0F;
        } else {
            lids = Math.max(0.0F, 1.0F - (t - BLINK_OPEN) * 1.5F);
        }
        float jaw = t < JAW_FROM ? 0.0F : Math.min(120.0F, (t - JAW_FROM) * 7.5F) + (RANDOM.nextFloat() - 0.5F) * 3.0F;
        CreatureRenderer.Head h = new CreatureRenderer.Head((RANDOM.nextFloat() - 0.5F) * 2.0F, -(want - have),
                roll * (1.0F - near * 0.6F), jaw, 0.0F, 0.0F, lids, 1.0F, 0.0F);
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(WorldRender.VIEW);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.clear(256, Minecraft.ON_OSX);
        // Its own lens, whatever your field of view: the face fills the screen the same way for everyone.
        float aspect = (float) mc.getWindow().getWidth() / Math.max(1, mc.getWindow().getHeight());
        Matrix4f lens = new Matrix4f().setPerspective((float) Math.toRadians(70.0), aspect, 0.05F, 64.0F);
        CreatureRenderer.draw(CreaturePose.LUNGE, frame, WorldRender.VIEW, lens, relative, yaw, 1.0F, h,
                LightTexture.FULL_BRIGHT, mc.renderBuffers().bufferSource(), Phantom.FLAG_LIT);
        RenderSystem.clear(256, Minecraft.ON_OSX);
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    private static void play(SoundEvent sound, float volume, float pitch) {
        if (volume <= 0.0F) {
            return;
        }
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
    }

    public static void reset() {
        age = -1;
    }
}
