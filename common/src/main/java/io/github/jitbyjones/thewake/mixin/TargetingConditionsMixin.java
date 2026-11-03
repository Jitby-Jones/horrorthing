package io.github.jitbyjones.thewake.mixin;

import io.github.jitbyjones.thewake.stopped.StoppedManager;
import javax.annotation.Nullable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** On servers the world keeps running during someone's Stopped; nothing out there may target them. */
@Mixin(TargetingConditions.class)
public abstract class TargetingConditionsMixin {
    @Inject(method = "test", at = @At("HEAD"), cancellable = true)
    private void thewake$untargetable(@Nullable LivingEntity attacker, LivingEntity target, CallbackInfoReturnable<Boolean> cir) {
        if (StoppedManager.isProtected(target)) {
            cir.setReturnValue(false);
        }
    }
}
