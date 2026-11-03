package io.github.jitbyjones.thewake.mixin;

import io.github.jitbyjones.thewake.WakeFlags;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla's freeze never freezes players. On a server, inside your own Stopped, everyone else
 * should be as still as the rest of the world. Only affects the one client manager that asked.
 */
@Mixin(TickRateManager.class)
public abstract class TickRateManagerMixin {
    @Inject(method = "isEntityFrozen", at = @At("HEAD"), cancellable = true)
    private void thewake$freezeOtherPlayers(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if ((Object) this == WakeFlags.frozenClientTicks && entity instanceof Player
                && !entity.getUUID().equals(WakeFlags.localPlayer)) {
            cir.setReturnValue(true);
        }
    }
}
