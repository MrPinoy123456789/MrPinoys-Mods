package charter.mc.land;

import charter.config.SpawnPolicy;
import charter.config.SpawnRuleSet;
import charter.id.OrgId;
import charter.land.Claim;
import charter.land.Core;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.book.CharterBook;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TraceableEntity;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.boat.AbstractChestBoat;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.EggItem;
import net.minecraft.world.item.FireworkRocketItem;
import net.minecraft.world.item.FishingRodItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.LeadItem;
import net.minecraft.world.item.NameTagItem;
import net.minecraft.world.item.SnowballItem;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.ThrowablePotionItem;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.item.WindChargeItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The spawn rules: an admin kingdom's land behaves like the spawn of a public server. Players walk, trade, ride and use
 * doors and buttons; they cannot fight, build, grief or be hurt. Operators are exempt from all of it. Every rule is a
 * switch in the {@code spawnRules} section of {@code charter.json} ({@link SpawnRuleSet}); the decisions that need no
 * Minecraft are in {@link SpawnPolicy}.
 *
 * <p>"In spawn" is one hash lookup: the chunks of every claim whose kingdom is an admin kingdom, kept per dimension and
 * rebuilt when the land changes or {@link #refresh} is called (the admin flag is a plain field with no event, so the
 * command that sets it calls refresh). When there is no admin kingdom, or the rules are off, every question answers at
 * once without a lookup.
 *
 * <p>The Fabric API events are registered here; the Mixins under {@code charter.mc.mixin} named Spawn* call the static
 * questions below, one line each.
 */
public final class SpawnRules {

    public static final int SWEEP_EVERY_TICKS = 20;
    private static final int REBUILD_EVERY_TICKS = 100;
    private static final double SWEEP_RADIUS = 48;

    /** Spawn chunks per dimension name, and the same sets looked up by the level's key for speed. */
    private static final Map<String, LongOpenHashSet> BY_DIMENSION = new HashMap<>();
    private static final Map<ResourceKey<Level>, LongOpenHashSet> BY_KEY = new IdentityHashMap<>();
    private static final LongOpenHashSet NONE = new LongOpenHashSet();
    private static boolean dirty = true;
    private static int count;
    private static long ticks;
    /** The runtime the sets were built for; a new server start rebuilds. */
    private static CharterRuntime builtFor;

    /** The allowlist, parsed once for the list object it came from. */
    private static List<String> parsedFrom;
    private static final Set<Block> USE_BLOCKS = new HashSet<>();
    private static final List<TagKey<Block>> USE_TAGS = new ArrayList<>();

    /** The origin of a random teleport under way (chorus fruit), on the one server thread. */
    private static Vec3 teleportFrom;
    private static boolean placingBack;

    private SpawnRules() {
    }

    public static void register() {
        ServerLivingEntityEvents.ALLOW_DAMAGE.register(SpawnRules::allowDamage);
        ServerPlayerEvents.COPY_FROM.register((old, fresh, alive) -> {
            if (!alive && (keepsDeathLoot(old) || charter.mc.RacePassives.keepsInventory(old))) {
                fresh.getInventory().replaceWith(old.getInventory());
                fresh.experienceLevel = old.experienceLevel;
                fresh.experienceProgress = old.experienceProgress;
                fresh.totalExperience = old.totalExperience;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(SpawnRules::tick);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            refresh();
            if (CharterServer.running()) CharterServer.runtime().state.history.listen(entry -> dirty = true);
        });
        HopperGate.register(SpawnRules::hopperInsert);
    }

    // ---- where the spawn is ----

    /** Marks the spawn chunks for rebuilding: after the admin flag changes, or land changes in a test. */
    public static void refresh() {
        dirty = true;
    }

    private static CharterRuntime rt() {
        return CharterServer.running() ? CharterServer.runtime() : null;
    }

    /** The rule set when the rules are on and there is spawn land at all, else null. Cheap enough for every hot path. */
    public static SpawnRuleSet live() {
        CharterRuntime rt = rt();
        if (rt == null || !rt.knobs.spawnRulesEnabled || rt.knobs.spawnRules == null) return null;
        if (dirty || builtFor != rt) rebuild(rt);
        return count == 0 ? null : rt.knobs.spawnRules;
    }

    private static void rebuild(CharterRuntime rt) {
        dirty = false;
        builtFor = rt;
        BY_DIMENSION.clear();
        BY_KEY.clear();
        count = 0;
        Set<OrgId> admins = new HashSet<>();
        for (var org : rt.state.orgs()) {
            if (org.adminKingdom && org.isTopLevel() && org.exists()) admins.add(org.id);
        }
        if (admins.isEmpty()) return;
        for (Claim claim : rt.state.claims()) {
            if (!admins.contains(rt.state.root(claim.owner))) continue;
            BY_DIMENSION.computeIfAbsent(claim.chunk.dimension(), d -> new LongOpenHashSet())
                    .add(ClaimIndex.pack(claim.chunk.x(), claim.chunk.z()));
            count++;
        }
    }

    private static LongOpenHashSet chunksOf(Level level) {
        ResourceKey<Level> key = level.dimension();
        LongOpenHashSet set = BY_KEY.get(key);
        if (set == null) {
            set = BY_DIMENSION.getOrDefault(Protection.dimension(level), NONE);
            BY_KEY.put(key, set);
        }
        return set;
    }

    /** Whether the column at this block lies in an admin kingdom's land (with the rules on). */
    public static boolean inSpawn(Level level, int blockX, int blockZ) {
        if (live() == null) return false;
        return chunksOf(level).contains(ClaimIndex.pack(blockX >> 4, blockZ >> 4));
    }

    public static boolean inSpawn(Level level, BlockPos pos) {
        return inSpawn(level, pos.getX(), pos.getZ());
    }

    public static boolean inSpawn(Entity entity) {
        return inSpawn(entity.level(), entity.getBlockX(), entity.getBlockZ());
    }

    /** Operators are exempt from every spawn rule. Creative mode is not. */
    public static boolean isOp(Entity entity) {
        return entity instanceof ServerPlayer sp && sp.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    /** The player behind an entity: itself, or the owner of an arrow, a potion, lit TNT. */
    private static Player playerBehind(Entity entity) {
        if (entity instanceof Player p) return p;
        if (entity instanceof TraceableEntity t && t.getOwner() instanceof Player p) return p;
        return null;
    }

    // ---- building and using (through Protection and Guard) ----

    /**
     * Protection's say for a player at a block: TRUE for an operator in spawn (the core chunk rule and membership no
     * longer matter), FALSE for anyone else in spawn, null outside spawn or with {@code spawnNoBuild} off.
     */
    public static Boolean actorVerdict(Player player, Level level, BlockPos pos) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoBuild || !inSpawn(level, pos)) return null;
        return isOp(player);
    }

    /** Whether a non-player actor (a mob, an arrow without a player) may change things here. */
    public static boolean refusesNonPlayer(Level level, BlockPos pos) {
        SpawnRuleSet r = live();
        return r != null && r.spawnNoBuild && inSpawn(level, pos);
    }

    private static void parseUse(SpawnRuleSet r) {
        if (parsedFrom == r.spawnAllowedUse) return;
        parsedFrom = r.spawnAllowedUse;
        USE_BLOCKS.clear();
        USE_TAGS.clear();
        SpawnPolicy.UseList list = SpawnPolicy.parseUse(r.spawnAllowedUse);
        for (String id : list.blocks()) {
            Identifier key = Identifier.tryParse(id);
            if (key != null && BuiltInRegistries.BLOCK.containsKey(key)) USE_BLOCKS.add(BuiltInRegistries.BLOCK.getValue(key));
        }
        for (String id : list.tags()) {
            Identifier key = Identifier.tryParse(id);
            if (key != null) USE_TAGS.add(TagKey.create(Registries.BLOCK, key));
        }
    }

    /** Whether the allowlist names this block. A waxed sign may always be read: its click commands run, nothing changes. */
    public static boolean allowedUse(Level level, BlockPos pos, BlockState state) {
        SpawnRuleSet r = live();
        if (r == null) return true;
        parseUse(r);
        if (USE_BLOCKS.contains(state.getBlock())) return true;
        for (TagKey<Block> tag : USE_TAGS) {
            if (state.is(tag)) return true;
        }
        if (state.getBlock() instanceof SignBlock) {
            BlockEntity be = level.getBlockEntity(pos);
            return be instanceof SignBlockEntity sign && sign.isWaxed();
        }
        return false;
    }

    /**
     * The answer to a right-click on a block in spawn, or null to leave it to the claim rules. An allowlisted block may
     * be used with an empty hand, or when not sneaking (the block's own use comes first; an item's use on it is refused by
     * {@link #refusesItemUseOn}). The Charter book still opens anywhere.
     */
    public static InteractionResult useBlockVerdict(Player player, Level level, InteractionHand hand, BlockHitResult hit) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoBuild || !(player instanceof ServerPlayer sp)) return null;
        BlockPos pos = hit.getBlockPos();
        if (!inSpawn(level, pos) || isOp(player)) return null;
        ItemStack stack = player.getItemInHand(hand);
        if ((stack.isEmpty() || !player.isSecondaryUseActive()) && allowedUse(level, pos, level.getBlockState(pos))) {
            return InteractionResult.PASS;
        }
        if (CharterBook.isBook(stack)) {
            InteractionResult opened = CharterBook.use(sp, hand);
            return opened == InteractionResult.PASS ? InteractionResult.FAIL : opened;
        }
        return InteractionResult.FAIL;
    }

    /** Behind the ItemStack mixin: an item's own use on a block in spawn (axes, hoes, bone meal, frames, eggs). */
    public static boolean refusesItemUseOn(UseOnContext context) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoBuild) return false;
        Player player = context.getPlayer();
        if (player == null || player.level().isClientSide()) return false;
        if (context.getItemInHand().getItem() instanceof BlockItem) return false;
        if (!inSpawn(context.getLevel(), context.getClickedPos()) || isOp(player)) return false;
        deny(player, context.getLevel(), context.getClickedPos());
        return true;
    }

    /**
     * The answer to a right-click on an entity in spawn, or null. Trading with villagers and riding boats, plain carts
     * and horses are allowed with nothing in hand that would name, leash or feed them; everything else is refused.
     */
    public static InteractionResult useEntityVerdict(Player player, InteractionHand hand, Entity entity) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoBuild || !(player instanceof ServerPlayer)) return null;
        if (!inSpawn(entity) || isOp(player)) return null;
        if (entity instanceof Player) return InteractionResult.PASS;
        ItemStack stack = player.getItemInHand(hand);
        Item item = stack.getItem();
        if (entity instanceof AbstractVillager && r.spawnAllowTrading
                && !(item instanceof NameTagItem || item instanceof SpawnEggItem || item instanceof LeadItem)) {
            return InteractionResult.PASS;
        }
        boolean rideable = (entity instanceof AbstractBoat && !(entity instanceof AbstractChestBoat))
                || entity instanceof Minecart || entity instanceof AbstractHorse;
        if (rideable && r.spawnAllowRiding && stack.isEmpty() && !player.isSecondaryUseActive()) return InteractionResult.PASS;
        return InteractionResult.FAIL;
    }

    /** The answer to a left-click on an entity when either end is in spawn, or null. */
    public static InteractionResult attackVerdict(Player player, Entity target) {
        SpawnRuleSet r = live();
        if (r == null || !(player instanceof ServerPlayer)) return null;
        if (!(inSpawn(target) || inSpawn(player)) || isOp(player)) return null;
        boolean refused;
        if (target instanceof Player) refused = r.spawnNoPvp;
        else if (target instanceof LivingEntity && !(target instanceof net.minecraft.world.entity.decoration.ArmorStand)) refused = r.spawnProtectMobs;
        else refused = r.spawnNoBuild && inSpawn(target);
        return refused ? InteractionResult.FAIL : null;
    }

    /** The answer to using an item in the air in spawn: projectiles and launchers are refused, an elytra boost and Riptide by rule. */
    public static InteractionResult useItemVerdict(Player player, InteractionHand hand) {
        SpawnRuleSet r = live();
        if (r == null || !(player instanceof ServerPlayer) || !inSpawn(player) || isOp(player)) return null;
        ItemStack stack = player.getItemInHand(hand);
        Item item = stack.getItem();
        if (item instanceof FireworkRocketItem) return r.spawnAllowElytraBoost ? null : InteractionResult.FAIL;
        if (item instanceof TridentItem && EnchantmentHelper.getTridentSpinAttackStrength(stack, player) > 0) {
            return r.spawnAllowRiptide ? null : InteractionResult.FAIL;
        }
        if (!r.spawnNoProjectiles) return null;
        boolean launches = item instanceof BowItem || item instanceof CrossbowItem || item instanceof TridentItem
                || item instanceof SnowballItem || item instanceof EggItem || item instanceof FishingRodItem
                || item instanceof WindChargeItem || item instanceof ThrowablePotionItem;
        return launches ? InteractionResult.FAIL : null;
    }

    /** Tells a player why not; shares Protection's throttle. */
    public static void deny(Player player, Level level, BlockPos pos) {
        Protection.deny(player, level, pos);
    }

    // ---- damage, death and effects ----

    private static boolean allowDamage(LivingEntity entity, DamageSource source, float amount) {
        SpawnRuleSet r = live();
        if (r == null) return true;
        boolean victimInside = inSpawn(entity);
        Player attacker = playerBehind(source.getEntity());
        boolean attackerInside = attacker != null && inSpawn(attacker);
        if (!victimInside && !attackerInside) return true;
        SpawnPolicy.Cause cause = source.is(DamageTypes.FELL_OUT_OF_WORLD) ? SpawnPolicy.Cause.VOID
                : source.is(DamageTypeTags.BYPASSES_INVULNERABILITY) ? SpawnPolicy.Cause.KILL
                : source.is(DamageTypeTags.IS_FALL) ? SpawnPolicy.Cause.FALL
                : source.is(DamageTypeTags.IS_EXPLOSION) ? SpawnPolicy.Cause.EXPLOSION
                : SpawnPolicy.Cause.OTHER;
        SpawnPolicy.Damage verdict = SpawnPolicy.damage(r, cause, entity instanceof Player, victimInside,
                attacker != null, attackerInside, attacker != null && isOp(attacker));
        if (verdict == SpawnPolicy.Damage.RESCUE && entity instanceof ServerPlayer player) {
            rescue(player);
            return false;
        }
        return verdict != SpawnPolicy.Damage.DENY;
    }

    /** Puts a player who fell out of the world under the spawn back on its Nexus, or on the ground above where they fell. */
    public static void rescue(ServerPlayer player) {
        CharterRuntime rt = rt();
        if (rt == null) return;
        ServerLevel level = player.level();
        double x = player.getX();
        double z = player.getZ();
        ServerLevel target = level;
        double y;
        Fixtures.Fixture nexus = nexusUnder(rt, level, player.getBlockX(), player.getBlockZ());
        ServerLevel nexusLevel = nexus == null ? null : levelNamed(level.getServer(), nexus.dimension());
        if (nexusLevel != null) {
            target = nexusLevel;
            x = nexus.x() + 0.5;
            z = nexus.z() + 0.5;
            target.getChunk(nexus.x() >> 4, nexus.z() >> 4);
            y = nexus.y() + 1.0;
        } else {
            level.getChunk(player.getBlockX() >> 4, player.getBlockZ() >> 4);
            y = level.getHeight(Heightmap.Types.MOTION_BLOCKING, player.getBlockX(), player.getBlockZ());
        }
        if (player.isPassenger()) player.stopRiding();
        player.resetFallDistance();
        player.setDeltaMovement(Vec3.ZERO);
        player.teleportTo(target, x, y, z, Set.of(), player.getYRot(), player.getXRot(), true);
        player.sendSystemMessage(Component.literal(Text.get("spawn.void_rescue")).withStyle(ChatFormatting.AQUA), true);
    }

    private static Fixtures.Fixture nexusUnder(CharterRuntime rt, Level level, int blockX, int blockZ) {
        var ref = rt.claims.at(Protection.dimension(level), blockX >> 4, blockZ >> 4);
        if (ref == null) return null;
        OrgId top = rt.state.root(ref.owner());
        Core keep = rt.state.coresOf(top).stream().filter(Core::isKeep).findFirst().orElse(null);
        return keep == null ? null : Fixtures.of(rt.state).first(keep.id, Fixtures.Kind.NEXUS);
    }

    private static ServerLevel levelNamed(MinecraftServer server, String dimension) {
        for (ServerLevel l : server.getAllLevels()) {
            if (Protection.dimension(l).equals(dimension)) return l;
        }
        return null;
    }

    /** Behind the LivingEntity mixin: a player who dies in spawn drops nothing, and keeps it all on respawn. */
    public static boolean keepsDeathLoot(Entity entity) {
        SpawnRuleSet r = live();
        return r != null && r.spawnKeepInventory && entity instanceof ServerPlayer && inSpawn(entity);
    }

    /** Behind the LivingEntity mixin: a harmful effect from someone (a potion, a tipped arrow) on a player in spawn. */
    public static boolean refusesEffect(LivingEntity target, MobEffectInstance effect, Entity source) {
        if (source == null || !(target instanceof Player)) return false;
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoHarmfulEffects) return false;
        if (effect.getEffect().value().getCategory() != MobEffectCategory.HARMFUL) return false;
        if (!inSpawn(target)) return false;
        Player behind = playerBehind(source);
        return behind == null || !isOp(behind);
    }

    /** Behind the LivingEntity mixin: remembers where a random teleport (chorus fruit) starts. */
    public static void teleportStarts(LivingEntity entity) {
        teleportFrom = entity instanceof ServerPlayer ? entity.position() : null;
    }

    /** After a random teleport: a player who was outside and landed inside is put back. Returns true when refused. */
    public static boolean teleportEnds(LivingEntity entity, boolean moved) {
        Vec3 from = teleportFrom;
        teleportFrom = null;
        if (!moved || from == null || !(entity instanceof ServerPlayer player)) return false;
        SpawnRuleSet r = live();
        if (r == null || isOp(player)) return false;
        boolean wasInside = inSpawn(player.level(), (int) Math.floor(from.x), (int) Math.floor(from.z));
        if (!SpawnPolicy.refusesTeleport(r, wasInside, inSpawn(player))) return false;
        player.teleportTo(from.x, from.y, from.z);
        player.sendSystemMessage(Component.literal(Text.get("spawn.no_pearl")).withStyle(ChatFormatting.RED), true);
        return true;
    }

    /** Behind the ender pearl mixin: a pearl from a player outside spawn that lands inside it does nothing. */
    public static boolean refusesPearl(Entity pearl, Entity owner, Vec3 landing) {
        if (!(owner instanceof ServerPlayer player)) return false;
        SpawnRuleSet r = live();
        if (r == null || isOp(player)) return false;
        boolean landsInside = inSpawn(pearl.level(), (int) Math.floor(landing.x), (int) Math.floor(landing.z));
        if (!SpawnPolicy.refusesTeleport(r, inSpawn(player), landsInside)) return false;
        player.sendSystemMessage(Component.literal(Text.get("spawn.no_pearl")).withStyle(ChatFormatting.RED), true);
        return true;
    }

    /** Behind the ServerPlayer mixin: with item drops off, a dropped stack goes back into the inventory. Returns true when refused. */
    public static boolean refusesDrop(ServerPlayer player, ItemStack stack) {
        if (placingBack || stack.isEmpty() || !player.isAlive()) return false;
        SpawnRuleSet r = live();
        if (r == null || r.spawnAllowItemDrop || !inSpawn(player) || isOp(player)) return false;
        placingBack = true;
        try {
            player.getInventory().placeItemBackInInventory(stack, net.minecraft.util.Prediction.SERVER_ONLY);
        } finally {
            placingBack = false;
        }
        return true;
    }

    // ---- mobs ----

    /** Behind the ServerLevel mixin: a hostile mob is not added inside spawn, whatever brought it (spawning, spawners, raids). */
    public static boolean refusesHostile(Level level, Entity entity) {
        if (!(entity instanceof Enemy)) return false;
        SpawnRuleSet r = live();
        return r != null && r.spawnNoHostileSpawns && inSpawn(level, entity.getBlockX(), entity.getBlockZ());
    }

    /** Behind the mob griefing mixins: sheep, rabbits and farmer villagers leave spawn's blocks alone. */
    public static boolean refusesMobGriefing(Entity mob) {
        SpawnRuleSet r = live();
        return r != null && r.spawnNoMobGriefing && inSpawn(mob);
    }

    // ---- the world ----

    /** Whether something going from one block to another crosses the spawn's border (fluids, pistons, dispensers, hoppers). */
    public static boolean crossesBorder(Level level, BlockPos from, BlockPos to) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnSealBorder) return false;
        return SpawnPolicy.crossesBorder(inSpawn(level, from), inSpawn(level, to));
    }

    /** Whether fire may not be set or burn here. */
    public static boolean refusesFire(Level level, BlockPos pos) {
        SpawnRuleSet r = live();
        return r != null && r.spawnNoFire && inSpawn(level, pos);
    }

    /** Whether an explosion may not break this block: in spawn, unless an operator set it off. */
    public static boolean sparesFromExplosion(Level level, BlockPos pos, Entity owner) {
        SpawnRuleSet r = live();
        return r != null && r.spawnNoExplosions && inSpawn(level, pos) && !isOp(owner);
    }

    /** Whether an explosion may not hurt or push this entity: it stands in spawn, and no operator set it off. */
    public static boolean sparesEntityFromExplosion(Entity entity, Entity source) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoExplosions || !inSpawn(entity)) return false;
        Player behind = source == null ? null : playerBehind(source);
        return behind == null || !isOp(behind);
    }

    private static long memoChunk = Long.MIN_VALUE;
    private static Level memoLevel;
    private static boolean memoInside;
    private static long memoTick = -1;

    /** Behind the random tick mixin: nothing grows, decays, melts or dries in spawn. Calls come chunk by chunk, so the last answer is kept. */
    public static boolean freezes(Level level, BlockPos pos) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnFreezeBlocks) return false;
        long chunk = ClaimIndex.pack(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != memoChunk || level != memoLevel || memoTick != ticks) {
            memoChunk = chunk;
            memoLevel = level;
            memoTick = ticks;
            memoInside = chunksOf(level).contains(chunk);
        }
        return memoInside;
    }

    /** Behind the ServerLevel mixin: no snow piles up and no water freezes from the weather in spawn. */
    public static boolean refusesWeather(Level level, BlockPos pos) {
        SpawnRuleSet r = live();
        return r != null && r.spawnNoSnowAndIce && inSpawn(level, pos);
    }

    /** Behind the lightning mixin: a real bolt does nothing in spawn. */
    public static boolean refusesLightning(Entity bolt) {
        SpawnRuleSet r = live();
        return r != null && r.spawnNoLightning && inSpawn(bolt);
    }

    /** Behind the portal mixin: no nether portal is built in spawn by a player arriving from the Nether. */
    public static boolean refusesPortal(Level level, BlockPos pos) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoPortalCreation) return false;
        // The game may build the frame anywhere within 16 blocks of the spot it asks about. Points 16 apart across that
        // square meet every chunk it touches, so a frame can never land in the spawn from a spot just outside it.
        for (int dx = -16; dx <= 16; dx += 16) {
            for (int dz = -16; dz <= 16; dz += 16) {
                if (inSpawn(level, pos.getX() + dx, pos.getZ() + dz)) return true;
            }
        }
        return false;
    }

    /** Behind the block state mixin: a projectile hitting a block in spawn leaves it as it is, unless an operator shot it. */
    public static boolean refusesProjectileHit(Level level, BlockPos pos, Entity projectile) {
        SpawnRuleSet r = live();
        if (r == null || !r.spawnNoProjectileBlockHits || !inSpawn(level, pos)) return false;
        Player behind = playerBehind(projectile);
        return behind == null || !isOp(behind);
    }

    /** Whether a vehicle or a hanging thing (frame, painting) in spawn may not be hurt by this source. */
    public static boolean sparesThing(Entity thing, DamageSource source) {
        SpawnRuleSet r = live();
        if (r == null || !inSpawn(thing)) return false;
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return false;
        Player behind = playerBehind(source.getEntity());
        if (behind != null) return r.spawnNoBuild && !isOp(behind);
        if (source.is(DamageTypeTags.IS_EXPLOSION)) return r.spawnNoExplosions;
        return r.spawnNoBuild && source.getEntity() != null;
    }

    private static ItemStack hopperInsert(Container source, Container destination, ItemStack stack, net.minecraft.core.Direction face) {
        if (live() == null) return null;
        Level level = levelOf(source);
        BlockPos from = posOf(source);
        BlockPos to = posOf(destination);
        if (level == null || from == null || to == null) return null;
        return crossesBorder(level, from, to) ? stack : null;
    }

    private static BlockPos posOf(Container container) {
        if (container instanceof BlockEntity be) return be.getBlockPos();
        if (container instanceof Entity e) return e.blockPosition();
        return null;
    }

    private static Level levelOf(Container container) {
        if (container instanceof BlockEntity be) return be.getLevel();
        if (container instanceof Entity e) return e.level();
        return null;
    }

    // ---- every tick ----

    private static void tick(MinecraftServer server) {
        ticks++;
        if (ticks % REBUILD_EVERY_TICKS == 0) dirty = true;
        SpawnRuleSet r = live();
        if (r == null || ticks % SWEEP_EVERY_TICKS != 0) return;
        sweep(server, r);
    }

    /** Tops up the hunger of players in spawn and removes hostile mobs near anyone, inside the spawn. */
    public static void sweep(MinecraftServer server, SpawnRuleSet r) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (r.spawnNoHunger && inSpawn(player) && player.getFoodData().getFoodLevel() < 20) {
                player.getFoodData().setFoodLevel(20);
            }
            if (!r.spawnRemoveHostiles) continue;
            AABB box = player.getBoundingBox().inflate(SWEEP_RADIUS);
            for (Mob mob : player.level().getEntitiesOfClass(Mob.class, box,
                    m -> m instanceof Enemy && !m.hasCustomName() && inSpawn(m))) {
                mob.discard();
            }
        }
    }
}
