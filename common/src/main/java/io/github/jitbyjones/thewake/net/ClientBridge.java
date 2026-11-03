package io.github.jitbyjones.thewake.net;

import javax.annotation.Nullable;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Lets common code hand payloads to the client package without loading any client class on a
 * dedicated server. The client entrypoint installs the implementation.
 */
public interface ClientBridge {
    void handle(CustomPacketPayload payload);

    final class Holder {
        @Nullable
        static ClientBridge instance;

        private Holder() {}
    }

    static void install(ClientBridge bridge) {
        Holder.instance = bridge;
    }

    static void dispatch(CustomPacketPayload payload) {
        ClientBridge bridge = Holder.instance;
        if (bridge != null) {
            bridge.handle(payload);
        }
    }
}
