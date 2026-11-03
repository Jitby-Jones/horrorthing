package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to owner: it has reached you. The client plays the attack; {@code x, y, z} is where its face was. */
public record AttackPayload(double x, double y, double z) implements CustomPacketPayload {
    public static final Type<AttackPayload> TYPE = new Type<>(TheWake.id("attack"));
    public static final StreamCodec<FriendlyByteBuf, AttackPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
            },
            buf -> new AttackPayload(buf.readDouble(), buf.readDouble(), buf.readDouble()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
