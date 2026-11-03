package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.client.fx.Overlays;
import io.github.jitbyjones.thewake.client.fx.StoppedClient;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.item.ItemPropertyFunction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Clocks stop near it, at the minute you last lay down. In your hand, in an item frame, on the
 * floor. A few seconds before it takes time from you, the clock in your hand stops too.
 */
public final class ClockOverride {
    private static final Map<ItemPropertyFunction, ItemPropertyFunction> WRAPPED = new IdentityHashMap<>();

    private ClockOverride() {}

    public static ItemPropertyFunction wrap(ItemPropertyFunction original) {
        return WRAPPED.computeIfAbsent(original, o -> (stack, level, entity, seed) -> {
            Float stopped = stoppedValue(stack, level, entity);
            return stopped != null ? stopped : o.call(stack, level, entity, seed);
        });
    }

    @Nullable
    private static Float stoppedValue(ItemStack stack, @Nullable ClientLevel level, @Nullable LivingEntity holder) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel world = level != null ? level : mc.level;
        if (world == null || ClientState.lastLayDown < 0) {
            return null;
        }
        Entity where = holder != null ? holder : stack.getEntityRepresentation();
        boolean stop = StoppedClient.active();
        if (!stop && Overlays.clockStopped() && holder == mc.player) {
            stop = true;
        }
        if (!stop && where != null && ClientState.clarity >= 0.45F && !ClientState.ended) {
            stop = ClientPhantoms.nearest(where.position(), 24.0) != null;
        }
        if (!stop || !world.dimensionType().natural()) {
            return null;
        }
        return world.dimensionType().timeOfDay(ClientState.lastLayDown);
    }
}
