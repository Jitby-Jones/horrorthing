package io.github.jitbyjones.thewake.haunt;

import io.github.jitbyjones.thewake.TheWake;
import io.github.jitbyjones.thewake.registry.WakeContent;
import io.github.jitbyjones.thewake.world.LiminalStructure;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.phys.Vec3;

/**
 * Development only: with {@code -Dthewake.demo4=true}, on the first server tick, finds every one
 * of its places within a few regions of spawn and generates each the way the world does, through
 * the chunk pipeline (as a player walking up to it would), then checks what came out and logs it.
 * In singleplayer it then stands you in front of, and inside, one of each kind for
 * {@code DemoStructuresClient} to screenshot.
 */
public final class DemoStructures {
    public static final boolean ENABLED = Boolean.getBoolean("thewake.demo4");
    public static volatile int startTick = -1;

    /** How many structure regions out from spawn's, each way. */
    private static final int RADIUS = 4;
    private static final int FIRST = 60;
    private static final int EVERY = 400;
    private static final int SETTLE = 360;

    /** A shot: where to stand and what to look at, in the piece's own terms (x across, y up, z from the front in). */
    public record Shot(String name, LiminalStructure.Kind kind, double ex, double ey, double ez, double lx, double ly, double lz) {}

    public static final Shot[] TOUR = {
            new Shot("s1_watchtower", LiminalStructure.Kind.WATCHTOWER, -11, 7, -14, 3.5, 8, 3.5),
            new Shot("s2_video_store", LiminalStructure.Kind.VIDEO_STORE, 1, 3.2, -7, 5, 3.5, 0),
            new Shot("s3_video_store_inside", LiminalStructure.Kind.VIDEO_STORE, 2, 1.7, 1, 6.5, 2.4, 3.5),
            new Shot("s4_waiting_room", LiminalStructure.Kind.WAITING_ROOM, -6, 5, -9, 5, 2, 4.5),
            new Shot("s5_waiting_room_inside", LiminalStructure.Kind.WAITING_ROOM, 5, 1.7, 1, 6, 1.8, 8),
            new Shot("s6_bedroom", LiminalStructure.Kind.BEDROOM, 1.5, 1.8, -2.5, 4.5, 1.4, 4),
            new Shot("s7_hallway", LiminalStructure.Kind.HALLWAY, -8, 12, -10, 2, 2, 10),
            new Shot("s8_hallway_inside", LiminalStructure.Kind.HALLWAY, 2, 1.7, 0.6, 2, 1.6, 25),
    };

    private record Found(LiminalStructure.Kind kind, BoundingBox box, Direction facing) {}

    private static final List<Found> FOUND = new ArrayList<>();
    private static final List<ChunkPos> TODO = new ArrayList<>();
    private static final Map<LiminalStructure.Kind, Integer> COUNTS = new EnumMap<>(LiminalStructure.Kind.class);
    private static boolean planned;
    private static boolean finished;
    private static int candidates;
    private static int problems;
    private static long started;

    private DemoStructures() {}

    public static int shotTick(int i) {
        return FIRST + EVERY * i + SETTLE;
    }

    public static int doneTick() {
        return shotTick(TOUR.length - 1) + 40;
    }

    /**
     * Every server tick, until it is done: one candidate place at a time, so that no single tick
     * runs into a dedicated server's watchdog.
     */
    public static void tick(MinecraftServer server) {
        if (!ENABLED || finished) {
            return;
        }
        ServerLevel level = server.overworld();
        try {
            if (!planned) {
                planned = true;
                plan(level);
            }
            if (!TODO.isEmpty()) {
                candidates++;
                check(level, TODO.remove(0));
            }
        } catch (RuntimeException e) {
            TheWake.LOG.error("[demo4] the check failed", e);
            TODO.clear();
        }
        if (TODO.isEmpty()) {
            finished = true;
            TheWake.LOG.info("[demo4] generated and checked {} places from {} candidate chunks in {} ms: {}; problems: {}",
                    FOUND.size(), candidates, (System.nanoTime() - started) / 1_000_000L, COUNTS, problems);
        }
    }

    /** Where the structure set may start one, region by region round spawn. */
    private static void plan(ServerLevel level) {
        started = System.nanoTime();
        StructureSet set = level.registryAccess().registryOrThrow(Registries.STRUCTURE_SET).get(TheWake.id("liminal"));
        if (set == null || !(set.placement() instanceof RandomSpreadStructurePlacement spread)) {
            TheWake.LOG.error("[demo4] the liminal structure set is missing");
            return;
        }
        long seed = level.getChunkSource().getGeneratorState().getLevelSeed();
        BlockPos spawn = level.getSharedSpawnPos();
        int rx0 = Math.floorDiv(SectionPos.blockToSectionCoord(spawn.getX()), spread.spacing());
        int rz0 = Math.floorDiv(SectionPos.blockToSectionCoord(spawn.getZ()), spread.spacing());
        for (int rx = rx0 - RADIUS; rx <= rx0 + RADIUS; rx++) {
            for (int rz = rz0 - RADIUS; rz <= rz0 + RADIUS; rz++) {
                // It takes a chunk in the region, not the region's index.
                TODO.add(spread.getPotentialStructureChunk(seed, rx * spread.spacing(), rz * spread.spacing()));
            }
        }
    }

    private static void check(ServerLevel level, ChunkPos at) {
        ChunkAccess starts = level.getChunk(at.x, at.z, ChunkStatus.STRUCTURE_STARTS, true);
        for (StructureStart start : starts.getAllStarts().values()) {
            if (!start.isValid() || !(start.getStructure() instanceof LiminalStructure liminal)) {
                continue;
            }
            // The piece's own box: the start's is grown by twelve all round for the terrain to blend.
            BoundingBox box = start.getPieces().get(0).getBoundingBox();
            // All of it, the way the world makes it: every chunk it stands in, to the end.
            // The last step, the shape updates, the game runs when the chunk first ticks near a player.
            for (int cx = SectionPos.blockToSectionCoord(box.minX()); cx <= SectionPos.blockToSectionCoord(box.maxX()); cx++) {
                for (int cz = SectionPos.blockToSectionCoord(box.minZ()); cz <= SectionPos.blockToSectionCoord(box.maxZ()); cz++) {
                    level.getChunk(cx, cz).postProcessGeneration();
                }
            }
            Direction facing = start.getPieces().get(0).getOrientation();
            problems += inspect(level, liminal.kind(), box, facing);
            COUNTS.merge(liminal.kind(), 1, Integer::sum);
            FOUND.add(new Found(liminal.kind(), box, facing));
        }
    }

    /** What came out: signs with their words and something to hang on, chests with loot, the book, doors onto wallpaper. */
    private static int inspect(ServerLevel level, LiminalStructure.Kind kind, BoundingBox box, Direction facing) {
        int signs = 0;
        int blank = 0;
        int loose = 0;
        int chests = 0;
        int empty = 0;
        int books = 0;
        int doors = 0;
        int outside = 0;
        int panes = 0;
        int bare = 0;
        List<String> words = new ArrayList<>();
        boolean backed = kind == LiminalStructure.Kind.HALLWAY || kind == LiminalStructure.Kind.BEDROOM;
        for (BlockPos p : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            BlockState state = level.getBlockState(p);
            BlockEntity be = state.hasBlockEntity() ? level.getBlockEntity(p) : null;
            if (be instanceof SignBlockEntity sign) {
                signs++;
                String first = sign.getFrontText().getMessage(0, false).getString();
                words.add(first);
                if (first.isEmpty() || !sign.isWaxed()) {
                    blank++;
                }
                if (state.hasProperty(WallSignBlock.FACING)
                        && !level.getBlockState(p.relative(state.getValue(WallSignBlock.FACING).getOpposite())).isSolid()) {
                    loose++;
                }
            } else if (be instanceof ChestBlockEntity chest) {
                chests++;
                if (chest.getLootTable() == null) {
                    empty++;
                }
            } else if (be instanceof LecternBlockEntity lectern && lectern.hasBook()) {
                books++;
            }
            if (state.is(Blocks.GLASS_PANE)) {
                panes++;
                if (!state.getValue(CrossCollisionBlock.NORTH) && !state.getValue(CrossCollisionBlock.EAST)
                        && !state.getValue(CrossCollisionBlock.SOUTH) && !state.getValue(CrossCollisionBlock.WEST)) {
                    bare++;
                }
            }
            if (backed && (state.is(Blocks.OAK_DOOR) || state.is(Blocks.SPRUCE_DOOR)) && state.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                doors++;
                BlockState behind = level.getBlockState(p.relative(state.getValue(DoorBlock.FACING).getOpposite()));
                if (!behind.is(WakeContent.WALLPAPER.get()) && !behind.is(WakeContent.STAINED_WALLPAPER.get())) {
                    outside++;
                }
            }
        }
        int wantSigns = switch (kind) {
            case WATCHTOWER, VIDEO_STORE, WAITING_ROOM -> 1;
            default -> 0;
        };
        int wantChests = kind == LiminalStructure.Kind.HALLWAY || kind == LiminalStructure.Kind.WAITING_ROOM ? 0 : 1;
        int wantBooks = kind == LiminalStructure.Kind.WAITING_ROOM ? 1 : 0;
        int problems = (signs != wantSigns ? 1 : 0) + blank + loose + (chests != wantChests ? 1 : 0) + empty + (books != wantBooks ? 1 : 0)
                + outside + bare;
        TheWake.LOG.info("[demo4] {} at {} facing {}: signs {} {} (blank {}, loose {}), chests {} (no loot {}), books {}, doors {} (onto the outside {}), "
                        + "panes {} (unjoined {}): {}",
                kind.getSerializedName(), new BlockPos(box.minX(), box.minY(), box.minZ()).toShortString(), facing, signs, words, blank, loose,
                chests, empty, books, doors, outside, panes, bare, problems == 0 ? "ok" : problems + " problem(s)");
        return problems;
    }

    // ---- the tour ---------------------------------------------------------------------------

    public static void onJoin(ServerPlayer player) {
        if (!ENABLED || !player.server.isSingleplayer()) {
            return;
        }
        // No watchdog in singleplayer: finish the check now, so the tour knows where everything is.
        while (!finished) {
            tick(player.server);
        }
        startTick = player.server.getTickCount();
        Director.schedule(20, () -> setup(player));
        for (int i = 0; i < TOUR.length; i++) {
            Shot shot = TOUR[i];
            Director.schedule(FIRST + EVERY * i, () -> stand(player, shot));
        }
    }

    private static void setup(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        player.setGameMode(GameType.SPECTATOR);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, player.server);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, player.server);
        level.setDayTime(5000L);
        level.setWeatherParameters(12000, 0, false, false);
    }

    private static void stand(ServerPlayer player, Shot shot) {
        if (player.hasDisconnected()) {
            return;
        }
        BlockPos spawn = player.serverLevel().getSharedSpawnPos();
        Found found = FOUND.stream().filter(f -> f.kind() == shot.kind())
                .min(Comparator.comparingDouble(f -> f.box().getCenter().distSqr(spawn))).orElse(null);
        if (found == null) {
            TheWake.LOG.warn("[demo4] no {} near spawn to show", shot.kind().getSerializedName());
            return;
        }
        Vec3 eye = world(found, shot.ex(), shot.ey(), shot.ez());
        Vec3 look = world(found, shot.lx(), shot.ly(), shot.lz());
        Vec3 d = look.subtract(eye);
        float yaw = (float) (Mth.atan2(d.z, d.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(d.y, d.horizontalDistance()) * Mth.RAD_TO_DEG);
        player.teleportTo(player.serverLevel(), eye.x, eye.y - player.getEyeHeight(), eye.z, yaw, pitch);
        TheWake.LOG.info("[demo4] {}: {} at {}", shot.name(), shot.kind().getSerializedName(), BlockPos.containing(eye).toShortString());
    }

    /** From the piece's terms to the world's, the way {@code StructurePiece} turns them. */
    private static Vec3 world(Found f, double x, double y, double z) {
        BoundingBox b = f.box();
        double wy = b.minY() + y;
        return switch (f.facing()) {
            case NORTH -> new Vec3(b.minX() + x + 0.5, wy, b.maxZ() - z + 0.5);
            case SOUTH -> new Vec3(b.minX() + x + 0.5, wy, b.minZ() + z + 0.5);
            case WEST -> new Vec3(b.maxX() - z + 0.5, wy, b.minZ() + x + 0.5);
            default -> new Vec3(b.minX() + z + 0.5, wy, b.minZ() + x + 0.5);
        };
    }
}
