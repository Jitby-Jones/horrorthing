package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: this client's game really did stall for {@code millis}. Lost time. */
public record HitchPayload(int millis) implements CustomPacketPayload {
    public static final Type<HitchPayload> TYPE = new Type<>(TheWake.id("hitch"));
    public static final StreamCodec<FriendlyByteBuf, HitchPayload> CODEC = StreamCodec.of(
            (buf, p) -> buf.writeVarInt(p.millis), buf -> new HitchPayload(buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
