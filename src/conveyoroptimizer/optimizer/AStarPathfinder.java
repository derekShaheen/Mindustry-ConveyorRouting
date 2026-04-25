package conveyoroptimizer.optimizer;

import conveyoroptimizer.graph.BlockRole;
import conveyoroptimizer.graph.LogisticsNode;
import mindustry.Vars;
import mindustry.content.Blocks;
import mindustry.world.Block;
import mindustry.world.Tile;
import mindustry.world.blocks.distribution.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * A* pathfinder over the tile grid for conveyor routing.
 * Finds the lowest-cost Manhattan path between two tile positions
 * while respecting obstacles, existing blocks, and conveyor direction rules.
 */
public class AStarPathfinder {

    /** Maximum node expansions before giving up. */
    private static final int MAX_EXPANSIONS = 20000;

    /** Cost constants (from distribution-blocks reference). */
    private static final float COST_EMPTY = 1.0f;
    private static final float COST_ALIGNED_CONVEYOR = 0.2f;
    private static final float COST_MISALIGNED_CONVEYOR = 5.0f;
    private static final float COST_JUNCTION = 0.5f;
    private static final float COST_TURN = 0.4f;
    private static final float COST_BRIDGE_BASE = 4.0f;
    private static final float COST_BRIDGE_PER_SPAN = 0.5f;

    /** Tiles that are already claimed by a previously planned path. */
    private final Set<Long> claimedTiles;

    /** The selection bounds. */
    private final int minX, minY, maxX, maxY;

    /** Tiles that are pinned (occupied by non-reroutable blocks). */
    private final Set<Long> pinnedTiles;

    public AStarPathfinder(int minX, int minY, int maxX, int maxY,
                           Set<Long> pinnedTiles) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.pinnedTiles = pinnedTiles;
        this.claimedTiles = new HashSet<Long>();
    }

    /** Mark a tile as claimed by a previous path. */
    public void claimTile(long key) {
        claimedTiles.add(key);
    }

    /** Mark a tile as claimed with direction for alignment checking. */
    public void claimTileWithDir(long key, int rotation) {
        claimedTiles.add(key);
        claimedDirections.put(key, rotation);
    }

    /** Directions of claimed tiles (for alignment checking). */
    private final Map<Long, Integer> claimedDirections = new HashMap<Long, Integer>();

    /**
     * Find a path from (sx, sy) to (ex, ey).
     *
     * @param sx start tile X
     * @param sy start tile Y
     * @param ex end tile X
     * @param ey end tile Y
     * @param conveyorType the conveyor block type to use
     * @return list of (x, y, rotation) steps, or null if no path found
     */
    public List<int[]> findPath(int sx, int sy, int ex, int ey, Block conveyorType) {
        Map<Long, Float> bestCost = new HashMap<Long, Float>();
        Map<Long, Long> cameFrom = new HashMap<Long, Long>();
        Map<Long, Integer> cameDir = new HashMap<Long, Integer>(); // direction we arrived with

        PriorityQueue<long[]> open = new PriorityQueue<long[]>(256, new Comparator<long[]>() {
            public int compare(long[] a, long[] b) {
                return Float.compare(Float.intBitsToFloat((int) a[1]),
                                     Float.intBitsToFloat((int) b[1]));
            }
        });

        long startKey = LogisticsNode.packTile(sx, sy);
        long endKey = LogisticsNode.packTile(ex, ey);

        bestCost.put(startKey, 0.0f);
        float h = heuristic(sx, sy, ex, ey);
        open.add(new long[]{startKey, Float.floatToIntBits(h)});

        int expansions = 0;

        while (!open.isEmpty() && expansions < MAX_EXPANSIONS) {
            long[] current = open.poll();
            long curKey = current[0];
            int cx = LogisticsNode.unpackX(curKey);
            int cy = LogisticsNode.unpackY(curKey);
            float curCost = bestCost.get(curKey);
            expansions++;

            if (curKey == endKey) {
                return reconstructPath(cameFrom, cameDir, startKey, endKey);
            }

            // Try all 4 cardinal directions
            for (int d = 0; d < 4; d++) {
                int nx = cx + LogisticsNode.DX[d];
                int ny = cy + LogisticsNode.DY[d];
                long nKey = LogisticsNode.packTile(nx, ny);

                // Bounds check — path must stay within selection
                // (except start/end which are at the boundary)
                if (!isWithinBounds(nx, ny) && nKey != endKey) continue;

                float moveCost = getMoveCost(nx, ny, d, conveyorType);
                if (moveCost < 0) continue; // impassable

                // Turn penalty
                Integer prevDir = cameDir.get(curKey);
                if (prevDir != null && prevDir != d) {
                    moveCost += COST_TURN;
                }

                float tentative = curCost + moveCost;
                Float existing = bestCost.get(nKey);
                if (existing == null || tentative < existing) {
                    bestCost.put(nKey, tentative);
                    cameFrom.put(nKey, curKey);
                    cameDir.put(nKey, d);
                    float f = tentative + heuristic(nx, ny, ex, ey);
                    open.add(new long[]{nKey, Float.floatToIntBits(f)});
                }
            }
        }

        return null; // no path found
    }

    /** Manhattan distance heuristic. */
    private float heuristic(int x1, int y1, int x2, int y2) {
        return Math.abs(x2 - x1) + Math.abs(y2 - y1);
    }

    /** Check if position is within the selection bounds. */
    private boolean isWithinBounds(int x, int y) {
        return x >= minX && x <= maxX && y >= minY && y <= maxY;
    }

    /**
     * Get the movement cost to enter tile (x, y) from direction d.
     * Returns -1 if impassable.
     */
    private float getMoveCost(int x, int y, int direction, Block conveyorType) {
        long key = LogisticsNode.packTile(x, y);

        // Pinned tiles are impassable (unless already claimed by same path — N/A here)
        if (pinnedTiles.contains(key)) return -1;

        // Check if already claimed by another path
        if (claimedTiles.contains(key)) {
            // Could share tile if it's a junction (crossing)
            Integer claimedDir = claimedDirections.get(key);
            if (claimedDir != null) {
                // Perpendicular crossing → can use a junction
                int diff = Math.abs(direction - claimedDir);
                if (diff == 1 || diff == 3) {
                    return COST_JUNCTION; // will become a junction
                }
            }
            return -1; // parallel conflict — impassable
        }

        Tile tile = Vars.world.tile(x, y);
        if (tile == null) return -1;

        Block block = tile.block();
        if (block == null || block == Blocks.air) return COST_EMPTY;

        // Check floor
        if (tile.floor() != null && tile.floor().isLiquid && !tile.floor().placeableOn) {
            return -1;
        }

        BlockRole role = BlockRole.classify(block);

        if (role == BlockRole.CONVEYOR) {
            int rot = (tile.build != null) ? tile.build.rotation : 0;
            if (rot == direction) return COST_ALIGNED_CONVEYOR;
            return COST_MISALIGNED_CONVEYOR;
        }

        if (role == BlockRole.JUNCTION) return COST_JUNCTION;

        if (role == BlockRole.AIR) return COST_EMPTY;

        // Non-logistics blocks and multi-tile blocks are impassable
        if (block.size > 1) return -1;
        if (!role.isLogistics()) return -1;

        return COST_EMPTY;
    }

    /** Reconstruct the path from A* results. */
    private List<int[]> reconstructPath(Map<Long, Long> cameFrom,
                                         Map<Long, Integer> cameDir,
                                         long startKey, long endKey) {
        List<int[]> path = new ArrayList<int[]>();
        long current = endKey;

        while (current != startKey) {
            int x = LogisticsNode.unpackX(current);
            int y = LogisticsNode.unpackY(current);
            Integer dir = cameDir.get(current);
            int rotation = (dir != null) ? dir : 0;
            path.add(new int[]{x, y, rotation});
            Long prev = cameFrom.get(current);
            if (prev == null) break;
            current = prev;
        }

        // Add start
        int sx = LogisticsNode.unpackX(startKey);
        int sy = LogisticsNode.unpackY(startKey);
        Integer sDir = cameDir.get(startKey);
        path.add(new int[]{sx, sy, (sDir != null) ? sDir : 0});

        Collections.reverse(path);
        return path;
    }
}
