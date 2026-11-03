package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.phantom.Phantom;
import io.github.jitbyjones.thewake.phantom.PhantomBehavior;
import io.github.jitbyjones.thewake.phantom.Phantoms;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * Factory for the images the Wake leaves, keeping the three photographic signals separate:
 * opacity = how long it stood, smear = it was moving, focus = how well it knows you.
 */
public final class Exposures {
    private Exposures() {}

    /** 0..1: how resolved its picture of this player is, eased so the early acts stay soft. */
    public static float likeness(HauntState state) {
        float t = Mth.clamp((state.clarity() - 0.06F) / (0.95F - 0.06F), 0.0F, 1.0F);
        return t * t * (3.0F - 2.0F * t);
    }

    /** It stood here a long time while the player was gone: opaque, unsmeared. */
    public static Phantom still(ServerPlayer owner, HauntState state, BlockPos feet, float yaw, Phantom.Stance stance) {
        return Phantoms.create(owner, Vec3.atBottomCenterOf(feet), yaw, Phantom.Mode.EXPOSURE, stance)
                .setOpacity(0.9F)
                .setSmear(0.0F)
                .setFocus(0.15F + 0.85F * likeness(state))
                .setFadeSpeed(1.0F)
                .setTouch(0.9, true);
    }

    /** It walked through here: faint, smeared along its way. */
    public static Phantom passing(ServerPlayer owner, HauntState state, BlockPos feet, float yaw) {
        return Phantoms.create(owner, Vec3.atBottomCenterOf(feet), yaw, Phantom.Mode.EXPOSURE, Phantom.Stance.WALK)
                .setOpacity(0.32F)
                .setSmear(0.85F)
                .setFocus(0.15F + 0.85F * likeness(state))
                .setFadeSpeed(1.0F)
                .setTouch(0.0, false);
    }

    /** Afterimages fade by themselves, like the dark spot left after looking at a lamp. */
    public static PhantomBehavior afterimage(int holdTicks, float fadePerTick) {
        return new PhantomBehavior() {
            private int age;

            @Override
            public void tick(Phantom phantom, ServerPlayer owner) {
                age++;
                if (age == holdTicks) {
                    phantom.setOpacity(0.0F).setFadeSpeed(fadePerTick);
                }
                if (age > holdTicks + 4.0F / fadePerTick) {
                    Phantoms.remove(owner, phantom);
                }
            }
        };
    }

    /** Distance at which sightings appear: far while it barely knows you, close once it does. */
    public static double sightingDistance(HauntState state) {
        return Mth.lerp(likeness(state), 28.0, 7.0);
    }

    /** A sighting in the player's view, facing them. */
    public static Phantom sighting(ServerPlayer owner, HauntState state, float halfCone) {
        double dist = sightingDistance(state);
        BlockPos spot = Spots.inView(owner, Math.max(3.5, dist * 0.55), dist, halfCone, 24);
        if (spot == null) {
            return null;
        }
        Vec3 at = Vec3.atBottomCenterOf(spot);
        return still(owner, state, spot, Spots.yawTowards(at, owner.position()), Phantom.Stance.STAND);
    }
}
