package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.client.ClientState;
import io.github.jitbyjones.thewake.net.FxPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

/** Full-screen effects drawn over the HUD: lost time (black), falling asleep (fade). */
public final class Overlays {
    private static boolean soundPaused;

    private Overlays() {}

    private static long clientTicks;

    public static void handle(FxPayload fx) {
        long t = clientTicks;
        switch (fx.kind()) {
            case FxPayload.BLACKOUT -> {
                ClientState.blackoutStart = t;
                ClientState.blackoutEnd = t + fx.durationTicks();
                if (!soundPaused) {
                    Minecraft.getInstance().getSoundManager().pause();
                    soundPaused = true;
                }
            }
            case FxPayload.CLOCK_STOP -> ClientState.clockStopEnd = t + fx.durationTicks();
            case FxPayload.WAKE_TINT -> wakeTint(fx.durationTicks());
            case FxPayload.FALL_ASLEEP -> {
                ClientState.fallAsleepStart = t;
                ClientState.fallAsleepEnd = t + fx.durationTicks() + 40;
            }
            default -> {
            }
        }
    }

    /** A black frame, without silencing anything (the scream carries over it). */
    public static void blackout(int ticks) {
        ClientState.blackoutStart = clientTicks;
        ClientState.blackoutEnd = clientTicks + ticks;
    }

    public static void wakeTint(int ticks) {
        ClientState.wakeTintStart = clientTicks;
        ClientState.wakeTintEnd = clientTicks + ticks;
    }

    public static long ticks() {
        return clientTicks;
    }

    public static void tick() {
        clientTicks++;
        if (soundPaused && clientTicks >= ClientState.blackoutEnd) {
            Minecraft.getInstance().getSoundManager().resume();
            soundPaused = false;
        }
    }

    public static boolean clockStopped() {
        return clientTicks < ClientState.clockStopEnd;
    }

    /** 0..1: the tape look in the first seconds after a night it spent close to you. */
    public static float morningAmount() {
        if (clientTicks < ClientState.wakeTintEnd) {
            float span = Math.max(1.0F, ClientState.wakeTintEnd - ClientState.wakeTintStart);
            float t = (clientTicks - ClientState.wakeTintStart) / span;
            return Mth.clamp(1.0F - t, 0.0F, 1.0F) * 0.85F;
        }
        return 0.0F;
    }

    public static void render(GuiGraphics graphics, float partial) {
        int w = graphics.guiWidth();
        int h = graphics.guiHeight();
        float alpha = 0.0F;
        if (ClientState.blackoutEnd > 0 && clientTicks < ClientState.blackoutEnd + 12) {
            float in = (clientTicks + partial - ClientState.blackoutStart) / 3.0F;
            float out = (ClientState.blackoutEnd + 12 - (clientTicks + partial)) / 12.0F;
            alpha = Mth.clamp(Math.min(in, out), 0.0F, 1.0F);
        }
        if (clientTicks < ClientState.fallAsleepEnd) {
            float span = Math.max(1.0F, ClientState.fallAsleepEnd - 40 - ClientState.fallAsleepStart);
            float t = Mth.clamp((clientTicks + partial - ClientState.fallAsleepStart) / span, 0.0F, 1.0F);
            alpha = Math.max(alpha, t);
        }
        if (alpha > 0.002F) {
            int a = Math.round(alpha * 255.0F);
            // Not pure black: the colour of the night it fills your view with.
            graphics.fill(0, 0, w, h, (a << 24) | 0x02030A);
        }
    }

    public static void reset() {
        if (soundPaused) {
            Minecraft.getInstance().getSoundManager().resume();
            soundPaused = false;
        }
    }
}
