package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to owner: place, change or remove one appearance of the creature. Nobody else is sent these. */
public record PhantomPayload(int op, int id, double x, double y, double z, float yaw, int mode, int pose, float headYaw,
        float headPitch, float jaw, float scale, int bedDirection, int flags) implements CustomPacketPayload {
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
                    buf.writeByte(p.pose);
                    buf.writeFloat(p.headYaw);
                    buf.writeFloat(p.headPitch);
                    buf.writeFloat(p.jaw);
                    buf.writeFloat(p.scale);
                    buf.writeByte(p.bedDirection);
                    buf.writeByte(p.flags);
                }
            },
            buf -> {
                int op = buf.readVarInt();
                int id = buf.readVarInt();
                if (op != OP_SET) {
                    return new PhantomPayload(op, id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, -1, 0);
                }
                return new PhantomPayload(op, id, buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readFloat(), buf.readByte(),
                        buf.readByte(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readByte(), buf.readByte());
            });

    public static PhantomPayload remove(int id, boolean dissolve) {
        return new PhantomPayload(dissolve ? OP_DISSOLVE : OP_REMOVE, id, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, -1, 0);
    }

    public static PhantomPayload clear() {
        return new PhantomPayload(OP_CLEAR, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, -1, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
