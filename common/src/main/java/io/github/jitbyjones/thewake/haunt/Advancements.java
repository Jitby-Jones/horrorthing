package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.server.level.ServerPlayer;

/** Grants this mod's data-driven advancements (each has a single impossible criterion). */
public final class Advancements {
    private Advancements() {}

    public static void award(ServerPlayer player, String name) {
        AdvancementHolder holder = player.server.getAdvancements().get(TheWake.id(name));
        if (holder == null) {
            return;
        }
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(holder);
        if (progress.isDone()) {
            return;
        }
        for (String criterion : progress.getRemainingCriteria()) {
            player.getAdvancements().award(holder, criterion);
        }
    }
}
