package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: a screen opened or closed ({@code kind}, see {@code Hunter.SCREEN_*}), and
 * whether the game window has focus. Only ever used to decide whether it may move; a client that
 * lies about it only makes its own game less dangerous.
 */
public record ScreenPayload(int kind, boolean focused) implements CustomPacketPayload {
    public static final Type<ScreenPayload> TYPE = new Type<>(TheWake.id("screen"));
    public static final StreamCodec<FriendlyByteBuf, ScreenPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeByte(p.kind);
                buf.writeBoolean(p.focused);
            },
            buf -> new ScreenPayload(buf.readByte(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
