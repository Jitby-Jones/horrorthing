package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.net.AttackPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.stopped.StoppedManager;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.phys.Vec3;

/**
 * It reached you while you were not watching. The menu is torn away, your view is wrenched round to
 * it, and its face is the whole screen with its jaw coming open. Real damage, through armour.
 * "&lt;player&gt; looked away." Then it is gone, and will not do it again for a few minutes. In
 * hardcore it leaves you at half a heart.
 */
public final class Attack {
    public static final ResourceKey<DamageType> LOOKED_AWAY = ResourceKey.create(Registries.DAMAGE_TYPE, TheWake.id("looked_away"));
    /** Client ticks from the start of the sequence to the bite: after the blink, with the jaw all the way down. */
    public static final int BITE_TICK = 30;

    private Attack() {}

    public static boolean allowed(ServerPlayer player, HauntState state) {
        WakeConfig config = WakeConfig.get();
        Stage stage = state.stage();
        return config.attacks && stage.atLeast(Stage.HUNT) && !stage.atLeast(Stage.HUSH)
                && player.serverLevel().getGameTime() >= state.nextAttackTick()
                && !player.isCreative() && !player.isSpectator() && player.isAlive() && Haunt.eligible(player)
                && !StoppedManager.isIn(player);
    }

    public static void begin(ServerPlayer player, HauntState state, Phantom creature) {
        ServerLevel level = player.serverLevel();
        state.setLastAttackDay(Haunt.day(level));
        // Not again for a while: two to six minutes, and you do not know which.
        state.setNextAttackTick(level.getGameTime() + 2400L + player.getRandom().nextInt(4800));
        state.lapse();
        Vec3 from = creature.pos().add(0.0, Math.min(creature.pose().height * 0.85, 2.4), 0.0);
        WakeNetwork.send(player, new AttackPayload(from.x, from.y, from.z));
        Phantoms.remove(player, creature);
        Hunter.release(player, false);
        Director.schedule(BITE_TICK, () -> bite(player));
        TheWake.LOG.debug("[{}] attack", player.getGameProfile().getName());
    }

    private static void bite(ServerPlayer player) {
        if (player.hasDisconnected() || !player.isAlive()) {
            return;
        }
        ServerLevel level = player.serverLevel();
        float amount = WakeConfig.get().attackDamage;
        if (level.getLevelData().isHardcore() && !WakeConfig.get().attackHardcoreKills) {
            amount = Math.min(amount, player.getHealth() - 1.0F);
        }
        if (amount <= 0.0F) {
            return;
        }
        DamageSource source = new DamageSource(level.registryAccess().registryOrThrow(Registries.DAMAGE_TYPE).getHolderOrThrow(LOOKED_AWAY));
        player.invulnerableTime = 0;
        player.hurt(source, amount);
    }
}
