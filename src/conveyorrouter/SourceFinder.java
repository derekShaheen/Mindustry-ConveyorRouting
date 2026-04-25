package conveyorrouter;

import arc.struct.*;
import java.util.HashSet;
import mindustry.*;
import mindustry.content.Blocks;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;

/**
 * BFS outward from the destination tile to locate the nearest conveyor that
 * has at least one open cardinal side (i.e. not fully surrounded by connected
 * logistics blocks).
 *
 * "Nearest" is measured in BFS ring distance. The first valid hit is returned.
 */
public class SourceFinder {

    // Cardinal offsets: E, N, W, S
    private static final int[] DX = {1, 0, -1, 0};
    private static final int[] DY = {0, 1, 0, -1};

    /**
     * Search outward from {@code origin} up to {@code maxRadius} tiles.
     * Returns the first qualifying conveyor tile, or null.
     */
    public static SourceResult findNearest(Tile origin, int maxRadius) {
        if (origin == null) return null;

        HashSet<Long> visited = new HashSet<>();
        Queue<int[]> queue = new Queue<>();

        int ox = origin.x, oy = origin.y;
        queue.addLast(new int[]{ox, oy});
        visited.add(key(ox, oy));

        while (!queue.isEmpty()) {
            int[] pos = queue.removeFirst();
            int cx = pos[0], cy = pos[1];

            // Distance check (Chebyshev ring).
            if (Math.abs(cx - ox) > maxRadius || Math.abs(cy - oy) > maxRadius) continue;

            Tile t = Vars.world.tile(cx, cy);
            if (t == null) continue;

            // Check if this tile has a conveyor with an open side.
            if (isUnconnectedConveyor(t)) {
                return new SourceResult(t, t.block());
            }

            // Expand cardinal neighbours.
            for (int d = 0; d < 4; d++) {
                int nx = cx + DX[d], ny = cy + DY[d];
                long k = key(nx, ny);
                if (!visited.contains(k)) {
                    visited.add(k);
                    queue.addLast(new int[]{nx, ny});
                }
            }
        }

        return null;
    }

    /**
     * A conveyor is "unconnected" if:
     *  - The tile contains a Conveyor block (vanilla).
     *  - At least one cardinal neighbour is empty or is not a logistics block
     *    that fully connects to this conveyor.
     */
    private static boolean isUnconnectedConveyor(Tile tile) {
        Block block = tile.block();
        if (block == null) return false;
        if (!(block instanceof Conveyor)) return false;

        for (int d = 0; d < 4; d++) {
            Tile adj = Vars.world.tile(tile.x + DX[d], tile.y + DY[d]);
            if (adj == null) continue;

            Block ab = adj.block();
            // Adjacent tile is empty — this side is open.
            if (ab == null || ab == Blocks.air) {
                return true;
            }
            // Adjacent block is NOT a logistics block — side is open.
            if (!(ab instanceof Conveyor) && !(ab instanceof Junction) && !(ab instanceof Router)
                && !(ab instanceof ItemBridge) && !(ab instanceof Sorter) && !(ab instanceof OverflowGate)) {
                return true;
            }
        }
        return false;
    }

    /** Pack two ints into a long for hashing. */
    private static long key(int x, int y) {
        return ((long) x << 32) | (y & 0xFFFFFFFFL);
    }
}
