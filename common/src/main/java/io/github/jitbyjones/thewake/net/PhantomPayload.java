package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to owner: create, update or remove one image. Nobody else is ever sent these. */
public record PhantomPayload(int op, int id, double x, double y, double z, float yaw, int mode, int stance, float opacity,
        float smear, float focus, float headYaw, float headPitch, float headSmear, int bedDirection, float fadeSpeed, float face)
        implements CustomPacketPayload {
    public static final int OP_SET = 0;
    public static final int OP_REMOVE = 1;
    public static final int OP_DISSOLVE = 2;
    public static final int OP_CLEAR = 3;

    public static final Type<PhantomPayload> TYPE = new Type<>(TheWake.id("phantom"));
    public static final StreamCodec<FriendlyByteBuf, PhantomPayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.op);
                buf.writeVarInt(p.id);
                if (p.op == OP_SET) {
                    buf.writeDouble(p.x);
                    buf.writeDouble(p.y);
                    buf.writeDouble(p.z);
                    buf.writeFloat(p.yaw);
                    buf.writeByte(p.mode);
                    buf.writeByte(p.stance);
                    buf.writeFloat(p.opacity);
                    buf.writeFloat(p.smear);
                    buf.writeFloat(p.focus);
                    buf.writeFloat(p.headYaw);
                    buf.writeFloat(p.headPitch);
                    buf.writeFloat(p.headSmear);
                    buf.writeByte(p.bedDirection);
                    buf.writeFloat(p.fadeSpeed);
                    buf.writeFloat(p.face);
                }
            },
            buf -> {
                int op = buf.readVarInt();
                int id = buf.readVarInt();
                if (op != OP_SET) {
                    return new PhantomPayload(op, id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 1, 0);
                }
                return new PhantomPayload(op, id, buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readByte(),
                        buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(),
                        buf.readByte(), buf.readFloat(), buf.readFloat());
            });

    public static PhantomPayload remove(int id, boolean dissolve) {
        return new PhantomPayload(dissolve ? OP_DISSOLVE : OP_REMOVE, id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 1, 0);
    }

    public static PhantomPayload clear() {
        return new PhantomPayload(OP_CLEAR, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -1, 1, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
