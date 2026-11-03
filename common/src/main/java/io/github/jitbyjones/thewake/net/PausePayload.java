package io.github.jitbyjones.thewake.net;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server (singleplayer only): the game was paused for {@code millis}. {@code figureSeen}
 * is set if the one unblurred thing behind the menu was on screen long enough to be seen.
 */
public record PausePayload(int millis, boolean figureSeen) implements CustomPacketPayload {
    public static final Type<PausePayload> TYPE = new Type<>(TheWake.id("pause"));
    public static final StreamCodec<FriendlyByteBuf, PausePayload> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeVarInt(p.millis);
                buf.writeBoolean(p.figureSeen);
            },
            buf -> new PausePayload(buf.readVarInt(), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
