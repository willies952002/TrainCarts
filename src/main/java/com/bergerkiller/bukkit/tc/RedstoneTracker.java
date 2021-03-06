package com.bergerkiller.bukkit.tc;

import com.bergerkiller.bukkit.common.collections.BlockSet;
import com.bergerkiller.bukkit.common.collections.CollectionBasics;
import com.bergerkiller.bukkit.common.utils.*;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.logging.Level;

/**
 * Keeps track of Redstone Power for signs, raising proper Sign redstone events in the process
 */
public class RedstoneTracker implements Listener {
    private final BlockSet ignoredSigns = new BlockSet();
    private BlockSet poweredBlocks = new BlockSet();

    /* ============= Handles raw block physics in a cached manner to reduce overhead ============ */
    private HashSet<Block> nextTickPhysicsBlocks = new HashSet<Block>();
    private boolean nextTickQueued = false;
    private final Runnable nextTickPhysicsHandler = new Runnable() {
        private final ArrayList<Block> pending = new ArrayList<Block>();

        @Override
        public void run() {
            CollectionBasics.setAll(this.pending, nextTickPhysicsBlocks);
            nextTickPhysicsBlocks.clear();
            nextTickQueued = false;

            for (final Block block : this.pending) {
                final Material type = block.getType();
                if (MaterialUtil.ISSIGN.get(type)) {
                    if (Util.isSupported(block)) {
                        // Check for potential redstone changes
                        updateRedstonePower(block);
                    } else {
                        // Remove from block power storage
                        poweredBlocks.remove(block);
                    }
                } else if (MaterialUtil.ISREDSTONETORCH.get(type)) {
                    // Send proper update events for all signs around this power source
                    for (BlockFace face : FaceUtil.RADIAL) {
                        final Block rel = block.getRelative(face);
                        if (MaterialUtil.ISSIGN.get(rel)) {
                            updateRedstonePower(rel);
                        }
                    }
                }
            }
        }
    };

    public RedstoneTracker() {
        initPowerLevels();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPhysics(BlockPhysicsEvent event) {
        nextTickPhysicsBlocks.add(event.getBlock());
        if (!nextTickQueued) {
            CommonUtil.nextTick(nextTickPhysicsHandler);
        }
    }

    /**
     * Initializes the power levels of all signs on a server.
     * Should be called only once, ever, as this method is quite slow.
     */
    public void initPowerLevels() {
        for (World world : WorldUtil.getWorlds()) {
            try {
                for (BlockState state : WorldUtil.getBlockStates(world)) {
                    if (state instanceof Sign) {
                        Block block = state.getBlock();
                        LogicUtil.addOrRemove(poweredBlocks, block, PowerState.isSignPowered(block));
                    }
                }
            } catch (Throwable t) {
                TrainCarts.plugin.getLogger().log(Level.SEVERE, "Error while initializing sign power states in world " + world.getName(), t);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        // Set the initial power state of all signs within this Chunk
        try {
            for (BlockState state : WorldUtil.getBlockStates(event.getChunk())) {
                if (state instanceof Sign) {
                    Block block = state.getBlock();
                    LogicUtil.addOrRemove(poweredBlocks, block, PowerState.isSignPowered(block));
                }
            }
        } catch (Throwable t) {
            TrainCarts.plugin.getLogger().log(Level.SEVERE, "Error while initializing sign power states in chunk " + event.getChunk().getX() + "/" + event.getChunk().getZ(), t);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockRedstoneChange(BlockRedstoneEvent event) {
        if (TrainCarts.isWorldDisabled(event)) {
            return;
        }
        Material type = event.getBlock().getType();
        if (BlockUtil.isType(type, Material.LEVER)) {
            Block up = event.getBlock().getRelative(BlockFace.UP);
            Block down = event.getBlock().getRelative(BlockFace.DOWN);
            if (MaterialUtil.ISSIGN.get(up)) {
                updateRedstonePowerVerify(up, event.getNewCurrent() > 0);
            }
            if (MaterialUtil.ISSIGN.get(down)) {
                updateRedstonePowerVerify(down, event.getNewCurrent() > 0);
            }
            ignoreOutputLever(event.getBlock());
        } else if (MaterialUtil.ISSIGN.get(type)) {
            updateRedstonePowerVerify(event.getBlock(), event.getNewCurrent() > 0);
        }
    }

    /**
     * Ignores signs of current-tick redstone changes caused by the lever
     *
     * @param lever to ignore
     */
    public void ignoreOutputLever(Block lever) {
        // Ignore signs that are attached to the block the lever is attached to
        Block att = BlockUtil.getAttachedBlock(lever);
        for (BlockFace face : FaceUtil.ATTACHEDFACES) {
            Block signblock = att.getRelative(face);
            if (MaterialUtil.ISSIGN.get(signblock) && BlockUtil.getAttachedFace(signblock) == face.getOppositeFace()) {
                if (ignoredSigns.isEmpty()) {
                    // clear this the next tick
                    CommonUtil.nextTick(new Runnable() {
                        public void run() {
                            ignoredSigns.clear();
                        }
                    });
                }
                ignoredSigns.add(signblock);
            }
        }
    }

    public void updateRedstonePower(final Block signblock) {
        // Update power level
        setRedstonePower(signblock, PowerState.isSignPowered(signblock));
    }

    public void updateRedstonePowerVerify(final Block signblock, boolean isPowered) {
        // Verify that the power state is correct
        if (PowerState.isSignPowered(signblock) != isPowered) {
            return;
        }

        // Update power level
        setRedstonePower(signblock, isPowered);
    }

    public void setRedstonePower(final Block signblock, boolean newPowerState) {
        // Do not proceed if the sign disallows on/off changes
        if (ignoredSigns.remove(signblock)) {
            return;
        }

        // Is the event allowed?
        SignActionEvent info = new SignActionEvent(signblock);
        SignActionType type = info.getHeader().getRedstoneAction(newPowerState);
        if (type == SignActionType.NONE) {
            LogicUtil.addOrRemove(poweredBlocks, info.getBlock(), newPowerState);
            return;
        }

        // Change in redstone power?
        if (!LogicUtil.addOrRemove(poweredBlocks, info.getBlock(), newPowerState)) {

            // No change in redstone power, but a redstone change nevertheless
            SignAction.executeAll(info, SignActionType.REDSTONE_CHANGE);
            return;
        }

        // Fire the event, with a REDSTONE_CHANGE afterwards
        SignAction.executeAll(info, type);
        SignAction.executeAll(info, SignActionType.REDSTONE_CHANGE);
    }
}
