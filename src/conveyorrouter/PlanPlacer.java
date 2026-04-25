package conveyorrouter;

import arc.struct.*;
import mindustry.*;
import mindustry.content.Blocks;
import mindustry.entities.units.*;
import mindustry.world.*;
import mindustry.world.blocks.distribution.*;

/**
 * Converts an ordered list of PathNodes into Mindustry BuildPlan objects
 * and queues them on the local player's unit.
 *
 * Conveyor rotation = direction from current node to next node.
 * Bridge endpoints get bridge block plans; intermediate spans are skipped.
 */
public class PlanPlacer {

    /**
     * Place build plans for the given path.
     *
     * @param path          ordered nodes from source to destination
     * @param conveyorBlock the conveyor type to place
     * @param bridgeBlock   the bridge type, or null to skip bridge segments
     * @return number of build plans queued
     */
    public static int placeConveyorPath(Seq<PathNode> path, Block conveyorBlock, Block bridgeBlock) {
        if (path == null || path.size < 2) return 0;

        int count = 0;

        for (int i = 0; i < path.size; i++) {
            PathNode node = path.get(i);

            // Skip the very first node (source conveyor already exists).
            if (i == 0) continue;

            // Skip intermediate bridge-span tiles.
            if (node.bridge) continue;

            // Determine rotation: direction toward next node.
            // For last node, use direction from previous node.
            int rotation;
            if (i < path.size - 1) {
                PathNode next = path.get(i + 1);
                rotation = directionTo(node.x, node.y, next.x, next.y);
            } else {
                PathNode prev = path.get(i - 1);
                rotation = directionTo(prev.x, prev.y, node.x, node.y);
            }

            // Check if this node is a bridge endpoint.
            boolean isBridgeStart = false;
            boolean isBridgeEnd = false;
            if (bridgeBlock != null) {
                if (i < path.size - 1 && path.get(i + 1).bridge) {
                    isBridgeStart = true;
                }
                if (i > 0 && path.get(i - 1).bridge) {
                    isBridgeEnd = true;
                }
            }

            // Don't place over existing buildings (except at destination or same-type conveyors).
            Tile tile = Vars.world.tile(node.x, node.y);
            if (tile != null && tile.block() != null && tile.block() != Blocks.air) {
                boolean isConveyor = tile.block() instanceof Conveyor;
                boolean isDest = (i == path.size - 1);
                if (!isConveyor && !isDest) continue;
                if (isConveyor && tile.block() == conveyorBlock
                    && tile.build != null && tile.build.rotation == rotation) {
                    continue; // already correct
                }
            }

            Block blockToPlace;
            if (isBridgeStart || isBridgeEnd) {
                blockToPlace = bridgeBlock;
                // Bridge rotation: face toward the landing node.
                if (isBridgeStart && i < path.size - 1) {
                    for (int j = i + 1; j < path.size; j++) {
                        if (!path.get(j).bridge) {
                            rotation = directionTo(node.x, node.y, path.get(j).x, path.get(j).y);
                            break;
                        }
                    }
                }
            } else {
                blockToPlace = conveyorBlock;
            }

            BuildPlan plan = new BuildPlan(node.x, node.y, rotation, blockToPlace);
            Vars.player.unit().addBuild(plan);
            count++;
        }

        return count;
    }

    /**
     * Mindustry rotation: 0=right/east, 1=up/north, 2=left/west, 3=down/south.
     */
    private static int directionTo(int ax, int ay, int bx, int by) {
        int ddx = bx - ax;
        int ddy = by - ay;
        if (ddx > 0) return 0;
        if (ddy > 0) return 1;
        if (ddx < 0) return 2;
        return 3;
    }
}
