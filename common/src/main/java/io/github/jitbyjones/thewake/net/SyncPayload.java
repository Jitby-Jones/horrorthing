package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: the few facts the client needs ahead of time (whether it is moving right now), and
 * the minute the player last lay down, which is where clocks stop near it.
 */
public record SyncPayload(float clarity, int stage, boolean hunting, boolean ended, long lastLayDown, int form, float tension, boolean chasing,
        int pins) implements CustomPacketPayload {
    public static final Type<SyncPayload> TYPE = new Type<>(TheWake.id("sync"));
    public static final StreamCodec<FriendlyByteBuf, SyncPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeFloat(p.clarity);
                buf.writeVarInt(p.stage);
                buf.writeBoolean(p.hunting);
                buf.writeBoolean(p.ended);
                buf.writeLong(p.lastLayDown);
                buf.writeVarInt(p.form);
                buf.writeFloat(p.tension);
                buf.writeBoolean(p.chasing);
                buf.writeVarInt(p.pins);
            },
            buf -> new SyncPayload(buf.readFloat(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(), buf.readLong(), buf.readVarInt(),
                    buf.readFloat(), buf.readBoolean(), buf.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
