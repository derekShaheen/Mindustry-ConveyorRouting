package conveyoroptimizer.graph;

import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.blocks.distribution.*;

/**
 * Classifies a Mindustry block into its logistics role.
 * Only blocks with a recognized logistics role are candidates for optimization.
 */
public enum BlockRole {
    CONVEYOR,
    JUNCTION,
    ROUTER,
    SORTER,
    OVERFLOW,
    BRIDGE,
    UNLOADER,
    NON_LOGISTICS,
    AIR;

    /**
     * Determine the logistics role of a block.
     * Uses instanceof checks against the Mindustry class hierarchy so that
     * modded blocks extending vanilla types are correctly classified.
     */
    public static BlockRole classify(Block block) {
        if (block == null || block == Blocks.air) return AIR;
        if (block instanceof ArmoredConveyor) return CONVEYOR;
        if (block instanceof StackConveyor) return NON_LOGISTICS; // plastanium/surge — out of V1 scope
        if (block instanceof Conveyor) return CONVEYOR;
        if (block instanceof Junction) return JUNCTION;
        if (block instanceof Sorter) return SORTER;
        if (block instanceof OverflowGate) return OVERFLOW;
        if (block instanceof Router) return ROUTER; // Router and Distributor
        if (block instanceof ItemBridge) return BRIDGE;
        if (block instanceof Unloader) return UNLOADER;
        return NON_LOGISTICS;
    }

    /** Returns true if this role is part of the item logistics network. */
    public boolean isLogistics() {
        return this != NON_LOGISTICS && this != AIR;
    }

    /** Returns true if this role is safe to reroute in V1. */
    public boolean isReroutable() {
        return this == CONVEYOR || this == JUNCTION || this == BRIDGE;
    }
}
