package io.github.jitbyjones.thewake.client.fx;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.client.ClientState;
import io.github.jitbyjones.thewake.client.render.Silhouette;
import io.github.jitbyjones.thewake.client.render.WakeRenderTypes;
import io.github.jitbyjones.thewake.net.PausePayload;
import io.github.jitbyjones.thewake.phantom.Phantom;
import javax.annotation.Nullable;
import net.minecraft.Util;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

/**
 * 1.21 blurs the world behind the pause menu. Once, in singleplayer, one thing behind it is not
 * blurred. It does not move. When you resume, it is not there.
 */
public final class PauseFigure {
    private static final Matrix4f PROJECTION = new Matrix4f();
    private static final Matrix4f MODEL_VIEW = new Matrix4f();
    private static Vec3 cameraPos = Vec3.ZERO;
    private static boolean captured;

    private static long pausedAt = -1L;
    private static long seenMillis;
    private static long lastSeenFrame = -1L;
    @Nullable
    private static Vec3 spot;
    private static float spotYaw;

    private PauseFigure() {}

    /** Called during the level render with the world's matrices current. */
    public static void capture(Camera camera) {
        PROJECTION.set(RenderSystem.getProjectionMatrix());
        MODEL_VIEW.set(RenderSystem.getModelViewMatrix());
        cameraPos = camera.getPosition();
        captured = true;
    }

    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        boolean paused = mc.isPaused() && mc.screen instanceof PauseScreen && mc.level != null && mc.player != null;
        long now = Util.getMillis();
        if (paused) {
            if (pausedAt < 0) {
                pausedAt = now;
                seenMillis = 0;
                spot = null;
            }
            return;
        }
        if (pausedAt >= 0) {
            int millis = (int) Math.min(Integer.MAX_VALUE, now - pausedAt);
            boolean seen = seenMillis >= 1500L;
            pausedAt = -1L;
            spot = null;
            if (seen) {
                ClientState.pauseArmed = false;
            }
            if (mc.getConnection() != null && mc.hasSingleplayerServer()) {
                try {
                    TheWake.platform().sendToServer(new PausePayload(millis, seen));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    /** Screen#renderBackground has just drawn the blur: draw the one sharp thing over it. */
    public static void afterBackground(Screen screen) {
        if (!(screen instanceof PauseScreen) || pausedAt < 0 || !ClientState.pauseArmed || !captured || StoppedClient.active()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        long now = Util.getMillis();
        if (now - pausedAt < 5000L) {
            return;
        }
        if (spot == null && !choose(mc)) {
            return;
        }
        // It only counts as seen while the window has focus: an alt-tabbed player saw nothing.
        if (lastSeenFrame > 0 && mc.isWindowActive()) {
            seenMillis += Math.min(100L, now - lastSeenFrame);
        }
        lastSeenFrame = now;
        draw(mc);
    }

    /** Off to one side, where the menu does not cover it: you have to look past the buttons to see it. */
    private static boolean choose(Minecraft mc) {
        Vec3 eye = cameraPos;
        float side = mc.level.random.nextBoolean() ? 1.0F : -1.0F;
        for (float s : new float[] {side, -side}) {
            Vec3 look = Vec3.directionFromRotation(0.0F, mc.player.getYRot() + s * 38.0F);
            for (double d = 9.0; d >= 5.0; d -= 1.0) {
                BlockPos column = BlockPos.containing(eye.add(look.scale(d)));
                for (int dy = 2; dy >= -6; dy--) {
                    BlockPos feet = column.offset(0, dy, 0);
                    if (mc.level.getBlockState(feet).getCollisionShape(mc.level, feet).isEmpty()
                            && mc.level.getBlockState(feet.above()).getCollisionShape(mc.level, feet.above()).isEmpty()
                            && !mc.level.getBlockState(feet.below()).getCollisionShape(mc.level, feet.below()).isEmpty()) {
                        spot = Vec3.atBottomCenterOf(feet);
                        double dx = eye.x - spot.x;
                        double dz = eye.z - spot.z;
                        spotYaw = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void draw(Minecraft mc) {
        RenderSystem.backupProjectionMatrix();
        RenderSystem.setProjectionMatrix(PROJECTION, VertexSorting.DISTANCE_TO_ORIGIN);
        Matrix4fStack stack = RenderSystem.getModelViewStack();
        stack.pushMatrix();
        stack.set(MODEL_VIEW);
        RenderSystem.applyModelViewMatrix();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        RenderType type = WakeRenderTypes.figure(true);
        float focus = 0.4F + 0.6F * Mth.clamp((ClientState.clarity - 0.3F) / 0.6F, 0.0F, 1.0F);
        Silhouette.draw(new PoseStack(), buffers.getBuffer(type), spot.subtract(cameraPos), spotYaw, Phantom.Stance.STAND, -1,
                1.0F, 0.0F, focus, 0.0F, 6.0F, 0.0F, 0.0F, 0.0F, Vec3.ZERO, 7, 1.0F);
        buffers.endBatch(type);
        stack.popMatrix();
        RenderSystem.applyModelViewMatrix();
        RenderSystem.restoreProjectionMatrix();
    }

    public static void reset() {
        pausedAt = -1L;
        spot = null;
        captured = false;
    }
}
