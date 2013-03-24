package com.bergerkiller.bukkit.tc.blocktracker;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import org.bukkit.World;
import org.bukkit.block.Block;

import com.bergerkiller.bukkit.common.ToggledState;
import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.tc.Util;
import com.bergerkiller.bukkit.tc.controller.MinecartGroup;
import com.bergerkiller.bukkit.tc.controller.MinecartMember;
import com.bergerkiller.bukkit.tc.detector.DetectorRegion;
import com.bergerkiller.bukkit.tc.events.SignActionEvent;
import com.bergerkiller.bukkit.tc.signactions.SignAction;
import com.bergerkiller.bukkit.tc.signactions.SignActionType;

/**
 * Keeps track of the active rails, signs and detector regions below a
 * MinecartGroup
 */
public class BlockTrackerGroup extends BlockTracker {
	private static final Set<Block> groupSignBuffer = new HashSet<Block>();
	private final MinecartGroup owner;
	private final Map<IntVector3, MinecartMember<?>> blockSpace = new LinkedHashMap<IntVector3, MinecartMember<?>>();
	private final ToggledState needsPositionUpdate = new ToggledState(true);

	public BlockTrackerGroup(MinecartGroup owner) {
		this.owner = owner;
	}

	/**
	 * Gets the owner of this Block Tracker
	 * 
	 * @return the Owner
	 */
	public MinecartGroup getOwner() {
		return owner;
	}

	@Override
	protected void onSignChange(Block signblock, boolean active) {
		SignActionEvent event = new SignActionEvent(signblock, owner);
		event.setAction(active ? SignActionType.GROUP_ENTER : SignActionType.GROUP_LEAVE);
		SignAction.executeAll(event);
	}

	/**
	 * Gets the Minecart Member part of this Group that is traveling on the
	 * rails block specified
	 * 
	 * @param railsBlock
	 *            to get the Minecart Member for
	 * @return the Minecart Member, or null if not found
	 */
	public MinecartMember<?> getMemberFromRails(Block railsBlock) {
		return getMemberFromRails(new IntVector3(railsBlock));
	}

	/**
	 * Gets the Minecart Member part of this Group that is traveling on the
	 * rails block specified
	 * 
	 * @param railsBlockPosition
	 *            to get the Minecart Member for
	 * @return the Minecart Member, or null if not found
	 */
	public MinecartMember<?> getMemberFromRails(IntVector3 railsBlockPosition) {
		return blockSpace.get(railsBlockPosition);
	}

	@Override
	public void clear() {
		for (MinecartMember<?> member : owner) {
			member.getBlockTracker().clear();
		}
		super.clear();
		detectorRegions.clear();
		blockSpace.clear();
	}

	@Override
	public boolean isOnRails(Block railsBlock) {
		return blockSpace.containsKey(new IntVector3(railsBlock));
	}

	/**
	 * Tells that this Block Tracker needs to be updated at some point
	 */
	public void updatePosition() {
		needsPositionUpdate.set();
	}

	@Override
	public boolean removeSign(Block signBlock) {
		if (super.removeSign(signBlock)) {
			for (MinecartMember<?> member : owner) {
				member.getBlockTracker().removeSign(signBlock);
			}
			return true;
		} else {
			return false;
		}
	}

	/**
	 * Refreshes the block space and active signs if required
	 */
	public void refresh() {
		// No need to update anything for empty trains
		if (owner.isEmpty()) {
			clear();
			return;
		}

		// Do all active rails, signs and detector regions have to be refreshed?
		if (needsPositionUpdate.clear()) {

			// Update member block space
			blockSpace.clear();
			if (owner.size() == 1) {
				MinecartMember<?> member = owner.head();
				blockSpace.put(member.getBlockPos(), member);
			} else {
				for (int i = 0; i < owner.size() - 1; i++) {
					MinecartMember<?> member = owner.get(i);
					IntVector3 from = member.getBlockPos();
					IntVector3 to = owner.get(i + 1).getBlockPos();
					// Put member at various blocks
					blockSpace.put(from, member);
					if (to.x > from.x + 1) {
						blockSpace.put(from.add(1, 0, 0), member);
					} else if (to.x + 1 < from.x) {
						blockSpace.put(from.add(-1, 0, 0), member);
					}
					if (to.y > from.y + 1) {
						blockSpace.put(from.add(0, 1, 0), member);
					} else if (to.y + 1 < from.y) {
						blockSpace.put(from.add(0, -1, 0), member);
					}
					if (to.z > from.z + 1) {
						blockSpace.put(from.add(0, 0, 1), member);
					} else if (to.z + 1 < from.z) {
						blockSpace.put(from.add(0, 0, -1), member);
					}
				}
				blockSpace.put(owner.tail().getBlockPos(), owner.tail());
			}

			// First clear the live active sign buffer of all members
			for (MinecartMember<?> member : owner) {
				member.getBlockTracker().liveActiveSigns.clear();
			}

			// Add all active signs to the block tracker of all members
			World world = owner.getWorld();
			for (Entry<IntVector3, MinecartMember<?>> entry : blockSpace.entrySet()) {
				Block block = entry.getKey().toBlock(world);
				if (Util.ISTCRAIL.get(block)) {
					Util.addSignsFromRails(entry.getValue().getBlockTracker().liveActiveSigns, block);
				}
			}

			// Perform update events of sign changes
			groupSignBuffer.clear();
			for (MinecartMember<?> member : owner) {
				BlockTrackerMember tracker = member.getBlockTracker();
				groupSignBuffer.addAll(tracker.liveActiveSigns);
				tracker.updateActiveSigns(tracker.liveActiveSigns);
			}
			// Update the active signs for this Group
			updateActiveSigns(groupSignBuffer);

			// Update detector regions
			detectorRegions.clear();
			for (MinecartMember<?> member : owner) {
				BlockTrackerMember tracker = member.getBlockTracker();
				tracker.detectorRegions.clear();
				tracker.detectorRegions.addAll(DetectorRegion.handleMove(member, member.getLastBlock(), member.getBlock()));
				detectorRegions.addAll(tracker.detectorRegions);
			}
		}

		// Perform routine update events
		if (needsUpdate.clear()) {
			for (Block signBlock : getActiveSigns()) {
				SignAction.executeAll(new SignActionEvent(signBlock, owner), SignActionType.GROUP_UPDATE);
			}
			for (DetectorRegion region : getActiveDetectorRegions()) {
				region.update(owner);
			}
			// Member updates
			for (MinecartMember<?> member : owner) {
				BlockTrackerMember tracker = member.getBlockTracker();
				if (tracker.needsUpdate.clear()) {
					for (Block signBlock : tracker.getActiveSigns()) {
						SignAction.executeAll(new SignActionEvent(signBlock, tracker.getOwner()), SignActionType.MEMBER_UPDATE);
					}
					for (DetectorRegion region : tracker.getActiveDetectorRegions()) {
						region.update(tracker.getOwner());
					}
				}
			}
		}
	}
}