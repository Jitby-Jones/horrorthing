package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** The house, lived in by two: pets that watch the wrong corner, and your own voice from the next room. */
public final class Household {
    private Household() {}

    static void tick(ServerPlayer player, HauntState state, ServerLevel level, long gameTime) {
        if (gameTime % 10L == 0L) {
            petsStare(player, level);
        }
        if (gameTime % 200L == 0L && Beats.window(state, Stage.SIGHTINGS, Stage.HUSH) && !Beats.hush(state, level)) {
            yourHurtSound(player, state, level, gameTime);
        }
    }

    /** Animals see it. They do not look at you while it is there. */
    private static void petsStare(ServerPlayer player, ServerLevel level) {
        Phantom phantom = Phantoms.nearest(player, 32.0, p -> !p.isDissolving());
        if (phantom == null) {
            return;
        }
        Vec3 eye = phantom.pos().add(0.0, 1.55, 0.0);
        AABB box = player.getBoundingBox().inflate(20.0);
        for (TamableAnimal pet : level.getEntitiesOfClass(TamableAnimal.class, box, t -> t.isTame() && player.getUUID().equals(t.getOwnerUUID()))) {
            pet.getLookControl().setLookAt(eye.x, eye.y, eye.z, 360.0F, 360.0F);
            if (pet instanceof Wolf && pet.getRandom().nextInt(160) == 0) {
                pet.playSound(SoundEvents.WOLF_WHINE, 0.6F, 0.9F);
            }
        }
    }

    /** Indoors, near home, rarely: your own hurt sound, from a room you are not in. */
    private static void yourHurtSound(ServerPlayer player, HauntState state, ServerLevel level, long gameTime) {
        BlockPos bed = state.bed();
        if (bed == null || !player.blockPosition().closerThan(bed, 24.0) || level.canSeeSky(player.blockPosition())) {
            return;
        }
        if (!state.ready("hurt_sound", gameTime) || player.getRandom().nextFloat() > 0.06F) {
            return;
        }
        BlockPos source = Spots.standableNear(level, player.blockPosition(), 7, 13, player.getRandom(),
                p -> !Spots.visibleFrom(player, p), 30);
        if (source == null) {
            return;
        }
        state.cooldown("hurt_sound", gameTime, 24000L * 2);
        send(player, SoundEvents.PLAYER_HURT, SoundSource.PLAYERS, Vec3.atCenterOf(source), 0.55F, 1.0F);
    }

    /** A sound only this player hears, at a position. */
    public static void send(ServerPlayer player, SoundEvent sound, SoundSource source, Vec3 at, float volume, float pitch) {
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(sound);
        player.connection.send(new ClientboundSoundPacket(holder, source, at.x, at.y, at.z, volume, pitch, player.getRandom().nextLong()));
    }
}
