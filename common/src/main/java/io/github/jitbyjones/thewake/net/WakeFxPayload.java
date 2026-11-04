package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: one effect, somewhere, with an amount. */
public record WakeFxPayload(int kind, double x, double y, double z, float amount, float extra) implements CustomPacketPayload {
    /** The close layer of a scream, in your ears. amount = volume, extra = pitch. */
    public static final int SCREAM_NEAR = 1;
    /** Your own flash, going off in your face. */
    public static final int FLASH = 2;
    /** The picture shakes. amount = strength, extra = ticks. */
    public static final int SHAKE = 3;
    /** The tape skips: a jolt of tracking noise, and something has moved. */
    public static final int TAPE_SKIP = 4;
    /** Something takes your head and turns it toward x, y, z. extra = ticks. */
    public static final int LOOK_AT = 5;
    /** The lights stutter. extra = ticks. */
    public static final int FLICKER = 6;
    /** You cannot move. extra = ticks. */
    public static final int PARALYSIS = 7;
    /** For a minute the sky gets light, and then it does not. extra = ticks. */
    public static final int FAKE_DAWN = 8;
    /** It is holding you: the picture closes in. extra = 1 begin, 0 end. */
    public static final int HELD = 9;
    /** It has gone for good. */
    public static final int REMOVED = 10;
    /** A chase begins (amount 1) or ends (amount 0). */
    public static final int CHASE = 11;
    /** It opens a screen on you: amount 0 inventory, 1 chat, 2 pause. */
    public static final int FORCE_SCREEN = 12;
    /** The time you lost, on a VHS counter. amount = seconds. */
    public static final int LOST_TIME = 13;
    /** A photograph was taken of you: a click and a flash, behind you. */
    public static final int SHUTTER = 14;
    /** PLAY: you died, and the tape is still running. extra = ticks to show it. */
    public static final int PLAY = 15;
    /** The tell before it strikes: everything goes quiet. extra = ticks. */
    public static final int HUSH = 16;
    /** FURIOUS skipped like a chewed tape from x, y, z: copies of it there for a moment. extra = its yaw. */
    public static final int SKIP = 17;

    public static final Type<WakeFxPayload> TYPE = new Type<>(TheWake.id("wake_fx"));
    public static final StreamCodec<FriendlyByteBuf, WakeFxPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.kind);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeFloat(p.amount);
                buf.writeFloat(p.extra);
            },
            buf -> new WakeFxPayload(buf.readVarInt(), buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readFloat()));

    public static WakeFxPayload of(int kind, float amount, float extra) {
        return new WakeFxPayload(kind, 0.0, 0.0, 0.0, amount, extra);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
