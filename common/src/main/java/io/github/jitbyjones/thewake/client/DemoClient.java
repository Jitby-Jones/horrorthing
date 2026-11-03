package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.DemoScript;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/**
 * Development only: the screenshot half of {@link DemoScript}. Keyed to the integrated server's
 * tick count (same JVM in singleplayer), so the two halves cannot drift apart.
 */
public final class DemoClient {
    private static final Object[][] SHOTS = {
            {110, "01_sighting_day"},
            {178, "02_waking_residue"},
            {300, "03_bedside_before_turn"},
            {345, "04_head_turning"},
            {378, "05_head_turned"},
            {440, "06_sighting_night"},
            {600, "08_stopped_in_bed"},
            {745, "09_stopped_walking"},
            {765, "10_stopped_on_its_path"},
            {1085, "11_escape_lying_down"},
            {1160, "12_escaped_morning"},
    };
    private static final Set<String> TAKEN = new HashSet<>();
    private static long pausedAt = -1L;
    private static boolean pauseDone;

    private DemoClient() {}

    public static void tick() {
        if (!DemoScript.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null || DemoScript.startTick < 0) {
            return;
        }
        int t = server.getTickCount() - DemoScript.startTick;
        for (Object[] shot : SHOTS) {
            String name = (String) shot[1];
            if (t >= (Integer) shot[0] && TAKEN.add(name)) {
                shot(mc, name);
            }
        }
        // The pause menu, in real time (the server does not tick while paused).
        if (!pauseDone && t >= 470) {
            long now = Util.getMillis();
            if (pausedAt < 0) {
                pausedAt = now;
                mc.pauseGame(false);
            } else if (now - pausedAt > 8000L && TAKEN.add("07_pause_menu")) {
                shot(mc, "07_pause_menu");
            } else if (now - pausedAt > 9000L) {
                mc.setScreen(null);
                pauseDone = true;
            }
        }
        if (t >= 1220 && TAKEN.add("done")) {
            TheWake.LOG.info("[demo] done");
            mc.stop();
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "thewake_demo_" + name + ".png", mc.getMainRenderTarget(),
                message -> TheWake.LOG.info("[demo] {}", message.getString()));
    }
}
