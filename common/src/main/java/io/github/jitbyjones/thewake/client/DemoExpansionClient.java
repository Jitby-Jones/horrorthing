package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.DemoExpansion;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/** Development only: screenshots for {@link DemoExpansion}, on the integrated server's clock. */
public final class DemoExpansionClient {
    private static final Object[][] SHOTS = {
            {85, "e01_face_watcher"}, {125, "e02_face_uneasy"}, {165, "e03_face_angry"}, {205, "e04_face_furious"},
            {232, "e05_chase_scream"}, {248, "e06_chase_running"}, {262, "e07_chase_close"}, {292, "e08_held"},
            {305, "e08b_held_face"}, {326, "e09_held_blink"}, {345, "e10_held_bite"},
            {405, "e11_door_coming"}, {418, "e12a_door"}, {424, "e12b_door"}, {430, "e12c_door"}, {440, "e12d_door"}, {460, "e12e_door"},
            {670, "e15_pillar_base"}, {700, "e16_climbing"}, {730, "e17_breaking_lip"}, {760, "e18_over_the_top"},
            {812, "e19_leap"}, {828, "e20_splash"}, {845, "e21_under"},
            {993, "e22_ledge"}, {1000, "e23_ledge_b"},
            {1059, "e24_flash"}, {1070, "e25_stunned"}, {1090, "e26_stunned_b"},
            {1150, "e27_chat_typing"}, {1185, "e28_chat_said"}, {1250, "e29_chat_command"},
            {1375, "e30_structures_row"}, {1395, "e31_watchtower_store"}, {1425, "e32_waiting_room"}, {1455, "e33_bedroom"},
            {1485, "e34_hallway"},
            {1555, "e35_archive"}, {1575, "e36_archive_b"},
            {1700, "e37_set"}, {1725, "e38_set_b"},
            {1752, "e39_lost_time"},
            {1845, "e40_last_night"}, {1872, "e41_pin_pulled"}, {1940, "e42_eject_phase"}, {2015, "e43_forced_screen"},
            {2035, "e44_removed"}, {2080, "e45_after"},
    };
    private static final Set<String> TAKEN = new HashSet<>();

    private DemoExpansionClient() {}

    public static void tick() {
        if (!DemoExpansion.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null || DemoExpansion.startTick < 0) {
            return;
        }
        int t = server.getTickCount() - DemoExpansion.startTick;
        boolean loading = mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
        for (Object[] shot : SHOTS) {
            if (loading) {
                break;
            }
            String name = (String) shot[1];
            if (t >= (Integer) shot[0] && TAKEN.add(name)) {
                Screenshot.grab(mc.gameDirectory, "thewake_" + name + ".png", mc.getMainRenderTarget(),
                        message -> TheWake.LOG.info("[demo2] {}", message.getString()));
            }
        }
        if (t >= 2100 && TAKEN.add("done")) {
            TheWake.LOG.info("[demo2] done");
            mc.stop();
        }
    }
}
