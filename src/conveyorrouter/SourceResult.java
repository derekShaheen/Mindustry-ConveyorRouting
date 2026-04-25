package conveyorrouter;

import mindustry.world.*;

/**
 * Holds the result of a source-conveyor search:
 * the tile where the conveyor sits and the block type.
 */
public class SourceResult {
    public final Tile tile;
    public final Block block;

    public SourceResult(Tile tile, Block block) {
        this.tile = tile;
        this.block = block;
    }
}
