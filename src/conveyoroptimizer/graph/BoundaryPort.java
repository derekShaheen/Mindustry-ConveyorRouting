package conveyoroptimizer.graph;

import mindustry.type.Item;
import mindustry.world.Block;

/**
 * Represents a point where items cross the selection boundary.
 * An input port means items flow INTO the selection from outside.
 * An output port means items flow OUT of the selection to the outside.
 */
public class BoundaryPort {
    /** Tile coordinates of the internal (inside-selection) block. */
    public final int x, y;

    /** Cardinal direction of the external connection (rotation toward outside). */
    public final int direction;

    /** True = items enter the selection here. False = items leave. */
    public final boolean isInput;

    /** Block type on the external side of the boundary. */
    public final Block externalBlock;

    /** Block type on the internal side of the boundary. */
    public final Block internalBlock;

    /** Filter item if the boundary block is a sorter. Null otherwise. */
    public final Item filterItem;

    /** The conveyor tier observed at this boundary point. */
    public final Block conveyorTier;

    public BoundaryPort(int x, int y, int direction, boolean isInput,
                        Block externalBlock, Block internalBlock,
                        Item filterItem, Block conveyorTier) {
        this.x = x;
        this.y = y;
        this.direction = direction;
        this.isInput = isInput;
        this.externalBlock = externalBlock;
        this.internalBlock = internalBlock;
        this.filterItem = filterItem;
        this.conveyorTier = conveyorTier;
    }

    /** Unique identifier combining position and direction. */
    public long key() {
        return LogisticsNode.packTile(x, y) ^ ((long) direction << 48) ^ (isInput ? 1L << 50 : 0L);
    }

    @Override
    public int hashCode() {
        return x * 31 * 31 + y * 31 + direction + (isInput ? 7 : 0);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BoundaryPort)) return false;
        BoundaryPort bp = (BoundaryPort) o;
        return x == bp.x && y == bp.y && direction == bp.direction && isInput == bp.isInput;
    }

    @Override
    public String toString() {
        return (isInput ? "IN" : "OUT") + "@(" + x + "," + y + ") dir=" + direction;
    }
}
