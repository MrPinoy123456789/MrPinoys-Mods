package foundry.mc;

import foundry.core.RaceTraits;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.Vec3;

/**
 * Applies the race traits from {@link RaceTraits} ({@code config/foundry/race_traits.json}) on the server, so Bedrock
 * players get them too:
 *
 * <ul>
 *   <li>stride: a movement speed attribute modifier while the race's ground applies (Dwarves underground, the folk of
 *   Mossbeard's Muddle on living ground);</li>
 *   <li>mount: speed and jump modifiers on the horse, donkey, mule or camel a player of the race rides (Humans), taken
 *   off the moment they get down;</li>
 *   <li>fall damage: a share taken off (the Fae), read by the LivingEntityFall mixin;</li>
 *   <li>jump drift: the forward push of a double jump (the Fae), read by {@link DoubleJump};</li>
 *   <li>sense: every few seconds the blocks within a small radius are searched for the race's list and the nearest is
 *   pointed out to that player alone, with a short trail of coloured dust from the player toward it and an action bar
 *   line. Particles and the action bar reach Bedrock players through Geyser; nothing is revealed past the radius.</li>
 * </ul>
 */
public final class RaceTraitsRuntime {
    private static final String FILE = "race_traits.json";
    private static final Identifier STRIDE = Identifier.fromNamespaceAndPath("foundry", "race_stride");
    private static final Identifier MOUNT_SPEED = Identifier.fromNamespaceAndPath("foundry", "race_mount_speed");
    private static final Identifier MOUNT_JUMP = Identifier.fromNamespaceAndPath("foundry", "race_mount_jump");
    private static RaceTraits traits = RaceTraits.defaults();
    private static long fileStamp = Long.MIN_VALUE;
    /** Each online player's traits (refreshed every {@value WornDisplays#SCAN_EVERY} ticks). */
    private static final Map<UUID, RaceTraits.Trait> OF = new HashMap<>();
    /** The mount each rider boosted, so the boost comes off when they get down. */
    private static final Map<UUID, LivingEntity> MOUNTED = new HashMap<>();
    /** The last block each player's sense pointed at, so the action bar only speaks when it changes or now and then. */
    private static final Map<UUID, BlockPos> POINTED = new HashMap<>();

    private RaceTraitsRuntime() {}

    /** The traits now in force (for tests and the docs). */
    public static RaceTraits traits() {
        return traits;
    }

    /** This player's traits, or an empty trait for a player of no race with traits. */
    public static RaceTraits.Trait of(UUID player) {
        return OF.getOrDefault(player, traits.of(""));
    }

    /** The share of fall damage this player is spared (the Fae), 0 for most. */
    public static double fallDamageCut(UUID player) {
        return of(player).fallDamage();
    }

    /** The fall damage multiplier for this player after their race's cut (what the LivingEntityFall mixin passes on). */
    public static float landing(ServerPlayer player, float multiplier) {
        double cut = fallDamageCut(player.getUUID());
        return cut > 0 ? (float) (multiplier * (1 - cut)) : multiplier;
    }

    static void tick(MinecraftServer server) {
        int now = server.getTickCount();
        if (now % 100 == 0) reload();
        if (now % WornDisplays.SCAN_EVERY == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                RaceTraits.Trait trait = null;
                for (var race : traits.races().entrySet()) {
                    if (Gate.hasNode(player, race.getKey())) trait = race.getValue();
                }
                if (trait == null) OF.remove(player.getUUID());
                else OF.put(player.getUUID(), trait);
            }
        }
        if (now % 5 == 0) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                RaceTraits.Trait trait = OF.get(player.getUUID());
                stride(player, trait);
                mount(player, trait);
            }
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            RaceTraits.Trait trait = OF.get(player.getUUID());
            if (trait == null || trait.sense() == null || !player.isAlive() || player.isSpectator()) continue;
            // each player on their own beat, so a full server does not search all at once
            if ((now + (player.getUUID().hashCode() & 0xff)) % trait.sense().every() == 0) sense(player, trait.sense());
        }
    }

    // ---- stride ----

    /** Puts on or takes off this player's stride bonus for where they stand now; public so a test can drive it. */
    public static void stride(ServerPlayer player, RaceTraits.Trait trait) {
        AttributeInstance speed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) return;
        var s = trait == null ? null : trait.stride();
        boolean on = s != null && s.bonus() > 0 && player.isAlive() && on(player, s.ground());
        if (on) {
            var have = speed.getModifier(STRIDE);
            if (have == null || have.amount() != s.bonus()) {
                speed.addOrUpdateTransientModifier(new AttributeModifier(STRIDE, s.bonus(), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
            }
        } else if (speed.getModifier(STRIDE) != null) {
            speed.removeModifier(STRIDE);
        }
    }

    /** Whether the player stands where this ground applies. */
    static boolean on(ServerPlayer player, RaceTraits.Ground ground) {
        return switch (ground) {
            case ANYWHERE -> true;
            case UNDERGROUND -> !player.level().canSeeSky(BlockPos.containing(player.getEyePosition()));
            case WILD -> {
                BlockState below = player.level().getBlockState(player.getBlockPosBelowThatAffectsMyMovement());
                yield below.is(BlockTags.LEAVES) || below.is(net.minecraft.world.level.block.Blocks.GRASS_BLOCK)
                        || below.is(net.minecraft.world.level.block.Blocks.MOSS_BLOCK)
                        || below.is(net.minecraft.world.level.block.Blocks.PODZOL)
                        || below.is(net.minecraft.world.level.block.Blocks.MYCELIUM)
                        || below.is(net.minecraft.world.level.block.Blocks.ROOTED_DIRT)
                        || below.is(net.minecraft.world.level.block.Blocks.COARSE_DIRT)
                        || below.is(net.minecraft.world.level.block.Blocks.MUD);
            }
        };
    }

    // ---- mount ----

    /** Boosts the mount this player rides, or takes the boost off the one they got down from; public for a test. */
    public static void mount(ServerPlayer player, RaceTraits.Trait trait) {
        var m = trait == null ? null : trait.mount();
        LivingEntity riding = player.getVehicle() instanceof LivingEntity l
                && (l instanceof net.minecraft.world.entity.animal.equine.AbstractHorse
                    || l instanceof net.minecraft.world.entity.animal.camel.Camel) ? l : null;
        LivingEntity before = MOUNTED.get(player.getUUID());
        if (before != null && (before != riding || m == null || !player.isAlive())) {
            unboost(before);
            MOUNTED.remove(player.getUUID());
        }
        if (riding == null || m == null || !player.isAlive()) return;
        boost(riding, Attributes.MOVEMENT_SPEED, MOUNT_SPEED, m.speed());
        boost(riding, Attributes.JUMP_STRENGTH, MOUNT_JUMP, m.jump());
        MOUNTED.put(player.getUUID(), riding);
    }

    private static void boost(LivingEntity mount, net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute,
                              Identifier id, double share) {
        AttributeInstance a = mount.getAttribute(attribute);
        if (a == null || share <= 0) return;
        var have = a.getModifier(id);
        if (have == null || have.amount() != share) {
            a.addOrUpdateTransientModifier(new AttributeModifier(id, share, AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
    }

    private static void unboost(LivingEntity mount) {
        for (var pair : List.of(java.util.Map.entry(Attributes.MOVEMENT_SPEED, MOUNT_SPEED), java.util.Map.entry(Attributes.JUMP_STRENGTH, MOUNT_JUMP))) {
            AttributeInstance a = mount.getAttribute(pair.getKey());
            if (a != null && a.getModifier(pair.getValue()) != null) a.removeModifier(pair.getValue());
        }
    }

    // ---- sense ----

    /**
     * Searches around the player for the sense's blocks and points at the nearest; returns it (null for none). Public so a
     * test can drive it.
     */
    public static BlockPos sense(ServerPlayer player, RaceTraits.Sense sense) {
        BlockPos found = find(player, sense);
        UUID id = player.getUUID();
        if (found == null) {
            POINTED.remove(id);
            return null;
        }
        if (player.level() instanceof ServerLevel level) {
            Vec3 from = player.getEyePosition().add(0, -0.4, 0);
            Vec3 to = Vec3.atCenterOf(found);
            Vec3 step = to.subtract(from).normalize().scale(0.35);
            var dust = new DustParticleOptions(sense.colour(), 0.8f);
            // a short trail from the player toward the find, never farther than two blocks out, so it hints, not marks
            for (int i = 1; i <= 6; i++) {
                Vec3 p = from.add(step.scale(i));
                level.sendParticles(player, dust, true, false, p.x, p.y, p.z, 1, 0.02, 0.02, 0.02, 0);
            }
        }
        BlockPos last = POINTED.put(id, found);
        if (!found.equals(last) || player.level().getServer().getTickCount() % 200 < sense.every()) {
            int dist = (int) Math.round(Math.sqrt(found.distToCenterSqr(player.position())));
            Block block = player.level().getBlockState(found).getBlock();
            player.sendSystemMessage(Component.literal(sense.name() + ": ").append(Component.translatable(block.getDescriptionId()))
                    .append(Component.literal(", about " + dist + " block" + (dist == 1 ? "" : "s") + " away")), true);
        }
        return found;
    }

    /** The nearest block within the sense's radius that it looks for, or null. */
    static BlockPos find(ServerPlayer player, RaceTraits.Sense sense) {
        List<Matcher> matchers = matchers(sense);
        if (matchers.isEmpty()) return null;
        var level = player.level();
        BlockPos centre = player.blockPosition();
        int r = sense.radius();
        List<BlockPos> hits = new ArrayList<>();
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r) continue;
                for (int dy = -r; dy <= r; dy++) {
                    if (dx * dx + dy * dy + dz * dz > r * r) continue;
                    at.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
                    if (!level.isLoaded(at)) continue;
                    BlockState state = level.getBlockState(at);
                    if (state.isAir()) continue;
                    for (Matcher m : matchers) {
                        if (m.test(level, at, state)) {
                            hits.add(at.immutable());
                            break;
                        }
                    }
                }
            }
        }
        if (hits.isEmpty()) return null;
        double[] d = new double[hits.size()];
        for (int i = 0; i < d.length; i++) d[i] = hits.get(i).distToCenterSqr(player.position());
        return hits.get(RaceTraits.nearest(d));
    }

    private interface Matcher {
        boolean test(net.minecraft.world.level.Level level, BlockPos pos, BlockState state);
    }

    private static final Map<RaceTraits.Sense, List<Matcher>> MATCHERS = new HashMap<>();

    private static List<Matcher> matchers(RaceTraits.Sense sense) {
        return MATCHERS.computeIfAbsent(sense, s -> {
            List<Matcher> out = new ArrayList<>();
            for (String text : s.blocks()) {
                if (text.equals(RaceTraits.LOOT_CHEST)) {
                    out.add((level, pos, state) -> state.hasBlockEntity()
                            && level.getBlockEntity(pos) instanceof RandomizableContainerBlockEntity c && c.getLootTable() != null);
                    continue;
                }
                var match = RaceTraits.BlockMatch.parse(text);
                if (match == null) continue;
                var block = BuiltInRegistries.BLOCK.getOptional(Identifier.parse(match.id())).orElse(null);
                if (block == null) continue;
                out.add((level, pos, state) -> state.is(block) && stateMatches(state, match.state()));
            }
            return List.copyOf(out);
        });
    }

    private static boolean stateMatches(BlockState state, Map<String, String> wanted) {
        for (var e : wanted.entrySet()) {
            Property<?> p = state.getBlock().getStateDefinition().getProperty(e.getKey());
            if (p == null || !name(state, p).equals(e.getValue())) return false;
        }
        return true;
    }

    private static <T extends Comparable<T>> String name(BlockState state, Property<T> p) {
        return p.getName(state.getValue(p));
    }

    // ---- file ----

    private static void reload() {
        Path path = FoundryMod.contentRoot().resolveSibling(FILE);
        long stamp;
        try {
            stamp = Files.isRegularFile(path) ? Files.getLastModifiedTime(path).toMillis() : -1L;
        } catch (IOException e) {
            return;
        }
        if (stamp == fileStamp) return;
        fileStamp = stamp;
        if (stamp < 0) {
            set(RaceTraits.defaults());
            return;
        }
        try {
            var parsed = RaceTraits.parse(Files.readString(path));
            parsed.problems().forEach(p -> FoundryMod.LOG.warn("Foundry: {}", p));
            if (parsed.problems().isEmpty()) {
                set(parsed.traits());
                FoundryMod.LOG.info("Foundry: race traits reloaded from {}", path.getFileName());
            } else {
                FoundryMod.LOG.warn("Foundry: {} has problems; keeping the race traits that were working", path.getFileName());
            }
        } catch (IOException e) {
            FoundryMod.LOG.warn("Foundry: could not read {}: {}", path.getFileName(), e.toString());
        }
    }

    private static void set(RaceTraits t) {
        traits = t;
        MATCHERS.clear();
        OF.clear();
    }

    static void forget(UUID player) {
        OF.remove(player);
        POINTED.remove(player);
        LivingEntity mount = MOUNTED.remove(player);
        if (mount != null) unboost(mount);
    }

    static void clear() {
        MOUNTED.values().forEach(RaceTraitsRuntime::unboost);
        MOUNTED.clear();
        OF.clear();
        POINTED.clear();
        fileStamp = Long.MIN_VALUE;
    }

    /** For a test: sets this player's traits now, without waiting for the scan. */
    public static void assign(ServerPlayer player, RaceTraits.Trait trait) {
        if (trait == null) OF.remove(player.getUUID());
        else OF.put(player.getUUID(), trait);
    }
}
