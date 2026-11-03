package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.DemoScript;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/** Development only: the screenshot half of {@link DemoScript}. */
public final class DemoClient {
    private static int ticks = -1;

    private DemoClient() {}

    public static void tick() {
        if (!DemoScript.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        ticks++;
        switch (ticks) {
            case 70 -> shot(mc, "01_sighting_day");
            case 140 -> shot(mc, "02_waking_residue");
            case 265 -> shot(mc, "03_bedside_before_turn");
            case 312 -> shot(mc, "04_head_turning");
            case 338 -> shot(mc, "05_head_turned");
            case 415 -> shot(mc, "06_sighting_night");
            case 565 -> shot(mc, "07_stopped_in_bed");
            case 690 -> shot(mc, "08_stopped_walking");
            case 745 -> shot(mc, "09_stopped_on_its_path");
            case 900 -> mc.pauseGame(false);
            case 1060 -> shot(mc, "10_pause_menu");
            case 1080 -> mc.setScreen(null);
            case 1205 -> shot(mc, "11_escape_lying_down");
            case 1300 -> shot(mc, "12_escaped_morning");
            case 1360 -> {
                TheWake.LOG.info("[demo] done");
                mc.stop();
            }
            default -> {
            }
        }
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, "thewake_demo_" + name + ".png", mc.getMainRenderTarget(),
                message -> TheWake.LOG.info("[demo] {}", message.getString()));
    }
}
