package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.DemoStructures;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.server.MinecraftServer;

/** Development only: screenshots for {@link DemoStructures}, on the integrated server's clock. */
public final class DemoStructuresClient {
    private static final Set<String> TAKEN = new HashSet<>();

    private DemoStructuresClient() {}

    public static void tick() {
        if (!DemoStructures.ENABLED) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        MinecraftServer server = mc.getSingleplayerServer();
        if (mc.level == null || mc.player == null || server == null || DemoStructures.startTick < 0) {
            return;
        }
        mc.options.hideGui = true;
        if (mc.screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                || mc.screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen) {
            return;
        }
        int t = server.getTickCount() - DemoStructures.startTick;
        for (int i = 0; i < DemoStructures.TOUR.length; i++) {
            String name = DemoStructures.TOUR[i].name();
            if (t >= DemoStructures.shotTick(i) && TAKEN.add(name)) {
                Screenshot.grab(mc.gameDirectory, "thewake_" + name + ".png", mc.getMainRenderTarget(),
                        message -> TheWake.LOG.info("[demo4] {}", message.getString()));
            }
        }
        if (t >= DemoStructures.doneTick() && TAKEN.add("done")) {
            TheWake.LOG.info("[demo4] done");
            mc.stop();
        }
    }
}
