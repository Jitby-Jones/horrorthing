package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.client.fx.Hitches;
import io.github.jitbyjones.thewake.client.fx.Overlays;
import io.github.jitbyjones.thewake.client.fx.PauseFigure;
import io.github.jitbyjones.thewake.client.fx.PhotoEffect;
import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import io.github.jitbyjones.thewake.client.render.Silhouette;
import io.github.jitbyjones.thewake.client.render.WorldRender;
import io.github.jitbyjones.thewake.net.ClientBridge;
import io.github.jitbyjones.thewake.net.FxPayload;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.net.SyncPayload;
import net.minecraft.client.Camera;
import net.minecraft.client.gui.GuiGraphics;

/** Client entry points. Each loader's client glue forwards its events here. */
public final class WakeClient {
    private WakeClient() {}

    public static void init() {
        ClientBridge.install(payload -> {
            if (payload instanceof PhantomPayload p) {
                ClientPhantoms.handle(p);
            } else if (payload instanceof SyncPayload s) {
                ClientState.clarity = s.clarity();
                ClientState.stage = s.stage();
                ClientState.pauseArmed = s.pauseArmed();
                ClientState.ended = s.ended();
                ClientState.lastLayDown = s.lastLayDown();
            } else if (payload instanceof FxPayload fx) {
                Overlays.handle(fx);
            } else if (payload instanceof StoppedPayload stopped) {
                StoppedClient.handle(stopped);
            }
        });
    }

    public static void tick() {
        Overlays.tick();
        ClientPhantoms.tick();
        StoppedClient.tick();
        PauseFigure.tick();
        io.github.jitbyjones.thewake.client.fx.RoomTone.tick();
    }

    public static void renderLevel(Camera camera, float partial) {
        WorldRender.afterEntities(camera, partial);
    }

    public static void renderHud(GuiGraphics graphics, float partial) {
        Hitches.frame();
        Overlays.render(graphics, partial);
    }

    public static void disconnect() {
        ClientPhantoms.clear();
        ClientState.reset();
        StoppedClient.reset();
        PauseFigure.reset();
        Overlays.reset();
        PhotoEffect.close();
        io.github.jitbyjones.thewake.client.fx.RoomTone.stop();
    }

    public static void resourcesReloaded() {
        Silhouette.invalidate();
        PhotoEffect.reloaded();
    }
}
