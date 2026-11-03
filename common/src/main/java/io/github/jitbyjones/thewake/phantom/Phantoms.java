package io.github.jitbyjones.thewake.phantom;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.haunt.Haunt;
import io.github.jitbyjones.thewake.haunt.HauntState;
import io.github.jitbyjones.thewake.net.PhantomPayload;
import io.github.jitbyjones.thewake.net.WakeNetwork;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;

/** Server-side registry of every player's images, and their lifecycle. */
public final class Phantoms {
    private static final Map<UUID, List<Phantom>> BY_OWNER = new HashMap<>();
    private static int nextId = 1;

    /** Hook invoked when a player walks into an image that costs time. Set by the haunt layer. */
    @Nullable
    private static TouchHandler touchHandler;

    public interface TouchHandler {
        void touched(ServerPlayer owner, Phantom phantom);
    }

    private Phantoms() {}

    public static void setTouchHandler(TouchHandler handler) {
        touchHandler = handler;
    }

    public static Phantom create(ServerPlayer owner, Vec3 pos, float yaw, Phantom.Mode mode, Phantom.Stance stance) {
        Phantom phantom = new Phantom(nextId++, pos, yaw, mode, stance);
        HauntState state = Haunt.state(owner);
        phantom.lapseGeneration = state.lapseCount();
        BY_OWNER.computeIfAbsent(owner.getUUID(), k -> new ArrayList<>()).add(phantom);
        return phantom;
    }

    public static List<Phantom> of(ServerPlayer owner) {
        return BY_OWNER.getOrDefault(owner.getUUID(), List.of());
    }

    @Nullable
    public static Phantom nearest(ServerPlayer owner, double radius, Predicate<Phantom> filter) {
        Phantom best = null;
        double bestD = radius * radius;
        for (Phantom p : of(owner)) {
            if (p.removed || !filter.test(p)) {
                continue;
            }
            double d = p.pos.distanceToSqr(owner.position());
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    public static void remove(ServerPlayer owner, Phantom phantom) {
        if (!phantom.removed) {
            phantom.removed = true;
            WakeNetwork.send(owner, PhantomPayload.remove(phantom.id, false));
        }
    }

    /** Fade out with the shutter. */
    public static void dissolve(ServerPlayer owner, Phantom phantom, boolean close) {
        if (phantom.removed || phantom.dissolveTicks >= 0) {
            return;
        }
        phantom.dissolveTicks = close ? 6 : 16;
        WakeNetwork.send(owner, PhantomPayload.remove(phantom.id, true));
        owner.serverLevel().playSound(null, phantom.pos.x, phantom.pos.y + 1.4, phantom.pos.z, WakeRegistry.SHUTTER.get(),
                SoundSource.AMBIENT, close ? 0.8F : 0.4F, 0.9F + owner.getRandom().nextFloat() * 0.15F);
    }

    public static void removeIf(ServerPlayer owner, Predicate<Phantom> test) {
        for (Phantom p : of(owner)) {
            if (!p.removed && test.test(p)) {
                remove(owner, p);
            }
        }
    }

    public static void clear(ServerPlayer owner) {
        List<Phantom> list = BY_OWNER.remove(owner.getUUID());
        if (list != null && !list.isEmpty() && !owner.hasDisconnected()) {
            WakeNetwork.send(owner, PhantomPayload.clear());
        }
    }

    public static void forget(UUID owner) {
        BY_OWNER.remove(owner);
    }

    public static void onServerStopped() {
        BY_OWNER.clear();
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<UUID, List<Phantom>>> owners = BY_OWNER.entrySet().iterator();
        while (owners.hasNext()) {
            Map.Entry<UUID, List<Phantom>> entry = owners.next();
            ServerPlayer owner = server.getPlayerList().getPlayer(entry.getKey());
            if (owner == null) {
                owners.remove();
                continue;
            }
            HauntState state = Haunt.state(owner);
            List<Phantom> list = entry.getValue();
            // Copy: behaviours may create new images while we iterate.
            for (Phantom p : new ArrayList<>(list)) {
                try {
                    tickOne(owner, state, p);
                } catch (RuntimeException e) {
                    TheWake.LOG.error("Phantom tick failed", e);
                    remove(owner, p);
                }
            }
            list.removeIf(p -> p.removed);
        }
    }

    private static void tickOne(ServerPlayer owner, HauntState state, Phantom p) {
        if (p.removed) {
            return;
        }
        if (p.dissolveTicks >= 0) {
            if (p.dissolveTicks-- <= 0) {
                p.removed = true;
            }
            return;
        }
        if (p.lapseGeneration >= 0 && state.lapseCount() > p.lapseGeneration) {
            // It moved during the lapse. There is nothing left to see.
            remove(owner, p);
            return;
        }
        if (p.pos.distanceToSqr(owner.position()) > 192.0 * 192.0) {
            remove(owner, p);
            return;
        }
        if (p.behavior != null) {
            p.behavior.tick(p, owner);
            if (p.removed || p.dissolveTicks >= 0) {
                return;
            }
        }
        if (p.life > 0 && --p.life == 0) {
            dissolve(owner, p, false);
            return;
        }
        if (p.touchRadius > 0.0 && p.mode == Phantom.Mode.EXPOSURE && p.pos.distanceToSqr(owner.position()) < p.touchRadius * p.touchRadius) {
            if (p.behavior != null) {
                p.behavior.onTouched(p, owner);
            }
            if (p.touchCostsTime && touchHandler != null) {
                remove(owner, p);
                touchHandler.touched(owner, p);
            } else {
                dissolve(owner, p, true);
            }
            return;
        }
        if (!p.added || p.dirty) {
            p.added = true;
            p.dirty = false;
            WakeNetwork.send(owner, new PhantomPayload(PhantomPayload.OP_SET, p.id, p.pos.x, p.pos.y, p.pos.z, p.yaw, p.mode.ordinal(),
                    p.stance.ordinal(), p.opacity, p.smear, p.focus, p.headYaw, p.headPitch, p.headSmear, p.bedDirection, p.fadeSpeed, p.face));
        }
    }
}
