package io.github.jitbyjones.thewake.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.serialization.Lifecycle;
import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldDimensions;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Vanilla marks any world with more than its three dimensions as "experimental", and greets it
 * with "Worlds using Experimental Settings are not supported". The Wake's two places (the Archive
 * and the Set) are ordinary data-driven dimensions; they should not make every world it is added
 * to look broken. Only ours are exempted: anything else that is experimental still says so.
 */
@Mixin(WorldDimensions.class)
public abstract class WorldDimensionsMixin {
    @Inject(method = "checkStability", at = @At("HEAD"), cancellable = true)
    private static void thewake$oursAreStable(ResourceKey<LevelStem> key, LevelStem stem, CallbackInfoReturnable<Lifecycle> cir) {
        if (TheWake.MOD_ID.equals(key.location().getNamespace())) {
            cir.setReturnValue(Lifecycle.stable());
        }
    }

    /** The count compared with vanilla's three, without ours. */
    @ModifyExpressionValue(method = "bake", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I"))
    private int thewake$notCountingOurs(int size, Registry<LevelStem> stemRegistry) {
        int ours = 0;
        for (ResourceKey<LevelStem> key : stemRegistry.registryKeySet()) {
            if (TheWake.MOD_ID.equals(key.location().getNamespace())) {
                ours++;
            }
        }
        return size - ours;
    }
}
