package conveyoroptimizer;

import arc.*;
import arc.input.KeyCode;
import arc.util.*;
import conveyoroptimizer.graph.*;
import conveyoroptimizer.optimizer.*;
import conveyoroptimizer.ui.*;
import mindustry.*;
import mindustry.entities.units.BuildPlan;
import mindustry.game.EventType;
import mindustry.mod.*;
import mindustry.world.Block;
import mindustry.world.Tile;

import java.util.ArrayList;
import java.util.List;

/**
 * Conveyor Optimizer Mod — entry point.
 *
 * Hotkey F6: enter selection mode, then confirm to optimize.
 * Enter: apply preview. Escape: cancel. Ctrl+Z: undo last apply.
 *
 * Usage flow:
 * 1. Press F6 → "Click and drag to select area"
 * 2. Drag rectangle over conveyor area
 * 3. Press F6 again → runs analysis + optimization
 * 4. Preview shows (green=new, red=removed)
 * 5. Press Enter to apply, Escape to cancel
 * 6. Ctrl+Z to undo
 */
public class ConveyorOptimizerMod extends Mod {

    private SelectionHandler selection;
    private PreviewRenderer preview;

    /** The current proposed layout (while previewing). */
    private ProposedLayout currentProposal;

    /** The original graph (while previewing / for undo). */
    private LogisticsGraph currentOriginalGraph;

    /** Undo buffer: the build plans that restore the previous layout. */
    private List<BuildPlan> undoBreakPlans;
    private List<BuildPlan> undoBuildPlans;

    public ConveyorOptimizerMod() {
        Log.info("[ConvOpt] Conveyor Optimizer mod loaded.");
    }

    @Override
    public void init() {
        selection = new SelectionHandler();
        preview = new PreviewRenderer();

        // Per-frame update
        Events.run(EventType.Trigger.update, new Runnable() {
            public void run() {
                updateLoop();
            }
        });

        // Drawing
        Events.run(EventType.Trigger.draw, new Runnable() {
            public void run() {
                selection.draw();
                preview.draw();
            }
        });

        Log.info("[ConvOpt] Initialized. Press F6 to start selection.");
    }

    /** Main update loop, called every frame. */
    private void updateLoop() {
        if (Vars.player == null) return;
        if (Vars.net.client()) return; // disable on multiplayer clients

        // Handle undo (Ctrl+Z)
        if (Core.input.keyTap(KeyCode.z) && Core.input.keyDown(KeyCode.controlLeft) &&
            !Core.scene.hasField() && !Core.scene.hasDialog()) {
            performUndo();
            return;
        }

        // Handle Enter to apply preview
        if (selection.getState() == SelectionHandler.State.PREVIEWING &&
            Core.input.keyTap(KeyCode.enter) &&
            !Core.scene.hasField() && !Core.scene.hasDialog()) {
            applyProposal();
            return;
        }

        // Selection handler returns true when the user confirms a selection
        boolean triggerOptimize = selection.update();

        if (triggerOptimize) {
            runOptimization();
        }
    }

    /** Run the full optimization pipeline. */
    private void runOptimization() {
        int x1 = selection.getMinX();
        int y1 = selection.getMinY();
        int x2 = selection.getMaxX();
        int y2 = selection.getMaxY();

        Log.info("[ConvOpt] Running optimization on area (" +
                 x1 + "," + y1 + ") to (" + x2 + "," + y2 + ")");

        // Phase 1-4: Build the logistics graph
        LogisticsGraph graph = GraphBuilder.build(x1, y1, x2, y2);

        if (graph.size() == 0) {
            Vars.ui.hudfrag.showToast("[gray]No logistics blocks in selection");
            selection.reset();
            return;
        }

        if (graph.inputPorts.isEmpty() && graph.outputPorts.isEmpty()) {
            Vars.ui.hudfrag.showToast("[gray]No boundary connections found. Selection may be self-contained.");
            // Could still optimize internal layout, but for V1 we need boundaries
        }

        Log.info("[ConvOpt] Graph: " + graph.size() + " nodes, " +
                 graph.inputPorts.size() + " inputs, " +
                 graph.outputPorts.size() + " outputs, " +
                 graph.flows.size() + " flows, " +
                 graph.reroutableCount() + " reroutable");

        // Phase 5-7: Plan optimized paths
        PathPlanner planner = new PathPlanner(graph);
        ProposedLayout proposal = planner.plan();

        if (!proposal.valid) {
            Vars.ui.hudfrag.showToast("[scarlet]Optimization failed: " + proposal.failureReason);
            selection.reset();
            return;
        }

        // Phase 8: Validate
        List<String> errors = GraphValidator.validate(graph, proposal);

        if (!errors.isEmpty()) {
            Log.warn("[ConvOpt] Validation errors:");
            for (String err : errors) {
                Log.warn("  - " + err);
            }
            Vars.ui.hudfrag.showToast("[scarlet]Validation failed: " + errors.size() +
                                       " issue(s). Check logs for details.");
            proposal.valid = false;
            selection.reset();
            return;
        }

        // Generate build plans
        generateBuildPlans(graph, proposal);

        Log.info("[ConvOpt] Proposal: " + proposal.size() + " blocks, " +
                 proposal.breakPlans.size() + " to break, " +
                 proposal.buildPlans.size() + " to build");

        // Show preview
        currentProposal = proposal;
        currentOriginalGraph = graph;
        preview.show(proposal, graph);
        selection.setState(SelectionHandler.State.PREVIEWING);
        Vars.ui.hudfrag.showToast("[accent]Preview ready.[white] Enter=apply, Escape=cancel");
    }

    /**
     * Generate Mindustry BuildPlan lists for breaking old blocks and placing new ones.
     */
    private void generateBuildPlans(LogisticsGraph graph, ProposedLayout proposal) {
        proposal.breakPlans.clear();
        proposal.buildPlans.clear();

        // Break plans: remove old logistics blocks that are not in the proposal
        // or that have different block/rotation
        for (LogisticsNode node : graph.nodes.values()) {
            long key = node.key();
            PlannedBlock pb = proposal.blocks.get(key);

            boolean needsBreak = false;
            if (pb == null) {
                // Block removed entirely
                needsBreak = true;
            } else if (pb.block != node.block || pb.rotation != node.rotation) {
                // Block changed
                needsBreak = true;
            }

            if (needsBreak) {
                BuildPlan breakPlan = new BuildPlan(node.x, node.y);
                proposal.breakPlans.add(breakPlan);
            }
        }

        // Build plans: place proposed blocks that are new or changed
        for (PlannedBlock pb : proposal.blocks.values()) {
            long key = LogisticsNode.packTile(pb.x, pb.y);
            LogisticsNode orig = graph.nodes.get(key);

            boolean needsBuild = false;
            if (orig == null) {
                needsBuild = true;
            } else if (orig.block != pb.block || orig.rotation != pb.rotation) {
                needsBuild = true;
            }

            if (needsBuild) {
                BuildPlan buildPlan = new BuildPlan(pb.x, pb.y, pb.rotation, pb.block);
                // Set config for sorters/unloaders
                if (pb.filterItem != null) {
                    buildPlan.config = pb.filterItem;
                }
                proposal.buildPlans.add(buildPlan);
            }
        }
    }

    /** Apply the current proposal by queuing build plans on the player. */
    private void applyProposal() {
        if (currentProposal == null || currentOriginalGraph == null) return;

        // Save undo state
        saveUndoState(currentOriginalGraph, currentProposal);

        // Queue break plans first, then build plans
        for (BuildPlan plan : currentProposal.breakPlans) {
            Vars.player.unit().addBuild(plan);
        }
        for (BuildPlan plan : currentProposal.buildPlans) {
            Vars.player.unit().addBuild(plan);
        }

        int changes = currentProposal.breakPlans.size() + currentProposal.buildPlans.size();
        Vars.ui.hudfrag.showToast("[green]Applied! " + changes + " changes queued. Ctrl+Z to undo.");

        preview.hide();
        selection.reset();
        currentProposal = null;
        currentOriginalGraph = null;
    }

    /**
     * Save the current state for undo.
     * The undo will break all proposed blocks and rebuild the original ones.
     */
    private void saveUndoState(LogisticsGraph original, ProposedLayout proposal) {
        undoBreakPlans = new ArrayList<BuildPlan>();
        undoBuildPlans = new ArrayList<BuildPlan>();

        // To undo: break all proposed blocks that were changed
        for (BuildPlan plan : proposal.buildPlans) {
            undoBreakPlans.add(new BuildPlan(plan.x, plan.y));
        }

        // To undo: rebuild all original blocks that were broken
        for (LogisticsNode node : original.nodes.values()) {
            long key = node.key();
            PlannedBlock pb = proposal.blocks.get(key);
            boolean wasChanged = (pb == null) ||
                                  (pb.block != node.block) ||
                                  (pb.rotation != node.rotation);
            if (wasChanged) {
                BuildPlan restorePlan = new BuildPlan(
                        node.x, node.y, node.rotation, node.block
                );
                if (node.filterItem != null) {
                    restorePlan.config = node.filterItem;
                }
                undoBuildPlans.add(restorePlan);
            }
        }
    }

    /** Undo the last optimization apply. */
    private void performUndo() {
        if (undoBreakPlans == null || undoBuildPlans == null) {
            Vars.ui.hudfrag.showToast("[gray]Nothing to undo");
            return;
        }

        for (BuildPlan plan : undoBreakPlans) {
            Vars.player.unit().addBuild(plan);
        }
        for (BuildPlan plan : undoBuildPlans) {
            Vars.player.unit().addBuild(plan);
        }

        int changes = undoBreakPlans.size() + undoBuildPlans.size();
        Vars.ui.hudfrag.showToast("[accent]Undone! " + changes + " changes queued.");

        undoBreakPlans = null;
        undoBuildPlans = null;
    }
}
