package io.github.jitbyjones.thewake.client;

import io.github.jitbyjones.thewake.net.PhantomPayload;
import it.unimi.dsi.fastutil.ints.Int2ObjectLinkedOpenHashMap;
import java.util.Collection;
import javax.annotation.Nullable;
import net.minecraft.world.phys.Vec3;

/** Every image this client has been told about. Nobody else's client knows they exist. */
public final class ClientPhantoms {
    private static final Int2ObjectLinkedOpenHashMap<ClientPhantom> ALL = new Int2ObjectLinkedOpenHashMap<>();

    private ClientPhantoms() {}

    static void handle(PhantomPayload payload) {
        switch (payload.op()) {
            case PhantomPayload.OP_SET -> ALL.computeIfAbsent(payload.id(),
                    id -> new ClientPhantom(id, new Vec3(payload.x(), payload.y(), payload.z()))).apply(payload);
            case PhantomPayload.OP_REMOVE -> ALL.remove(payload.id());
            case PhantomPayload.OP_DISSOLVE -> {
                ClientPhantom p = ALL.get(payload.id());
                if (p != null) {
                    p.dissolve();
                }
            }
            case PhantomPayload.OP_CLEAR -> ALL.clear();
            default -> {
            }
        }
    }

    static void tick() {
        ALL.values().removeIf(p -> !p.tick());
    }

    public static Collection<ClientPhantom> all() {
        return ALL.values();
    }

    public static boolean isEmpty() {
        return ALL.isEmpty();
    }

    /** Nearest image within {@code radius} of a point, for clocks. */
    @Nullable
    public static ClientPhantom nearest(Vec3 at, double radius) {
        ClientPhantom best = null;
        double bestD = radius * radius;
        for (ClientPhantom p : ALL.values()) {
            double d = p.pos.distanceToSqr(at);
            if (d < bestD && !p.dissolving) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    static void clear() {
        ALL.clear();
    }
}
