package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;

/**
 * How afraid the player ought to be. It rises slowly through the night, in the dark, underground,
 * alone, hurt; every knock and every glimpse adds to it; daylight and company let it down. The
 * director spends it: a little on noises, a lot on a sighting, all of it on a chase. After a real
 * scare it drops nearly to nothing and stays low for a while, so the quiet can do its work.
 */
public final class Tension {
    private Tension() {}

    public static void spike(HauntState state, float amount) {
        state.tension = Mth.clamp(state.tension + amount, 0.0F, 1.0F);
    }

    /** Once a second. */
    static void tick(ServerPlayer player, HauntState state, long gameTime) {
        ServerLevel level = player.serverLevel();
        if (!Haunt.awake(state) || Temper.removed(state)) {
            state.tension = Math.max(0.0F, state.tension - 0.02F);
            return;
        }
        BlockPos head = player.blockPosition().above();
        int sky = level.getBrightness(LightLayer.SKY, head) - level.getSkyDarken();
        int block = level.getBrightness(LightLayer.BLOCK, head);
        boolean night = Haunt.isNight(level) || !level.dimensionType().hasSkyLight();
        boolean underground = level.getBrightness(LightLayer.SKY, head) == 0 && head.getY() < level.getSeaLevel();
        boolean ours = WakeDimensions.isOurs(level);
        float rise;
        if (ours) {
            rise = 0.009F;
        } else if (underground && block < 8) {
            rise = 0.006F;
        } else if (night && sky < 8) {
            rise = block >= 12 ? 0.0015F : 0.0042F;
        } else if (Math.max(sky, block) <= 6) {
            rise = 0.003F;
        } else {
            rise = -0.012F;
        }
        if (rise > 0.0F) {
            if (alone(player)) {
                rise *= 1.3F;
            }
            if (player.getHealth() < 8.0F) {
                rise += 0.002F;
            }
            rise *= switch (Temper.form(state)) {
                case WATCHER -> 0.6F;
                case UNEASY -> 0.9F;
                case ANGRY -> 1.2F;
                case FURIOUS -> 1.5F;
            };
            if (!state.peakStage().atLeast(Stage.SIGHTINGS)) {
                rise *= 0.5F;
            }
            rise *= (float) Math.sqrt(WakeConfig.get().pace);
            // The long quiet after a scare.
            if (gameTime - state.lastScareTick < 20L * 120) {
                rise *= 0.15F;
            }
        }
        state.tension = Mth.clamp(state.tension + rise, 0.0F, 1.0F);
    }

    private static boolean alone(ServerPlayer player) {
        for (ServerPlayer other : player.serverLevel().players()) {
            if (other != player && !other.isSpectator() && other.distanceToSqr(player) < 48.0 * 48.0) {
                return false;
            }
        }
        return true;
    }
}
