package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.DemoForms;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/** Development only: screenshots for {@link DemoForms}, on the integrated server's clock. */
public final class DemoFormsClient {
    private static final Object[][] SHOTS = {
            {82, "f0a_watcher_whole"},
            {112, "f0b_watcher_face"},
            {142, "f1a_uneasy_whole"},
            {172, "f1b_uneasy_face"},
            {202, "f2a_angry_whole"},
            {232, "f2b_angry_face"},
            {262, "f3a_furious_whole"},
            {292, "f3b_furious_face"},
            {320, "c00_knock"},
            {350, "c01_pick"},
            {380, "c02_uneasy_walk"},
            {410, "c03_reach_wall"},
            {440, "c04_reach_up"},
            {470, "c05_reach_window"},
            {500, "c06_angry_walk"},
            {530, "c07_furious_run"},
            {560, "c08_furious_lunge"},
            {590, "c09_furious_scream"},
            {614, "p_pick_614"},
            {624, "p_pick_624"},
            {634, "p_pick_634"},
            {652, "p_pick_652"},
            {716, "w_wall_716"},
            {728, "w_wall_728"},
            {740, "w_wall_740"},
            {752, "w_wall_752"},
            {764, "w_wall_764"},
            {776, "w_wall_776"},
            {788, "w_wall_788"},
            {800, "w_wall_800"},
            {812, "w_wall_812"},
            {824, "w_wall_824"},
            {836, "w_wall_836"},
            {856, "u_pillar_856"},
            {868, "u_pillar_868"},
            {880, "u_pillar_880"},
            {892, "u_pillar_892"},
            {904, "u_pillar_904"},
            {916, "u_pillar_916"},
            {928, "u_pillar_928"},
            {940, "u_pillar_940"},
            {952, "u_pillar_952"},
            {964, "u_pillar_964"},
            {976, "u_pillar_976"},
            {990, "s_skip_990"},
            {995, "s_skip_995"},
            {1000, "s_skip_1000"},
            {1005, "s_skip_1005"},
            {1010, "s_skip_1010"},
            {1015, "s_skip_1015"},
            {1020, "s_skip_1020"},
            {1025, "s_skip_1025"},
            {1030, "s_skip_1030"},
            {1035, "s_skip_1035"},
            {1040, "s_skip_1040"},
            {1045, "s_skip_1045"},
            {1050, "s_skip_1050"},
            {1055, "s_skip_1055"},
            {1060, "s_skip_1060"},
            {1065, "s_skip_1065"},
            {1070, "s_skip_1070"},
            {1075, "s_skip_1075"},
            {1080, "s_skip_1080"},
            {1085, "s_skip_1085"},
            {1090, "s_skip_1090"},
            {1095, "s_skip_1095"},
            {1100, "s_skip_1100"},
            {1105, "s_skip_1105"},
            {1110, "s_skip_1110"},
            {1115, "s_skip_1115"},
            {1120, "s_skip_1120"},
            {1125, "s_skip_1125"},
            {1130, "s_skip_1130"},
            {1135, "s_skip_1135"},
            {1160, "t_traces_1160"},
            {1185, "t_traces_1185"},
    };
    private static final Set<String> TAKEN = new HashSet<>();

    private DemoFormsClient() {}

    public static void tick() {
        if (!DemoForms.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null || DemoForms.startTick < 0) {
            return;
        }
        int t = server.getTickCount() - DemoForms.startTick;
        boolean loading = mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen;
        for (Object[] shot : SHOTS) {
            if (loading) {
                break;
            }
            String name = (String) shot[1];
            if (t >= (Integer) shot[0] && TAKEN.add(name)) {
                Screenshot.grab(mc.gameDirectory, "thewake_" + name + ".png", mc.getMainRenderTarget(),
                        message -> TheWake.LOG.info("[demo3] {}", message.getString()));
            }
        }
        if (t >= 1220 && TAKEN.add("done")) {
            TheWake.LOG.info("[demo3] done");
            mc.stop();
        }
    }
}
