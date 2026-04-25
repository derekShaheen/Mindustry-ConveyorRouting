package conveyoroptimizer.graph;

import mindustry.game.Team;
import mindustry.type.Item;
import mindustry.world.Block;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents a single logistics block inside the selected area.
 * Edges (inputs/outputs) are populated by GraphBuilder after all nodes are created.
 */
public class LogisticsNode {
    /** Tile coordinates. */
    public final int x, y;

    /** The Mindustry block placed at this tile. */
    public final Block block;

    /** Rotation (0=east, 1=north, 2=west, 3=south). */
    public final int rotation;

    /** Classified role of this block. */
    public final BlockRole role;

    /** Filter item for sorters/inverted sorters/unloaders. Null for other blocks. */
    public final Item filterItem;

    /** Team that owns this building. */
    public final Team team;

    /** True if this node receives items from outside the selection. */
    public boolean isBoundaryInput;

    /** True if this node sends items to outside the selection. */
    public boolean isBoundaryOutput;

    /** Set of nodes that feed items INTO this node. */
    public final Set<LogisticsNode> inputs = new HashSet<LogisticsNode>();

    /** Set of nodes that this node feeds items TO. */
    public final Set<LogisticsNode> outputs = new HashSet<LogisticsNode>();

    /**
     * Whether this node is "pinned" — must not be moved or rerouted.
     * Nodes are pinned if they have complex behavior (routers, sorters,
     * overflow gates) or are part of ambiguous subgraphs.
     */
    public boolean pinned;

    public LogisticsNode(int x, int y, Block block, int rotation,
                         BlockRole role, Item filterItem, Team team) {
        this.x = x;
        this.y = y;
        this.block = block;
        this.rotation = rotation;
        this.role = role;
        this.filterItem = filterItem;
        this.team = team;

        // Pin non-reroutable nodes by default
        this.pinned = !role.isReroutable();
    }

    /** Pack coordinates into a single long key for map lookups. */
    public long key() {
        return packTile(x, y);
    }

    /** Pack two tile coordinates into a long. */
    public static long packTile(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }

    /** Unpack the x coordinate from a packed tile key. */
    public static int unpackX(long key) {
        return (int) (key >> 32);
    }

    /** Unpack the y coordinate from a packed tile key. */
    public static int unpackY(long key) {
        return (int) key;
    }

    /** Direction offsets: index matches rotation (0=E, 1=N, 2=W, 3=S). */
    public static final int[] DX = {1, 0, -1, 0};
    public static final int[] DY = {0, 1, 0, -1};

    /** Returns the rotation value that points from (ax,ay) toward (bx,by). */
    public static int directionTo(int ax, int ay, int bx, int by) {
        int dx = bx - ax;
        int dy = by - ay;
        if (dx > 0) return 0;
        if (dy > 0) return 1;
        if (dx < 0) return 2;
        return 3;
    }

    /** Returns the opposite rotation (0↔2, 1↔3). */
    public static int oppositeDir(int rotation) {
        return (rotation + 2) % 4;
    }

    @Override
    public int hashCode() {
        return x * 31 + y;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof LogisticsNode)) return false;
        LogisticsNode n = (LogisticsNode) o;
        return x == n.x && y == n.y;
    }

    @Override
    public String toString() {
        return role + "@(" + x + "," + y + ") rot=" + rotation;
    }
}
