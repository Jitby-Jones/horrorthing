package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.entity.WakeBrain;
import io.github.jitbyjones.thewake.entity.WakeEntity;
import io.github.jitbyjones.thewake.net.WakeFxPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * The flash. For a moment everything in front of you is lit as bright as day, and anything with
 * its eyes open is blinded. The images it leaves of itself do not survive being photographed.
 */
public final class Flash {
    private Flash() {}

    public static void fire(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        level.playSound(null, player.blockPosition(), WakeRegistry.CAMERA_FLASH.get(), SoundSource.PLAYERS, 1.2F * WakeConfig.get().soundVolume, 1.0F);
        WakeNetwork.send(player, WakeFxPayload.of(WakeFxPayload.FLASH, 1.0F, 0.0F));
        light(level, player);
        HauntState state = Haunt.state(player);
        WakeEntity body = Pursuit.body(player);
        if (body != null && body.brain() != null && inFrame(player, body.position().add(0.0, Math.min(2.6, body.clip().height * 0.85), 0.0), 20.0, 28.0F)) {
            WakeBrain brain = body.brain();
            Temper.learn(player, state, Reputation.Tactic.FLASH);
            if (brain.eyesShut()) {
                // Its eyes were shut. It knew.
                return;
            }
            Temper.provoke(player, state, Temper.Provocation.FLASHED);
            int ticks = brain.lastNight() ? 70 : 80;
            brain.stun(player, ticks, brain.mode() == WakeBrain.Mode.STALK || brain.mode() == WakeBrain.Mode.LURK
                    ? WakeBrain.Mode.LEAVE : WakeBrain.Mode.CHASE);
            return;
        }
        Phantom phantom = Phantoms.nearest(player, 28.0, p -> p.scale() == 1.0F && !p.isDissolving()
                && inFrame(player, p.pos().add(0.0, p.pose().height * 0.8, 0.0), 28.0, 30.0F));
        if (phantom != null) {
            Phantoms.dissolve(player, phantom, true);
            Hunter.release(player, false);
            Temper.provoke(player, state, Temper.Provocation.LIGHT);
        }
    }

    /** In front of the lens and nothing in the way. */
    static boolean inFrame(ServerPlayer player, Vec3 target, double range, float halfAngle) {
        Vec3 eye = player.getEyePosition();
        if (target.distanceTo(eye) > range || Appearances.angleFromView(player, target) > halfAngle) {
            return false;
        }
        BlockHitResult hit = player.level().clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceToSqr(target) < 1.0;
    }

    /** A real light for three ticks where you stand, so the room really does light up. */
    private static void light(ServerLevel level, ServerPlayer player) {
        BlockPos at = BlockPos.containing(player.getEyePosition());
        if (!level.getBlockState(at).isAir()) {
            return;
        }
        level.setBlock(at, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
        Director.schedule(3, () -> {
            if (level.getBlockState(at).is(Blocks.LIGHT)) {
                level.setBlock(at, Blocks.AIR.defaultBlockState(), 3);
            }
        });
    }
}
