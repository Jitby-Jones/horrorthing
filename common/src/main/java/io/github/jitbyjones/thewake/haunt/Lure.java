package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.config.WakeConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * The knock was real. When something knocks on your wall, scrapes in the cave or opens your door,
 * and you go to see what it was, sometimes it is still there. Watching, it never is. Uneasy, now
 * and then. Angry, often. Furious, almost always: going to look is how you start a chase.
 */
public final class Lure {
    private record Spot(Vec3 at, long until) {}

    private static final Map<UUID, Spot> SPOTS = new HashMap<>();

    private Lure() {}

    public static void onServerStopped() {
        SPOTS.clear();
    }

    /** A torment came from {@code at}: for half a minute, going there means something. */
    static void set(ServerPlayer player, Vec3 at) {
        SPOTS.put(player.getUUID(), new Spot(at, player.level().getGameTime() + 20L * 30));
    }

    /** Once a second, from {@link Pursuit#tick}. True if it was there. */
    static boolean tick(ServerPlayer player, HauntState state, long gameTime) {
        Spot spot = SPOTS.get(player.getUUID());
        if (spot == null) {
            return false;
        }
        if (gameTime > spot.until()) {
            SPOTS.remove(player.getUUID());
            return false;
        }
        double d = spot.at().distanceTo(player.position().add(0.0, 1.0, 0.0));
        if (d > 4.5) {
            return false;
        }
        SPOTS.remove(player.getUUID());
        Reputation.Form form = Temper.form(state);
        float chance = switch (form) {
            case WATCHER -> 0.0F;
            case UNEASY -> 0.15F;
            case ANGRY -> 0.4F;
            case FURIOUS -> 0.8F;
        };
        if (!WakeConfig.get().ambushes || player.getRandom().nextFloat() >= chance) {
            return false;
        }
        Vec3 at = near(player, spot.at());
        return at != null && Ambush.start(player, state, at, Ambush.Kind.CORNER, null);
    }

    /** Right there, a couple of blocks from you, where you have not looked yet. */
    @Nullable
    private static Vec3 near(ServerPlayer player, Vec3 lure) {
        ServerLevel level = player.serverLevel();
        BlockPos c = BlockPos.containing(lure);
        BlockPos spot = Spots.standableNear(level, c, 0, 3, player.getRandom(),
                p -> !Spots.visibleFrom(player, p) && Vec3.atBottomCenterOf(p).distanceTo(player.position()) > 1.8, 30);
        if (spot == null) {
            spot = Spots.standableNear(level, player.blockPosition(), 2, 4, player.getRandom(),
                    p -> Appearances.angleFromView(player, Vec3.atCenterOf(p).add(0.0, 1.0, 0.0)) > 70.0F, 30);
        }
        return spot == null ? null : Vec3.atBottomCenterOf(spot);
    }
}
