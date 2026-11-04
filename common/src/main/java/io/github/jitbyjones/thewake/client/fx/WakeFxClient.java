package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.client.ClientState;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * The client half of the chase and its powers: the flash going off in your face, the picture
 * shaking, the tape skipping, your head being turned, the lights stuttering, the lost-time
 * counter, the scream in your ears, the drone and your heart under a chase.
 */
public final class WakeFxClient {
    private static final RandomSource RANDOM = RandomSource.create();

    private static long flashStart = -100;
    private static float flashAmount;
    private static long shakeEnd;
    private static float shakeAmount;
    private static long flickerEnd;
    private static float flickerAmount;
    private static long lookEnd;
    private static long lookStart;
    private static Vec3 lookAt = Vec3.ZERO;
    private static long lostStart = -1000;
    private static int lostSeconds;
    private static long shutterStart = -100;
    private static boolean held;
    private static long removedStart = -1000;
    private static Loop drone;
    private static Loop heart;

    private WakeFxClient() {}

    public static void handle(WakeFxPayload fx) {
        Minecraft mc = Minecraft.getInstance();
        long t = Overlays.ticks();
        switch (fx.kind()) {
            case WakeFxPayload.SCREAM_NEAR -> {
                SoundEvent scream = WakeRegistry.CHASE_SCREAM.get();
                mc.getSoundManager().play(new SimpleSoundInstance(scream.getLocation(), SoundSource.HOSTILE, fx.amount(), fx.extra(),
                        SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
                shake(0.5F * fx.amount(), 12);
                TapeEffect.tear(0.6F * fx.amount());
            }
            case WakeFxPayload.FLASH -> {
                flashStart = t;
                flashAmount = fx.amount();
            }
            case WakeFxPayload.SHAKE -> shake(fx.amount(), (int) fx.extra());
            case WakeFxPayload.TAPE_SKIP -> {
                TapeEffect.tear(fx.amount());
                Overlays.blackout(1);
            }
            case WakeFxPayload.LOOK_AT -> {
                lookStart = t;
                lookEnd = t + Math.max(4, (int) fx.extra());
                lookAt = new Vec3(fx.x(), fx.y(), fx.z());
            }
            case WakeFxPayload.FLICKER -> {
                flickerEnd = t + (long) fx.extra();
                flickerAmount = fx.amount();
            }
            case WakeFxPayload.HELD -> held = fx.extra() > 0.5F;
            case WakeFxPayload.REMOVED -> removedStart = t;
            case WakeFxPayload.CHASE -> {
                ClientState.chasing = fx.amount() > 0.5F;
                TapeEffect.tear(0.4F);
            }
            case WakeFxPayload.FORCE_SCREEN -> force((int) fx.amount());
            case WakeFxPayload.LOST_TIME -> {
                lostStart = t;
                lostSeconds = (int) fx.amount();
            }
            case WakeFxPayload.SHUTTER -> shutterStart = t;
            case WakeFxPayload.PARALYSIS, WakeFxPayload.FAKE_DAWN -> {
            }
            default -> {
            }
        }
    }

    /** It opens a screen on you, so you are not watching. */
    private static void force(int kind) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        switch (kind) {
            case 0 -> mc.setScreen(new InventoryScreen(mc.player));
            case 1 -> mc.setScreen(new ChatScreen(""));
            default -> mc.pauseGame(false);
        }
        TapeEffect.tear(0.5F);
    }

    public static void shake(float amount, int ticks) {
        shakeAmount = Math.max(shakeAmount * (Overlays.ticks() < shakeEnd ? 1.0F : 0.0F), amount);
        shakeEnd = Math.max(shakeEnd, Overlays.ticks() + ticks);
    }

    public static boolean held() {
        return held;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (mc.player.getVehicle() instanceof io.github.jitbyjones.thewake.entity.WakeEntity) {
            // "Press shift to dismount" is not something it would tell you.
            mc.gui.setOverlayMessage(net.minecraft.network.chat.Component.empty(), false);
        }
        boolean chase = ClientState.chasing;
        float volume = WakeConfig.get().soundVolume;
        if (chase && (drone == null || drone.isStopped()) && volume > 0.0F) {
            drone = new Loop(WakeRegistry.CHASE_DRONE.get(), 0.7F * volume, 1.0F);
            mc.getSoundManager().play(drone);
        }
        if (drone != null) {
            drone.want = chase ? 0.75F * volume : 0.0F;
        }
        boolean afraid = chase || ClientState.tension > 0.82F;
        if (afraid && (heart == null || heart.isStopped()) && volume > 0.0F) {
            heart = new Loop(WakeRegistry.HEARTBEAT.get(), 0.0F, 1.0F);
            mc.getSoundManager().play(heart);
        }
        if (heart != null) {
            heart.want = afraid ? (chase ? 0.9F : 0.45F) * volume : 0.0F;
            heart.pitchWant = chase ? 1.25F : 1.0F;
        }
    }

    /** Each frame, before the level: the shake and the turned head. */
    public static void frame(float partial) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        long t = Overlays.ticks();
        if (t < lookEnd) {
            Vec3 d = lookAt.subtract(mc.player.getEyePosition());
            float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
            float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
            float k = 0.35F;
            float y = mc.player.getYRot() + Mth.wrapDegrees(yaw - mc.player.getYRot()) * k;
            float p = mc.player.getXRot() + (pitch - mc.player.getXRot()) * k;
            mc.player.setYRot(y);
            mc.player.setXRot(p);
        }
        if (t < shakeEnd && shakeAmount > 0.0F) {
            float fade = Mth.clamp((shakeEnd - t - partial) / 10.0F, 0.0F, 1.0F);
            float a = shakeAmount * fade * 2.2F;
            mc.player.setYRot(mc.player.getYRot() + (RANDOM.nextFloat() - 0.5F) * a);
            mc.player.setXRot(Mth.clamp(mc.player.getXRot() + (RANDOM.nextFloat() - 0.5F) * a, -90.0F, 90.0F));
        }
    }

    /** Over the HUD. */
    public static void render(GuiGraphics graphics, float partial) {
        Minecraft mc = Minecraft.getInstance();
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        long t = Overlays.ticks();
        float now = t + partial;
        // The flash: white, all at once, then draining away, and a green after-image.
        float since = now - flashStart;
        if (since < 26.0F) {
            float white = since < 2.0F ? 1.0F : Mth.clamp(1.0F - (since - 2.0F) / 16.0F, 0.0F, 1.0F);
            int a = Math.round(white * 235.0F * flashAmount);
            if (a > 2) {
                graphics.fill(0, 0, w, h, (a << 24) | 0xFFFDF4);
            }
        }
        // Someone else's flash, behind you: only the edges of the picture go white.
        float shutter = now - shutterStart;
        if (shutter < 8.0F) {
            int a = Math.round(Mth.clamp(1.0F - shutter / 8.0F, 0.0F, 1.0F) * 120.0F);
            graphics.fillGradient(0, 0, w / 5, h, (a << 24) | 0xFFFFFF, 0x00FFFFFF);
            graphics.fillGradient(w - w / 5, 0, w, h, 0x00FFFFFF, (a << 24) | 0xFFFFFF);
        }
        // The lights stutter.
        if (t < flickerEnd) {
            boolean off = RANDOM.nextInt(4) == 0 || ((t / 2) % 3 == 0 && RANDOM.nextBoolean());
            if (off) {
                int a = Math.round(flickerAmount * 200.0F);
                graphics.fill(0, 0, w, h, (a << 24) | 0x020306);
            }
        }
        // Held up in front of its face: the picture closes in from the edges.
        if (held) {
            int a = 150;
            graphics.fillGradient(0, 0, w, h / 4, (a << 24), 0);
            graphics.fillGradient(0, h - h / 4, w, h, 0, (a << 24));
        }
        // Lost time, on the counter, the way a VCR shows it.
        float lost = now - lostStart;
        if (lost < 90.0F && lostSeconds > 0) {
            boolean blink = lost < 40.0F || ((int) lost / 8) % 2 == 0;
            if (blink) {
                Font font = mc.font;
                int hours = lostSeconds / 3600;
                int minutes = (lostSeconds / 60) % 60;
                int seconds = lostSeconds % 60;
                String text = String.format(Locale.ROOT, "-%d:%02d:%02d", hours, minutes, seconds);
                int alpha = Math.round(Mth.clamp((90.0F - lost) / 20.0F, 0.0F, 1.0F) * 255.0F);
                if (alpha > 8) {
                    int color = (alpha << 24) | 0xE8E8E8;
                    graphics.pose().pushPose();
                    graphics.pose().translate(w - 18 - font.width(text) * 2, 16, 0);
                    graphics.pose().scale(2.0F, 2.0F, 1.0F);
                    graphics.drawString(font, text, 0, 0, color, true);
                    graphics.drawString(font, "◀◀", 0, 12, color, true);
                    graphics.pose().popPose();
                }
            }
        }
        // It has gone: a moment of white, as when a tape runs out.
        float gone = now - removedStart;
        if (gone < 60.0F) {
            int a = Math.round(Mth.clamp(1.0F - gone / 60.0F, 0.0F, 1.0F) * 200.0F);
            graphics.fill(0, 0, w, h, (a << 24) | 0x101828);
        }
    }

    public static void reset() {
        held = false;
        ClientState.chasing = false;
        flickerEnd = 0;
        shakeEnd = 0;
        lookEnd = 0;
        if (drone != null) {
            drone.want = 0.0F;
        }
        if (heart != null) {
            heart.want = 0.0F;
        }
    }

    /** A looping sound that fades to where it is wanted, and stops itself at silence. */
    private static final class Loop extends AbstractTickableSoundInstance {
        float want;
        float pitchWant;

        Loop(SoundEvent sound, float volume, float pitch) {
            super(sound, SoundSource.AMBIENT, SoundInstance.createUnseededRandom());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
            this.want = volume;
            this.pitch = pitch;
            this.pitchWant = pitch;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            volume += (want - volume) * 0.08F;
            pitch += (pitchWant - pitch) * 0.05F;
            if (want <= 0.0F && volume < 0.01F) {
                stop();
            }
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
