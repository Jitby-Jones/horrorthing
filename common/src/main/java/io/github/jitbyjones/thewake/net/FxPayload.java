package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: a one-shot presentation effect. */
public record FxPayload(int kind, int durationTicks) implements CustomPacketPayload {
    /** The view fills with night and the sound stops: lost time. */
    public static final int BLACKOUT = 1;
    /** The clock in your hand stops, a few seconds before something takes your time. */
    public static final int CLOCK_STOP = 3;
    /** The first seconds after waking look like an old photograph. */
    public static final int WAKE_TINT = 4;
    /** The screen fades as if falling asleep (servers, where vanilla sleep cannot be used). */
    public static final int FALL_ASLEEP = 5;

    public static final Type<FxPayload> TYPE = new Type<>(TheWake.id("fx"));
    public static final StreamCodec<FriendlyByteBuf, FxPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeVarInt(p.durationTicks);
            },
            buf -> new FxPayload(buf.readVarInt(), buf.readVarInt()));

    public static FxPayload of(int kind, int durationTicks) {
        return new FxPayload(kind, durationTicks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
