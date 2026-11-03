package io.github.jitbyjones.thewake.mixin.client;

import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.network.protocol.game.ClientboundTickingStatePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** In the Stopped the sky does not move, whatever the server's clock says (servers keep running). */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method = "handleSetTime", at = @At("HEAD"), cancellable = true)
    private void thewake$noTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        if (StoppedClient.active()) {
            ci.cancel();
        }
    }

    @Inject(method = "handleTickingState", at = @At("TAIL"))
    private void thewake$stayFrozen(ClientboundTickingStatePacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (StoppedClient.active() && mc.level != null) {
            mc.level.tickRateManager().setFrozen(true);
        }
    }
}
