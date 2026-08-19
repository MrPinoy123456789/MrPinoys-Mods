package pocketdungeons;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Where a player stood before they entered. Recorded per member, not per
 * instance: everyone in a party came from somewhere different and each of them
 * has to be put back exactly where they were.
 */
record ReturnPoint(ResourceKey<Level> dimension, Vec3 pos, float yaw, float pitch) {}
