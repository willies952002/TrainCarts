package com.bergerkiller.bukkit.tc.controller;

import com.bergerkiller.bukkit.common.bases.IntVector3;
import com.bergerkiller.bukkit.common.collections.ClassMap;
import com.bergerkiller.bukkit.common.controller.DefaultEntityController;
import com.bergerkiller.bukkit.common.controller.DefaultEntityNetworkController;
import com.bergerkiller.bukkit.common.controller.EntityController;
import com.bergerkiller.bukkit.common.controller.EntityNetworkController;
import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.entity.CommonEntity;
import com.bergerkiller.bukkit.common.entity.CommonEntityType;
import com.bergerkiller.bukkit.common.entity.type.*;
import com.bergerkiller.bukkit.common.utils.*;
import com.bergerkiller.bukkit.tc.TrainCarts;
import com.bergerkiller.bukkit.tc.controller.type.*;
import com.bergerkiller.bukkit.tc.events.MemberSpawnEvent;
import com.bergerkiller.mountiplex.conversion.annotations.ConverterMethod;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public abstract class MinecartMemberStore {
    private static ClassMap<Class<?>> controllers = new ClassMap<>();

    static {
        controllers.put(CommonMinecartRideable.class, MinecartMemberRideable.class);
        controllers.put(CommonMinecartFurnace.class, MinecartMemberFurnace.class);
        controllers.put(CommonMinecartChest.class, MinecartMemberChest.class);
        controllers.put(CommonMinecartHopper.class, MinecartMemberHopper.class);
        controllers.put(CommonMinecartTNT.class, MinecartMemberTNT.class);
        controllers.put(CommonMinecartMobSpawner.class, MinecartMemberMobSpawner.class);
        controllers.put(CommonMinecartCommandBlock.class, MinecartMemberCommandBlock.class);
    }

    /**
     * Converts all Minecarts on all enabled worlds into Minecart Members
     */
    public static void convertAll() {
        List<Minecart> minecarts = new ArrayList<>();
        for (org.bukkit.World world : WorldUtil.getWorlds()) {
            if (TrainCarts.isWorldDisabled(world)) {
                continue;
            }
            for (org.bukkit.entity.Entity entity : WorldUtil.getEntities(world)) {
                if (canConvert(entity)) {
                    minecarts.add((Minecart) entity);
                }
            }
        }
        // Convert
        for (Minecart minecart : minecarts) {
            convert(minecart);
        }
        minecarts.clear();
    }

    /**
     * Checks if a given minecart can be converted to a minecart member<br>
     * - Returns false if the minecart null or already a minecart member<br>
     * - Returns true if the class equals the Entity Minecart<br>
     * - Returns false if the class is another extended version of the Entity Minecart<br>
     * - Returns true if the class name equals the minecart member name (a forgotten minecart)<br>
     * - Returns false if the world the entity is in is not enabled in TrainCarts
     *
     * @param minecart to check
     * @return True if the minecart can be converted, False if not
     */
    public static boolean canConvert(org.bukkit.entity.Entity minecart) {
        if (!(minecart instanceof Minecart) || TrainCarts.isWorldDisabled(minecart.getWorld())) {
            return false;
        }
        CommonEntity<Entity> common = CommonEntity.get(minecart);
        return common.hasControllerSupport() && common.getController() instanceof DefaultEntityController;
    }

    /**
     * Creates a Minecart Member from the source minecart specified<br>
     * Returns null if no member could be created for this Source.
     * If the source is already a Minecart Member, this is returned instead.
     *
     * @param source minecart to convert
     * @return Minecart Member conversion
     */
    @SuppressWarnings("rawtypes")
    public static MinecartMember<?> convert(Minecart source) {
        if (source.isDead()) {
            return null;
        }
        // Already assigned a controller?
        CommonEntity<?> entity = CommonEntity.get(source);
        if (entity.getController() instanceof MinecartMember) {
            MinecartMember member = (MinecartMember) entity.getController();
            member.updateUnloaded();
            return member;
        }

        // Check for conversion
        if (!canConvert(source)) {
            return null;
        }

        // Create a new Minecart controller for this type
        MinecartMember newController = createController(entity);
        if (newController == null) {
            // Unsupported
            return null;
        }

        // Set controllers and done
        entity.setController(newController);
        entity.setNetworkController(createNetworkController());

        // Unloaded?
        newController.updateUnloaded();

        return newController;
    }

    /**
     * Creates a suitable Minecart Member Network controller for an Entity.
     * Network settings are used to select the right one.
     *
     * @return new Network Controller
     */
    public static EntityNetworkController<?> createNetworkController() {
        if (TrainCarts.useNetworkSynchronizer) {
            return new MinecartMemberNetwork();
        } else {
            return new DefaultEntityNetworkController();
        }
    }

    /**
     * Creates a suitable Minecart Member controller for an Entity
     *
     * @param entityType of the controller to create
     * @return new MinecartMember instance suitable for the type of Entity, or null if none found
     */
    public static MinecartMember<?> createController(EntityType entityType) {
        try {
            Class<?> commonType = CommonEntityType.byEntityType(entityType).commonType.getType();
            Class<?> controllerClass = controllers.get(commonType);

            if (controllerClass != null) {
                return (MinecartMember<?>) controllerClass.newInstance();
            }
            return null;
        } catch (Throwable t) {
            TrainCarts.plugin.handle(t);
            return null;
        }
    }
    
    /**
     * Creates a suitable Minecart Member controller for an Entity
     *
     * @param entity to create a controller for
     * @return new MinecartMember instance suitable for the type of Entity, or null if none found
     */
    public static MinecartMember<?> createController(CommonEntity<?> entity) {
        Class<?> controllerClass = controllers.get(entity);
        if (controllerClass == null) {
            return null;
        }
        try {
            return (MinecartMember<?>) controllerClass.newInstance();
        } catch (Throwable t) {
            TrainCarts.plugin.handle(t);
            return null;
        }
    }

    /**
     * Spawns a minecart as being placed by a player
     *
     * @param at     location to spawn
     * @param player that placed something
     * @return the spawned Minecart Member, or null if it failed
     */
    public static MinecartMember<?> spawnBy(Location at, Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack item = inventory.getItemInMainHand();
        if (LogicUtil.nullOrEmpty(item)) {
            return null;
        }
        EntityType type = Conversion.toMinecartType.convert(item.getType());
        if (type == null) {
            return null;
        }

        // subtract held item
        if (player.getGameMode() != GameMode.CREATIVE) {
            ItemUtil.subtractAmount(item, 1);
            if (LogicUtil.nullOrEmpty(item)) {
                inventory.setItemInMainHand(null);
            } else {
                inventory.setItemInMainHand(item);
            }
        }

        // spawn and fire event
        MinecartMember<?> spawned = spawn(at, type);
        if (spawned != null && !spawned.getEntity().isDead()) {
            spawned.getGroup().getProperties().setDefault(player);
        }
        return spawned;
    }

    public static MinecartMember<?> spawn(Location at, EntityType type) {
        MinecartMember<?> controller = createController(type);
        if (controller == null) {
            throw new IllegalArgumentException("No suitable MinecartMember type for " + type);
        }
        EntityNetworkController<?> networkController = createNetworkController();
        CommonEntity.spawn(type, at, controller, networkController);
        controller.invalidateDirection();
        controller.setDirection(FaceUtil.yawToFace(at.getYaw()));
        return MemberSpawnEvent.call(controller).getMember();
    }

    /**
     * Tries to find a minecart member by UUID
     *
     * @param uuid of the minecart
     * @return Minecart Member, or null if not found
     */
    @ConverterMethod
    public static MinecartMember<?> getFromUID(UUID uuid) {
        for (org.bukkit.World world : WorldUtil.getWorlds()) {
            if (TrainCarts.isWorldDisabled(world)) {
                continue;
            }
            MinecartMember<?> member = getFromEntity(EntityUtil.getEntity(world, uuid));
            if (member != null && !member.isUnloaded()) {
                return member;
            }
        }
        return null;
    }

    /**
     * Checks whether a given Entity is a TrainCarts Minecart, and if so, returns the MinecartMember controller
     * 
     * @param entity to check
     * @return minecart member controller, or null if not a TrainCarts minecart
     */
    @ConverterMethod
    public static MinecartMember<?> getFromEntity(org.bukkit.entity.Entity entity) {
        if (entity instanceof Minecart) {
            EntityController<?> controller = CommonEntity.get((Minecart) entity).getController();
            if (controller instanceof MinecartMember) {
                MinecartMember<?> result = (MinecartMember<?>) controller;
                if (result.isUnloaded()) {
                    return null;
                } else {
                    return result;
                }
            }
        }
        return null;
    }

    public static MinecartMember<?> getAt(Block block) {
        return getAt(block.getWorld(), new IntVector3(block));
    }

    public static MinecartMember<?> getAt(org.bukkit.World world, IntVector3 coord) {
        org.bukkit.Chunk chunk = WorldUtil.getChunk(world, coord.x >> 4, coord.z >> 4);
        if (chunk != null) {
            MinecartMember<?> mm;
            MinecartMember<?> result = null;
            // find member in chunk
            for (org.bukkit.entity.Entity entity : WorldUtil.getEntities(chunk)) {
                if ((mm = getFromEntity(entity)) != null) {
                    if (mm.getEntity().loc.x.chunk() != coord.x) continue;
                    if (mm.getEntity().loc.y.chunk() != coord.y) continue;
                    if (mm.getEntity().loc.z.chunk() != coord.z) continue;
                    result = mm;
                    if (result.isHeadingTo(coord)) return result;
                }
            }
            if (result == null) {
                // find member in all groups
                for (MinecartGroup group : MinecartGroupStore.getGroupsUnsafe()) {
                    mm = group.getAt(coord);
                    if (mm == null) continue;
                    result = mm;
                    if (result.isHeadingTo(coord)) return result;
                }
            }
            return result;
        }
        return null;
    }

    public static MinecartMember<?> getAt(Location at) {
        return getAt(at, null);
    }

    public static MinecartMember<?> getAt(Location at, MinecartGroup in) {
        return getAt(at, in, 0.999);
    }

    public static MinecartMember<?> getAt(Location at, MinecartGroup in, double searchRadius) {
        if (at == null || TrainCarts.isWorldDisabled(at.getWorld())) {
            return null;
        }
        MinecartMember<?> result = null;
        final double distSquared = searchRadius * searchRadius;
        for (org.bukkit.entity.Entity e : WorldUtil.getNearbyEntities(at, searchRadius, searchRadius, searchRadius)) {
            MinecartMember<?> mm = getFromEntity(e);
            if (mm == null) {
                continue;
            }
            if (in != null && mm.getGroup() != in) {
                continue;
            }
            if (mm.getEntity().loc.distanceSquared(at) > distSquared) {
                continue;
            }
            result = mm;
            // If heading (moving) towards the point, instantly return it
            if (mm.isHeadingTo(at)) {
                return result;
            }
        }
        return result;
    }
}
