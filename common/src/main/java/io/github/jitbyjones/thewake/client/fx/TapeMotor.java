package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.client.ClientState;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;

/**
 * While it moves, the tape runs: a thin motor warble under everything, the sound of something
 * being played back. Only ever while you are in a menu, asleep or away, because that is the only
 * time it moves. Out of the menu: nothing.
 */
public final class TapeMotor {
    @Nullable
    private static Loop playing;

    private TapeMotor() {}

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean on = mc.player != null && ClientState.hunting && WakeConfig.get().soundVolume > 0.0F;
        if (!on) {
            stop();
            return;
        }
        if (playing == null || playing.isStopped() || !mc.getSoundManager().isActive(playing)) {
            playing = new Loop();
            mc.getSoundManager().play(playing);
        }
    }

    public static void stop() {
        if (playing != null) {
            Minecraft.getInstance().getSoundManager().stop(playing);
            playing = null;
        }
    }

    private static final class Loop extends AbstractTickableSoundInstance {
        private int age;

        Loop() {
            super(WakeRegistry.WARBLE.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.relative = true;
            this.attenuation = SoundInstance.Attenuation.NONE;
            this.volume = 0.01F;
        }

        @Override
        public void tick() {
            age++;
            if (!ClientState.hunting) {
                this.stop();
                return;
            }
            this.volume = Math.min(1.0F, age / 30.0F) * 0.32F * WakeConfig.get().soundVolume;
        }

        @Override
        public boolean canStartSilent() {
            return true;
        }
    }
}
