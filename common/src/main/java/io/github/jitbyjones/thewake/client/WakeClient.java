package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.creature.CreatureRenderer;
import io.github.jitbyjones.thewake.client.fx.AttackClient;
import io.github.jitbyjones.thewake.client.fx.Hitches;
import io.github.jitbyjones.thewake.client.fx.Overlays;
import io.github.jitbyjones.thewake.client.fx.PauseTape;
import io.github.jitbyjones.thewake.client.fx.RoomTone;
import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import io.github.jitbyjones.thewake.client.fx.TapeEffect;
import io.github.jitbyjones.thewake.client.fx.TapeMotor;
import io.github.jitbyjones.thewake.client.render.WorldRender;
import io.github.jitbyjones.thewake.haunt.Hunter;
import io.github.jitbyjones.thewake.net.AttackPayload;
import io.github.jitbyjones.thewake.net.ClientBridge;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.net.ScreenPayload;
import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.net.SyncPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;

/** Client entry points. Each loader's client glue forwards its events here. */
public final class WakeClient {
    private static int reportedScreen = -1;
    private static boolean reportedFocus;
    private static int reportCooldown;

    private WakeClient() {}

    public static void init() {
        ClientBridge.install(payload -> {
            if (payload instanceof PhantomPayload p) {
                ClientPhantoms.handle(p);
            } else if (payload instanceof SyncPayload s) {
                ClientState.clarity = s.clarity();
                ClientState.stage = s.stage();
                ClientState.hunting = s.hunting();
                ClientState.ended = s.ended();
                ClientState.lastLayDown = s.lastLayDown();
                ClientState.form = s.form();
                ClientState.tension = s.tension();
                ClientState.chasing = s.chasing();
                ClientState.pins = s.pins();
            } else if (payload instanceof FxPayload fx) {
                Overlays.handle(fx);
            } else if (payload instanceof StoppedPayload stopped) {
                StoppedClient.handle(stopped);
            } else if (payload instanceof AttackPayload attack) {
                AttackClient.begin(attack);
            } else if (payload instanceof io.github.jitbyjones.thewake.net.WakeFxPayload fx) {
                io.github.jitbyjones.thewake.client.fx.WakeFxClient.handle(fx);
            } else if (payload instanceof io.github.jitbyjones.thewake.net.PossessPayload possess) {
                io.github.jitbyjones.thewake.client.fx.PossessionClient.begin(possess);
            }
        });
    }

    public static void tick() {
        reportScreen();
        Overlays.tick();
        ClientPhantoms.tick();
        StoppedClient.tick();
        PauseTape.tick();
        AttackClient.tick();
        io.github.jitbyjones.thewake.client.fx.WakeFxClient.tick();
        io.github.jitbyjones.thewake.client.fx.PossessionClient.tick();
        TapeEffect.tick();
        TapeMotor.tick();
        RoomTone.tick();
        DemoClient.tick();
        DemoExpansionClient.tick();
        DemoFormsClient.tick();
        DemoStructuresClient.tick();
    }

    /**
     * Tells the server when a screen opens or closes, and whether the window has focus: menus are
     * when it moves, and an alt-tabbed pause does not count.
     */
    private static void reportScreen() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null || mc.player == null) {
            reportedScreen = -1;
            return;
        }
        Screen screen = mc.screen;
        // A loading screen, a death screen or a disconnect is not you looking away.
        boolean notYours = screen instanceof net.minecraft.client.gui.screens.ReceivingLevelScreen
                || screen instanceof net.minecraft.client.gui.screens.LevelLoadingScreen
                || screen instanceof net.minecraft.client.gui.screens.ProgressScreen
                || screen instanceof net.minecraft.client.gui.screens.GenericMessageScreen
                || screen instanceof net.minecraft.client.gui.screens.DeathScreen;
        int kind = screen == null || notYours ? Hunter.SCREEN_NONE : screen instanceof PauseScreen ? Hunter.SCREEN_PAUSE
                : screen instanceof ChatScreen ? Hunter.SCREEN_CHAT : Hunter.SCREEN_MENU;
        // (The scripted demo runs on a virtual display where the window never has focus.)
        boolean focused = mc.isWindowActive() || io.github.jitbyjones.thewake.haunt.DemoScript.ENABLED
                || io.github.jitbyjones.thewake.haunt.DemoExpansion.ENABLED;
        if (reportCooldown > 0) {
            reportCooldown--;
        }
        if ((kind != reportedScreen || focused != reportedFocus) && reportCooldown == 0) {
            reportedScreen = kind;
            reportedFocus = focused;
            reportCooldown = 2;
            try {
                TheWake.platform().sendToServer(new ScreenPayload(kind, focused));
            } catch (RuntimeException ignored) {
                // A server without the mod: nothing there can hear it.
            }
        }
    }

    /** Every frame, before the level renders. */
    public static void beforeLevel(float partial) {
        AttackClient.frame(partial);
        io.github.jitbyjones.thewake.client.fx.WakeFxClient.frame(partial);
    }

    public static void renderLevel(Camera camera, float partial) {
        WorldRender.afterEntities(camera, partial);
    }

    public static void renderHud(GuiGraphics graphics, float partial) {
        Hitches.frame();
        Overlays.render(graphics, partial);
        io.github.jitbyjones.thewake.client.fx.WakeFxClient.render(graphics, partial);
        PauseTape.renderHud(graphics);
    }

    public static void disconnect() {
        ClientPhantoms.clear();
        ClientState.reset();
        StoppedClient.reset();
        PauseTape.reset();
        AttackClient.reset();
        io.github.jitbyjones.thewake.client.fx.WakeFxClient.reset();
        io.github.jitbyjones.thewake.client.fx.PossessionClient.reset();
        io.github.jitbyjones.thewake.client.render.WorldRender.forgetBodies();
        Overlays.reset();
        TapeEffect.close();
        TapeEffect.reset();
        TapeMotor.stop();
        RoomTone.stop();
        WorldRender.releaseDepth();
        reportedScreen = -1;
    }

    public static void resourcesReloaded() {
        CreatureRenderer.invalidate();
        TapeEffect.reloaded();
    }
}
