package io.github.jitbyjones.thewake.phantom;

import net.minecraft.server.level.ServerPlayer;

/** Server-side script for the rare times an image does something. Never persisted. */
public interface PhantomBehavior {
    void tick(Phantom phantom, ServerPlayer owner);

    default void onTouched(Phantom phantom, ServerPlayer owner) {
    }
}
