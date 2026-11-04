package io.github.jitbyjones.thewake.world;

import io.github.jitbyjones.thewake.TheWake;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Which beds are not beds. */
public final class LiminalBeds {
    public static final ResourceKey<Structure> BEDROOM = ResourceKey.create(Registries.STRUCTURE, TheWake.id("bedroom"));

    private LiminalBeds() {}

    /** A bed inside one of its bedrooms in the woods. */
    public static boolean isStrangeBed(ServerLevel level, BlockPos pos) {
        Structure bedroom = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(BEDROOM);
        if (bedroom == null) {
            return false;
        }
        return level.structureManager().getStructureWithPieceAt(pos, bedroom).isValid();
    }
}
