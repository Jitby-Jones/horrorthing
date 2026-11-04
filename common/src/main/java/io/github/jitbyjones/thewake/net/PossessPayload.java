package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: it takes the chat box. Types {@code say}, erases it, types {@code command} and
 * sends it. {@code hurried}: it has hold of you, and does not take its time.
 */
public record PossessPayload(String say, String command, boolean hurried) implements CustomPacketPayload {
    public static final Type<PossessPayload> TYPE = new Type<>(TheWake.id("possess"));
    public static final StreamCodec<FriendlyByteBuf, PossessPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.say, 256);
                buf.writeUtf(p.command, 256);
                buf.writeBoolean(p.hurried);
            },
            buf -> new PossessPayload(buf.readUtf(256), buf.readUtf(256), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
