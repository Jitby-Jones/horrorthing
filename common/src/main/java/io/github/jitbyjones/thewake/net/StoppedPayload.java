package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: enter or leave the Stopped. On entry it carries every place the player has
 * ever walked (block positions, with how worn each one is), drawn as ribbons of night.
 */
public record StoppedPayload(boolean active, long frozenDayTime, long[] crumbs, byte[] wear, int outcome) implements CustomPacketPayload {
    public static final int OUTCOME_NONE = 0;
    public static final int OUTCOME_ESCAPED = 1;
    public static final int OUTCOME_CAUGHT = 2;
    public static final int OUTCOME_KEPT = 3;

    public static final Type<StoppedPayload> TYPE = new Type<>(TheWake.id("stopped"));
    public static final StreamCodec<FriendlyByteBuf, StoppedPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.active);
                buf.writeLong(p.frozenDayTime);
                buf.writeLongArray(p.crumbs);
                buf.writeByteArray(p.wear);
                buf.writeVarInt(p.outcome);
            },
            buf -> new StoppedPayload(buf.readBoolean(), buf.readLong(), buf.readLongArray(), buf.readByteArray(), buf.readVarInt()));

    public static StoppedPayload leave(int outcome) {
        return new StoppedPayload(false, 0L, new long[0], new byte[0], outcome);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
