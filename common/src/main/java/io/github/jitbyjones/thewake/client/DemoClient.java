package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.DemoScript;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.server.MinecraftServer;

/**
 * Development only: the client half of {@link DemoScript}: opens the inventory, pauses, and takes
 * the screenshots. Keyed to the integrated server's tick count (same JVM in singleplayer), so the
 * two halves cannot drift apart.
 */
public final class DemoClient {
    private static final Object[][] SHOTS = {
            {92, "01_giant_on_horizon"},
            {126, "02_corner_of_eye"},
            {152, "03_looked_at"},
            {214, "04_turned_to_you"},
            {256, "05_foot_of_bed"},
            {300, "06_bedside"},
            {358, "07_head_turning"},
            {400, "08_head_turned"},
            {540, "09_inventory_hunt_a"},
            {610, "10_inventory_hunt_b"},
            {690, "10c_inventory_hunt_c"},
            {706, "11_frozen_after_menu"},
            {765, "13_attack_a"},
            {769, "14_attack_b"},
            {910, "15_stopped_in_bed"},
            {1100, "16_stopped_walking"},
            {1165, "17_stopped_close"},
            {1395, "18_escape_lying_down"},
            {1470, "19_escaped_morning"},
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
        if (t >= 90 && TAKEN.add("log_phantoms")) {
            for (ClientPhantom p : ClientPhantoms.all()) {
                TheWake.LOG.info("[demo] client sees phantom {} at {} scale {} pose {} visible {} inView {} distance {}", p.id, p.pos, p.scale,
                        p.pose, p.gaze.visible, p.gaze.inView, String.format("%.1f", p.gaze.distance));
            }
        }
        if (t >= DemoScript.OPEN_INVENTORY && TAKEN.add("open_inventory")) {
            mc.setScreen(new InventoryScreen(mc.player));
        }
        if (t >= DemoScript.CLOSE_INVENTORY && TAKEN.add("close_inventory")) {
            mc.setScreen(null);
        }
        // The pause menu, in real time (the server's world does not tick while paused; it does).
        if (!pauseDone && t >= DemoScript.PAUSE) {
            long now = Util.getMillis();
            if (pausedAt < 0) {
                pausedAt = now;
                mc.pauseGame(false);
            } else if (now - pausedAt > 7000L && (now / 600L) % 2L == 0L && TAKEN.add("12_pause_tape")) {
                shot(mc, "12_pause_tape");
            } else if (now - pausedAt > 8400L) {
                mc.setScreen(null);
                pauseDone = true;
            }
        }
        if (pauseDone && TAKEN.add("12b_play") ) {
            // Next tick after resuming: the PLAY mark.
            shot(mc, "12b_play");
        }
        if (t >= 1530 && TAKEN.add("done")) {
            TheWake.LOG.info("[demo] done");
            mc.stop();
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "thewake_demo_" + name + ".png", mc.getMainRenderTarget(),
                message -> TheWake.LOG.info("[demo] {}", message.getString()));
    }
}
