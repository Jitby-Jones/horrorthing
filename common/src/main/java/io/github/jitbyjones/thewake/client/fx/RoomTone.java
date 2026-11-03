package io.github.jitbyjones.thewake.client.fx;

import io.github.jitbyjones.thewake.client.ClientPhantom;
import io.github.jitbyjones.thewake.client.ClientPhantoms;
import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.AbstractTickableSoundInstance;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/** Near it the air sounds like a different room: a bedroom at 3 a.m. Very quiet. */
public final class RoomTone {
    private static final double RANGE = 14.0;
    @Nullable
    private static Loop playing;

    private RoomTone() {}

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || StoppedClient.active()) {
            stop();
            return;
        }
        ClientPhantom near = ClientPhantoms.nearest(mc.player.position(), RANGE);
        if (near == null) {
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
        Loop() {
            super(WakeRegistry.ROOM_TONE.get(), SoundSource.AMBIENT, RandomSource.create());
            this.looping = true;
            this.delay = 0;
            this.volume = 0.01F;
        }

        @Override
        public void tick() {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) {
                this.stop();
                return;
            }
            ClientPhantom near = ClientPhantoms.nearest(mc.player.position(), RANGE + 2.0);
            if (near == null) {
                this.stop();
                return;
            }
            Vec3 at = near.pos.add(0.0, 1.5, 0.0);
            this.x = at.x;
            this.y = at.y;
            this.z = at.z;
            double d = near.pos.distanceTo(mc.player.position());
            float closeness = (float) Math.max(0.0, 1.0 - d / RANGE);
            this.volume = Math.max(0.01F, closeness * 0.6F * WakeConfig.get().soundVolume);
        }
    }
}
