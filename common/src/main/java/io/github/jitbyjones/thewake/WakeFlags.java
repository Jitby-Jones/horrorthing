package io.github.jitbyjones.thewake;

import java.util.UUID;
import javax.annotation.Nullable;

/**
 * Side-neutral flags that common mixins read. Holds no client classes, so it is safe to load on a
 * dedicated server.
 */
public final class WakeFlags {
    /** This client's TickRateManager while it is in the Stopped; null otherwise. */
    @Nullable
    public static volatile Object frozenClientTicks;
    /** The local player, whom the Stopped never freezes. */
    @Nullable
    public static volatile UUID localPlayer;

    private WakeFlags() {}
}
