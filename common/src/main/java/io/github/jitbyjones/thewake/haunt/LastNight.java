package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.creature.CreaturePose;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.entity.WakeSounds;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/**
 * How it ends, if you want it to. You found your tape in the Archive. You put it in the machine by
 * your bed, at night, and press play, and it comes for you as it has never come: the last night.
 *
 * <p>Its face is a card held on by four pins. Blind it with the flash and, while it cannot see,
 * reach up and pull one out. Each time, it screams, the tape skips, and it comes back faster. With
 * the fourth the card hangs by a corner, and it stops running. Now it does what it always did: it
 * moves only while you are not watching, and it makes you not watch, opening your inventory, your
 * chat, pausing your game. Walk back to the machine with your eyes on it, and press eject.
 *
 * <p>Pins pulled stay pulled, between attempts and between nights.
 */
public final class LastNight {
    public enum Phase { PINS, EJECT }

    private static final Map<UUID, Night> NIGHTS = new HashMap<>();

    private static final class Night {
        final BlockPos machine;
        Phase phase = Phase.PINS;
        int ticks;
        int forceClock = 120;
        int returnClock;

        Night(BlockPos machine) {
            this.machine = machine.immutable();
        }
    }

    private LastNight() {}

    public static boolean active(ServerPlayer player) {
        return NIGHTS.containsKey(player.getUUID());
    }

    public static boolean ejecting(ServerPlayer player) {
        Night n = NIGHTS.get(player.getUUID());
        return n != null && n.phase == Phase.EJECT;
    }

    @Nullable
    public static BlockPos machine(ServerPlayer player) {
        Night n = NIGHTS.get(player.getUUID());
        return n == null ? null : n.machine;
    }

    public static void onServerStopped() {
        NIGHTS.clear();
    }

    /** Leaving the game in the middle of it: the night is over, and nothing changed. */
    public static void onLogout(ServerPlayer player) {
        if (NIGHTS.remove(player.getUUID()) != null) {
            Haunt.state(player).syncDirty = true;
        }
    }

    /** The tape goes in. Returns false (and says why, in the machine's way) if it will not play. */
    public static boolean begin(ServerPlayer player, BlockPos machine) {
        HauntState state = Haunt.state(player);
        ServerLevel level = player.serverLevel();
        if (Temper.removed(state) || active(player)) {
            return false;
        }
        if (!Haunt.isNight(level) && level.dimensionType().hasSkyLight()) {
            player.displayClientMessage(Component.translatable("block.thewake.vcr.not_now"), true);
            return false;
        }
        Night night = new Night(machine);
        NIGHTS.put(player.getUUID(), night);
        state.syncDirty = true;
        level.playSound(null, machine, WakeRegistry.CLICK.get(), SoundSource.BLOCKS, 1.0F, 0.9F);
        level.playSound(null, machine, WakeRegistry.WARBLE.get(), SoundSource.BLOCKS, 0.8F, 0.8F);
        // Wherever the phantoms are, they are gone. Only the one is left.
        Phantoms.clear(player);
        Hunter.release(player, true);
        Director.schedule(60, () -> {
            if (player.hasDisconnected() || !active(player)) {
                return;
            }
            if (Temper.pins(state) >= 4) {
                beginEject(player, state, NIGHTS.get(player.getUUID()));
            } else {
                summon(player);
            }
        });
        TheWake.LOG.info("[{}] the last night begins ({} pins out)", player.getGameProfile().getName(), Temper.pins(state));
        return true;
    }

    private static void summon(ServerPlayer player) {
        Vec3 at = Pursuit.near(player, 16.0, 24.0, true);
        if (at == null) {
            at = Pursuit.near(player, 8.0, 30.0, false);
        }
        if (at != null) {
            Pursuit.spawn(player, at, WakeBrain.Purpose.LAST_NIGHT);
        }
    }

    /** Every tick for a player in the last night. */
    static void tick(ServerPlayer player, HauntState state) {
        Night night = NIGHTS.get(player.getUUID());
        if (night == null) {
            return;
        }
        night.ticks++;
        ServerLevel level = player.serverLevel();
        // Morning, or you died: it failed. The tape stays in the machine; the pins stay out.
        if (!player.isAlive() || Haunt.timeOfDay(level) > 23400L && level.dimensionType().hasSkyLight() && night.ticks > 200) {
            end(player, state, false);
            return;
        }
        if (night.phase == Phase.PINS) {
            if (!Pursuit.active(player) && night.ticks > 80 && ++night.returnClock > 60) {
                night.returnClock = 0;
                summon(player);
            }
            return;
        }
        // Eject: it moves only while you are not watching, and now it makes you look away.
        Phantom creature = Hunter.creature(player);
        if (creature == null) {
            placeForEject(player, night);
            return;
        }
        if (--night.forceClock <= 0) {
            night.forceClock = 110 + player.getRandom().nextInt(90);
            // (The scripted demo has no hands to unpause with: the inventory only.)
            int kind = DemoExpansion.ENABLED ? 0 : player.getRandom().nextInt(3);
            WakeNetwork.send(player, new WakeFxPayload(WakeFxPayload.FORCE_SCREEN, 0, 0, 0, kind, 0));
        }
    }

    private static void beginEject(ServerPlayer player, HauntState state, @Nullable Night night) {
        if (night == null) {
            return;
        }
        night.phase = Phase.EJECT;
        night.forceClock = 140;
        WakeEntity body = Pursuit.body(player);
        if (body != null) {
            Pursuit.ended(player, state, body, Pursuit.Outcome.GAVE_UP);
        }
        placeForEject(player, night);
    }

    private static void placeForEject(ServerPlayer player, Night night) {
        Vec3 at = Pursuit.near(player, 10.0, 16.0, false);
        if (at == null) {
            return;
        }
        Phantom p = Appearances.still(player, BlockPos.containing(at), Spots.yawTowards(at, player.position()), CreaturePose.STAND);
        p.setTouch(0.0, false);
        Hunter.adopt(player, p);
    }

    /** It is blinded and in reach: a pin comes out. */
    public static void pullPin(ServerPlayer player, HauntState state, WakeEntity body, boolean asleep) {
        if (Temper.pins(state) >= 4) {
            return;
        }
        if (asleep && state.reputation().pinsWhileAsleep() >= 2) {
            // It will not sleep through that a third time.
            body.brain(player).wake(player, state);
            return;
        }
        int pins = Temper.pullPin(state, asleep);
        body.setPins(pins);
        Temper.provoke(player, state, Temper.Provocation.PIN);
        ItemStack pin = new ItemStack(WakeContent.PIN.get());
        if (!player.getInventory().add(pin)) {
            player.drop(pin, false);
        }
        ServerLevel level = player.serverLevel();
        level.playSound(null, body.blockPosition(), SoundEvents.TRIDENT_HIT, SoundSource.HOSTILE, 1.0F, 1.6F);
        WakeSounds.scream(player, WakeRegistry.CHASE_SCREAM.get(), body.position().add(0.0, 2.6, 0.0), 0.7F);
        WakeNetwork.send(player, new WakeFxPayload(WakeFxPayload.SHAKE, 0, 0, 0, 1.0F, 20.0F));
        TheWake.LOG.info("[{}] pulled a pin ({}/4){}", player.getGameProfile().getName(), pins, asleep ? " while it slept" : "");
        if (asleep) {
            body.brain(player).wake(player, state);
            return;
        }
        Night night = NIGHTS.get(player.getUUID());
        if (pins >= 4 && night != null) {
            beginEject(player, state, night);
        }
    }

    /** After a stun on the last night: the tape skips it away, and it comes again. */
    public static void afterStun(ServerPlayer player, HauntState state, WakeEntity body) {
        Vec3 away = Pursuit.near(player, 14.0, 22.0, true);
        if (away != null) {
            Pursuit.tapeSkip(player, body, away);
        }
    }

    /** The machine, used again. In the eject phase, this is the end of it. */
    public static boolean eject(ServerPlayer player, BlockPos machine) {
        Night night = NIGHTS.get(player.getUUID());
        if (night == null || !night.machine.equals(machine)) {
            return false;
        }
        HauntState state = Haunt.state(player);
        if (night.phase != Phase.EJECT) {
            // Stopped halfway: it is still out there, and the night goes on.
            return false;
        }
        end(player, state, true);
        return true;
    }

    private static void end(ServerPlayer player, HauntState state, boolean removed) {
        Night night = NIGHTS.remove(player.getUUID());
        WakeEntity body = Pursuit.body(player);
        if (body != null) {
            Pursuit.ended(player, state, body, removed ? Pursuit.Outcome.REMOVED : Pursuit.Outcome.GAVE_UP);
        }
        Hunter.release(player, true);
        Phantoms.clear(player);
        state.syncDirty = true;
        if (!removed) {
            return;
        }
        ServerLevel level = player.serverLevel();
        // The card falls. Somebody's face, on an index card, with the eyes cut out.
        Temper.markRemoved(state);
        state.setEnded(true, false);
        state.setFlag("removed");
        WakeNetwork.send(player, WakeFxPayload.of(WakeFxPayload.REMOVED, 1.0F, 0.0F));
        level.playSound(null, night.machine, WakeRegistry.CLICK.get(), SoundSource.BLOCKS, 1.2F, 0.7F);
        Vec3 at = Vec3.atCenterOf(night.machine).add(0.0, 0.6, 0.0);
        ItemStack card = new ItemStack(WakeContent.CARD.get());
        level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, card));
        ItemStack tape = new ItemStack(WakeContent.TAPE.get());
        tape.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, Component.translatable("item.thewake.tape.blank"));
        level.addFreshEntity(new ItemEntity(level, at.x, at.y, at.z, tape));
        io.github.jitbyjones.thewake.block.VcrBlock.setTape(level, night.machine, false);
        // The night is over.
        if (player.server.isSingleplayer() && level.dimensionType().hasSkyLight()) {
            long day = level.getDayTime() / 24000L;
            level.setDayTime((day + 1) * 24000L);
        }
        TheWake.LOG.info("[{}] the card came off", player.getGameProfile().getName());
        if (WakeConfig.get().soundVolume > 0.0F) {
            level.playSound(null, player.blockPosition(), SoundEvents.AMBIENT_CAVE.value(), SoundSource.AMBIENT, 0.0F, 1.0F);
        }
    }

    /** The bite during eject: it puts you down and goes back a way. */
    public static void onEjectBite(ServerPlayer player) {
        Night night = NIGHTS.get(player.getUUID());
        if (night != null) {
            night.forceClock = 200;
        }
    }
}
