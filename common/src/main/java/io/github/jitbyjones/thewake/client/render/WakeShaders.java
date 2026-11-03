package io.github.jitbyjones.thewake.client.render;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.config.WakeConfig;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;

/** Holder for the two core shaders, registered by each loader's shader event. */
public final class WakeShaders {
    public static final ResourceLocation NIGHT = TheWake.id("rendertype_wake_night");
    public static final ResourceLocation RIBBON = TheWake.id("rendertype_wake_ribbon");

    @Nullable
    public static ShaderInstance night;
    @Nullable
    public static ShaderInstance ribbon;

    private static long shaderPackCheckedAt;
    private static boolean shaderPackActive;
    private static Method irisCheck;
    private static Object irisApi;
    private static boolean irisLookedUp;

    private WakeShaders() {}

    /** Use the night-sky shaders, or fall back to plain textures (shader packs, or turned off in config). */
    public static boolean usable() {
        return night != null && ribbon != null && WakeConfig.get().nightSkyShader && !shaderPackActive();
    }

    /** Iris/Oculus replace the pipeline; custom core shaders may render black there. Checked once a second. */
    public static boolean shaderPackActive() {
        long now = Util.getMillis();
        if (now - shaderPackCheckedAt < 1000L) {
            return shaderPackActive;
        }
        shaderPackCheckedAt = now;
        if (!irisLookedUp) {
            irisLookedUp = true;
            try {
                Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                irisApi = api.getMethod("getInstance").invoke(null);
                irisCheck = api.getMethod("isShaderPackInUse");
            } catch (ReflectiveOperationException | LinkageError ignored) {
                irisApi = null;
            }
        }
        boolean active = false;
        if (irisApi != null) {
            try {
                active = (Boolean) irisCheck.invoke(irisApi);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                active = false;
            }
        }
        shaderPackActive = active;
        return active;
    }

    /** Real seconds, so the stars keep moving even where game time does not. */
    public static void applyTime() {
        float seconds = (Util.getMillis() % 3_600_000L) / 1000.0F;
        if (night != null) {
            night.safeGetUniform("WakeTime").set(seconds);
        }
        if (ribbon != null) {
            ribbon.safeGetUniform("WakeTime").set(seconds);
        }
    }
}
