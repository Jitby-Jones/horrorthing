package io.github.jitbyjones.thewake;

import io.github.jitbyjones.thewake.config.WakeConfig;
import io.github.jitbyjones.thewake.platform.WakePlatform;
import io.github.jitbyjones.thewake.registry.WakeRegistry;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TheWake {
    public static final String MOD_ID = "thewake";
    public static final Logger LOG = LoggerFactory.getLogger("The Wake");

    private static WakePlatform platform;

    private TheWake() {}

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    public static WakePlatform platform() {
        if (platform == null) {
            throw new IllegalStateException("The Wake was used before its loader entrypoint ran");
        }
        return platform;
    }

    /** Called first thing from each loader's main entrypoint. */
    public static void init(WakePlatform loaderPlatform) {
        platform = loaderPlatform;
        WakeConfig.load(platform.configDir());
        WakeRegistry.register();
        io.github.jitbyjones.thewake.haunt.Lapses.bootstrap();
        LOG.info("Loaded on {}. Someone has to stay up.", platform.loaderName());
    }
}
