package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.net.StoppedPayload;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * The client side of the Stopped: the world frozen on one frame, the sky stuck, no sound but your
 * own steps, and every place you ever walked lying on the ground as a ribbon of night.
 */
public final class StoppedClient {
    private static volatile boolean active;
    private static long frozenDayTime;
    private static final Long2ByteOpenHashMap RIBBONS = new Long2ByteOpenHashMap();
    private static final LongOpenHashSet FRESH = new LongOpenHashSet();
    @Nullable
    private static BlockPos lastFresh;
    @Nullable
    private static Stillness stillness;

    private StoppedClient() {}

    public static boolean active() {
        return active;
    }

    public static Long2ByteOpenHashMap ribbons() {
        return RIBBONS;
    }

    public static LongOpenHashSet fresh() {
        return FRESH;
    }

    public static void handle(StoppedPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (payload.active()) {
            active = true;
            frozenDayTime = payload.frozenDayTime();
            RIBBONS.clear();
            FRESH.clear();
            long[] crumbs = payload.crumbs();
            byte[] wear = payload.wear();
            for (int i = 0; i < crumbs.length; i++) {
                RIBBONS.put(crumbs[i], i < wear.length ? wear[i] : 1);
            }
            mc.getMusicManager().stopPlaying();
            mc.getSoundManager().stop();
            freeze(mc.level, true);
            stillness = new Stillness();
            mc.getSoundManager().play(stillness);
        } else {
            active = false;
            freeze(mc.level, false);
            RIBBONS.clear();
            FRESH.clear();
            if (stillness != null) {
                mc.getSoundManager().stop(stillness);
                stillness = null;
            }
            if (payload.outcome() == StoppedPayload.OUTCOME_ESCAPED) {
                // Colour, sound and time come back as you wake.
                Overlays.wakeTint(60);
            }
        }
    }

    private static void freeze(@Nullable ClientLevel level, boolean frozen) {
        if (level == null) {
            return;
        }
        level.tickRateManager().setFrozen(frozen);
        if (frozen) {
            level.setDayTime(frozenDayTime);
        }
    }

    public static void tick() {
        if (!active) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        // Keep the world stopped, whatever the server says about it.
        if (!mc.level.tickRateManager().isFrozen()) {
            mc.level.tickRateManager().setFrozen(true);
        }
        mc.level.setDayTime(frozenDayTime);
        if (mc.player.onGround()) {
            BlockPos feet = mc.player.blockPosition();
            if (!feet.equals(lastFresh)) {
                lastFresh = feet;
                if (!RIBBONS.containsKey(feet.asLong())) {
                    FRESH.add(feet.asLong());
                }
            }
        }
    }

    public static void reset() {
        active = false;
        RIBBONS.clear();
        FRESH.clear();
        stillness = null;
    }

    /** Which sounds may play in the Stopped: yours, its, and the menu's. */
    public static boolean allows(SoundInstance sound) {
        if (sound.getLocation().getNamespace().equals(io.github.jitbyjones.thewake.TheWake.MOD_ID)) {
            return true;
        }
        SoundSource source = sound.getSource();
        return source == SoundSource.PLAYERS || source == SoundSource.BLOCKS || source == SoundSource.MASTER;
    }

    /** The faint ringing of a room with no sound in it. */
    private static final class Stillness extends AbstractTickableSoundInstance {
        Stillness() {
            super(WakeRegistry.STILLNESS.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.5F;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
        }

        @Override
        public void tick() {
            if (!active) {
                this.stop();
            }
        }
    }
}
