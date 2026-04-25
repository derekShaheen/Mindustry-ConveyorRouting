package conveyoroptimizer.optimizer;

import conveyoroptimizer.graph.LogisticsNode;
import mindustry.entities.units.BuildPlan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The complete proposed optimized layout.
 * Contains planned blocks, build/break plans, and validation status.
 */
public class ProposedLayout {

    /** Planned blocks keyed by packed tile coordinates. */
    public final Map<Long, PlannedBlock> blocks = new HashMap<Long, PlannedBlock>();

    /** Build plans to execute (new blocks to place). */
    public final List<BuildPlan> buildPlans = new ArrayList<BuildPlan>();

    /** Break plans to execute (old blocks to remove first). */
    public final List<BuildPlan> breakPlans = new ArrayList<BuildPlan>();

    /** Whether this layout passed validation. */
    public boolean valid = false;

    /** Reason for validation failure (null if valid). */
    public String failureReason;

    /** Selection bounds for reference. */
    public int minX, minY, maxX, maxY;

    public void addBlock(PlannedBlock pb) {
        blocks.put(LogisticsNode.packTile(pb.x, pb.y), pb);
    }

    public PlannedBlock getBlock(int x, int y) {
        return blocks.get(LogisticsNode.packTile(x, y));
    }

    /** Total number of blocks in the proposed layout. */
    public int size() {
        return blocks.size();
    }
}
