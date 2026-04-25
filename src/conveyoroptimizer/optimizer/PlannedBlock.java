package conveyoroptimizer.optimizer;

import mindustry.type.Item;
import mindustry.world.Block;

/**
 * Represents a single block placement in the proposed optimized layout.
 */
public class PlannedBlock {
    public final int x, y;
    public final int rotation;
    public final Block block;
    public final Item filterItem; // for sorters, null otherwise

    public PlannedBlock(int x, int y, int rotation, Block block) {
        this(x, y, rotation, block, null);
    }

    public PlannedBlock(int x, int y, int rotation, Block block, Item filterItem) {
        this.x = x;
        this.y = y;
        this.rotation = rotation;
        this.block = block;
        this.filterItem = filterItem;
    }

    @Override
    public String toString() {
        return block.name + "@(" + x + "," + y + ") rot=" + rotation;
    }
}
