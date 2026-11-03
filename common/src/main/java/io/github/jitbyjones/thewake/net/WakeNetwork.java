package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.Hunter;
import io.github.jitbyjones.thewake.haunt.Lapses;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;

/** Payload routing shared by both loaders. Loader code only registers types and forwards here. */
public final class WakeNetwork {
    private WakeNetwork() {}

    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        TheWake.platform().sendToPlayer(player, payload);
    }

    /** Main thread, server side. Everything from a client is validated before use. */
    public static void handleServer(ServerPlayer player, CustomPacketPayload payload) {
        if (payload instanceof PausePayload pause) {
            Lapses.onPauseReport(player, pause);
        } else if (payload instanceof HitchPayload hitch) {
            Lapses.onHitch(player, hitch.millis());
        } else if (payload instanceof ScreenPayload screen) {
            Hunter.onScreen(player, screen.kind(), screen.focused());
        }
    }

    /** Main thread, client side. */
    public static void handleClient(CustomPacketPayload payload) {
        ClientBridge.dispatch(payload);
    }
}
