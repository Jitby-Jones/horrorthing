package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.net.HitchPayload;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;

/**
 * Real hitches: when this client's frames really do stall for more than a second, that time was
 * lost too. Measured per client, so a slow server does not haunt everyone at once. Nothing is faked.
 */
public final class Hitches {
    private static long lastFrame = -1L;
    private static long inWorldSince = -1L;

    private Hitches() {}

    public static void frame() {
        Minecraft mc = Minecraft.getInstance();
        long now = Util.getMillis();
        if (mc.level == null || mc.player == null || mc.getConnection() == null) {
            lastFrame = -1L;
            inWorldSince = -1L;
            return;
        }
        if (inWorldSince < 0) {
            inWorldSince = now;
        }
        long gap = lastFrame < 0 ? 0 : now - lastFrame;
        lastFrame = now;
        boolean settled = now - inWorldSince > 15_000L;
        if (gap > 1000L && gap < 30_000L && settled && !mc.isPaused() && mc.screen == null && mc.isWindowActive()
                && !StoppedClient.active()) {
            try {
                TheWake.platform().sendToServer(new HitchPayload((int) gap));
            } catch (RuntimeException ignored) {
                // Server without the mod: nothing to tell.
            }
        }
    }
}
