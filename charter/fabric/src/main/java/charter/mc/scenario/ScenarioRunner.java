package charter.mc.scenario;

import charter.engine.Action;
import charter.engine.Actor;
import charter.engine.Outcome;
import charter.engine.Reason;
import charter.gov.Office;
import charter.gov.Preset;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.grant.GrantScope;
import charter.grant.Holder;
import charter.grant.Power;
import charter.grant.actions.GrantPower;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Claim;
import charter.land.Core;
import charter.land.actions.ClaimLand;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.Text;
import charter.mc.granary.Granaries;
import charter.mc.granary.GranaryService;
import charter.mc.justice.Cells;
import charter.mc.land.Fixtures;
import charter.mc.land.LandItems;
import charter.mc.land.Placement;
import charter.mc.land.Protection;
import charter.org.MemberRecord;
import charter.org.OrgStatus;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.time.Durations;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds a {@link Scenario} in the world around a chunk: factions with their people,
 * keeps with stocked granaries and cells, farms, raised camps with stocked stores,
 * food chests, rails and a cart, and players put where the scenario says. Every
 * rule goes through the engine as the admin, so a scenario that the rules refuse
 * stops at that step and says why.
 *
 * <p>Each run remembers the factions it made, so {@link #clear} can take them down
 * again before the next part of the session.
 */
public final class ScenarioRunner {

    /** What a run said: the lines for the operator and whether it finished. */
    public record Report(boolean ok, List<String> lines) {
    }

    private static final class Failed extends RuntimeException {
        Failed(String message) {
            super(message);
        }
    }

    /** Factions made by scenario runs since the server started, newest last. */
    private static final List<OrgId> MADE = new ArrayList<>();

    private final CharterRuntime rt = CharterServer.runtime();
    private final Actor admin;
    private final MinecraftServer server;
    private final ServerLevel level;
    private final String dimension;
    private final int ox;
    private final int oz;
    private final List<String> lines = new ArrayList<>();
    private final Map<String, PlayerId> people = new LinkedHashMap<>();
    private final Map<String, ServerPlayer> online = new HashMap<>();
    private final Map<String, OrgId> factions = new HashMap<>();

    public ScenarioRunner(MinecraftServer server, ServerLevel level, int originChunkX, int originChunkZ, String operator) {
        this.server = server;
        this.level = level;
        this.dimension = Protection.dimension(level);
        this.ox = originChunkX;
        this.oz = originChunkZ;
        this.admin = new Actor.Admin(operator);
    }

    /** Runs the scenario with these real players named for its roles, in order. */
    public Report run(Scenario s, List<String> playerNames) {
        try {
            cast(s, playerNames);
            knobs(s);
            for (Scenario.Faction f : s.factions) faction(f);
            for (Scenario.Keep k : s.keeps) keep(k);
            for (Scenario.Farm f : s.farms) farm(f);
            for (Scenario.Camp c : s.camps) camp(c);
            if (s.raiseCamps && !s.camps.isEmpty()) {
                long raising = Durations.hours(rt.knobs.campRaisingHours);
                Granaries.around(() -> rt.sim.advance(raising));
                lines.add(Text.get("scenario.raised", "hours", rt.knobs.campRaisingHours));
            }
            for (Scenario.Chest c : s.chests) chest(c);
            for (Scenario.Rails r : s.rails) rails(r);
            for (Scenario.Give g : s.give) give(g);
            for (Scenario.Stand st : s.stand) stand(st);
            rt.claims.rebuild();
            return new Report(true, lines);
        } catch (Failed failed) {
            lines.add(failed.getMessage());
            return new Report(false, lines);
        }
    }

    // ---- people ----

    private void cast(Scenario s, List<String> names) {
        List<String> roles = s.roles();
        if (names.size() > roles.size()) {
            throw new Failed(Text.get("scenario.too_many_players", "count", names.size(), "roles", roles.size()));
        }
        for (int i = 0; i < roles.size(); i++) {
            String role = roles.get(i);
            if (i < names.size()) {
                ServerPlayer player = server.getPlayerList().getPlayer(names.get(i));
                if (player == null) throw new Failed(Text.get("scenario.not_online", "player", names.get(i)));
                PlayerId id = new PlayerId(player.getUUID());
                rt.state.membership(id).ifPresent(m -> {
                    throw new Failed(Text.get("scenario.already_member", "player", player.getPlainTextName(), "org", rt.describe.org(m.org())));
                });
                rt.state.touch(id, player.getPlainTextName(), rt.clock.now());
                people.put(role, id);
                online.put(role, player);
                lines.add(Text.get("scenario.cast_player", "role", role, "player", player.getPlainTextName()));
            } else {
                PlayerId id = new PlayerId(UUID.randomUUID());
                rt.state.touch(id, role, rt.clock.now());
                people.put(role, id);
            }
        }
        if (names.size() < roles.size()) lines.add(Text.get("scenario.cast_stand_ins", "count", roles.size() - names.size()));
    }

    private PlayerId person(String role) {
        PlayerId id = people.get(role);
        if (id == null) throw new Failed(Text.get("scenario.no_role", "role", role));
        return id;
    }

    // ---- knobs ----

    private void knobs(Scenario s) {
        for (Map.Entry<String, Double> e : s.knobs.entrySet()) {
            try {
                Field field = rt.knobs.getClass().getField(e.getKey());
                Class<?> type = field.getType();
                double v = e.getValue();
                if (type == int.class) field.setInt(rt.knobs, (int) Math.round(v));
                else if (type == long.class) field.setLong(rt.knobs, Math.round(v));
                else if (type == double.class) field.setDouble(rt.knobs, v);
                else if (type == boolean.class) field.setBoolean(rt.knobs, v != 0);
                else throw new Failed(Text.get("scenario.bad_knob", "knob", e.getKey()));
                lines.add(Text.get("scenario.knob", "knob", e.getKey(), "value", field.get(rt.knobs)));
            } catch (NoSuchFieldException | IllegalAccessException bad) {
                throw new Failed(Text.get("scenario.bad_knob", "knob", e.getKey()));
            }
        }
    }

    // ---- factions ----

    private void faction(Scenario.Faction f) {
        if (f.roles.isEmpty()) throw new Failed(Text.get("scenario.no_lord", "faction", f.id));
        String name = f.name;
        for (int n = 2; rt.state.orgNamed(name).isPresent(); n++) name = f.name + " " + n;
        PlayerId lord = person(f.roles.getFirst());
        engine(new Found(lord, name, Preset.LORDSHIP), "found " + name);
        OrgId org = rt.state.membership(lord).orElseThrow().org();
        rt.state.setStatus(org, OrgStatus.ACTIVE);
        factions.put(f.id, org);
        MADE.add(org);
        for (String role : f.roles.subList(1, f.roles.size())) {
            PlayerId member = person(role);
            engine(new InviteMember(org, member), "invite " + role, new Actor.Player(lord));
            engine(new AcceptInvite(member, org), "accept " + role, new Actor.Player(member));
        }
        // Members of a few days' standing, so presence and justice count them as they would in play.
        for (MemberRecord m : List.copyOf(rt.state.members(org))) {
            rt.state.restoreMembership(new MemberRecord(m.player(), m.org(), m.joinedAt() - Durations.days(30), m.roles()));
        }
        rt.state.siege.protectionWaived.add(org);
        if (f.diamonds > 0) engine(new Deposit(org, Currency.DIAMOND, f.diamonds), "fund " + name);
        if (f.cobblestone > 0) engine(new Deposit(org, Currency.COBBLESTONE, f.cobblestone), "fund " + name);
        for (Scenario.Office office : f.offices) office(org, office);
        lines.add(Text.get("scenario.faction", "name", name, "count", f.roles.size()));
    }

    private void office(OrgId org, Scenario.Office o) {
        engine(new CreateOffice(org, o.name), "office " + o.name);
        Office office = rt.state.officeNamed(org, o.name).orElseThrow(() -> new Failed(Text.get("scenario.refused", "step", "office " + o.name, "why", "?")));
        if (!o.holder.isEmpty()) engine(new Appoint(org, office.id, person(o.holder)), "appoint " + o.holder);
        for (String powerName : o.powers) {
            Power power;
            try {
                power = Power.valueOf(powerName);
            } catch (IllegalArgumentException bad) {
                throw new Failed(Text.get("scenario.bad_power", "power", powerName));
            }
            engine(new GrantPower(org, power, new Holder.OfOffice(office.id), GrantScope.ANY, false, 0), "grant " + powerName + " to " + o.name);
        }
    }

    private OrgId org(String id) {
        OrgId org = factions.get(id);
        if (org == null) throw new Failed(Text.get("scenario.no_faction", "faction", id));
        return org;
    }

    // ---- land ----

    private ChunkKey chunk(int[] at) {
        return new ChunkKey(dimension, ox + at[0], oz + at[1]);
    }

    /** The middle block of a chunk at ground level, the chunk loaded first. */
    private BlockPos ground(ChunkKey chunk, int dx, int dz) {
        level.getChunk(chunk.x(), chunk.z());
        int x = chunk.x() * 16 + 8 + dx;
        int z = chunk.z() * 16 + 8 + dz;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    /** A flat stone pad around {@code centre}, cleared three blocks up, so fixtures stand on solid ground. */
    private void pad(BlockPos centre, int radius) {
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos at = centre.offset(dx, 0, dz);
                level.setBlockAndUpdate(at.below(), Blocks.STONE_BRICKS.defaultBlockState());
                for (int dy = 0; dy < 3; dy++) level.setBlockAndUpdate(at.above(dy), Blocks.AIR.defaultBlockState());
            }
        }
    }

    private void keep(Scenario.Keep k) {
        OrgId org = org(k.faction);
        ChunkKey at = chunk(k.at);
        engine(new PlaceNexus(org, at), "keep at " + k.at[0] + "," + k.at[1]);
        Core keep = rt.state.claimAt(at).flatMap(c -> rt.state.core(c.core())).orElseThrow();
        BlockPos nexus = ground(at, 0, 0);
        pad(nexus, 3);
        level.setBlockAndUpdate(nexus, Blocks.LODESTONE.defaultBlockState());
        Fixtures.of(rt.state).add(new Fixtures.Fixture(Fixtures.Kind.NEXUS, dimension, nexus.getX(), nexus.getY(), nexus.getZ(), keep.id));
        rt.claims.rebuild();
        granary(keep, nexus, k.granary);
        if (!k.cell.isEmpty()) cell(keep, chunk(new int[]{k.at[0] + k.cellAt[0], k.at[1] + k.cellAt[1]}), k.cell);
        lines.add(Text.get("scenario.keep", "site", rt.describe.core(keep.id), "x", nexus.getX(), "z", nexus.getZ(),
                "percent", Math.round(k.granary * 100)));
    }

    /** Chests beside the nexus, linked as the keep's granary and filled with bread to the share of its limit. */
    private void granary(Core keep, BlockPos nexus, double share) {
        GranaryService service = Granaries.service();
        if (service == null) throw new Failed(Text.get("granary.not_running"));
        double limit = rt.sim.rules().storageLimit(keep);
        double bread = rt.sim.food().get("minecraft:bread").nourishment();
        int loaves = (int) Math.ceil(Math.max(0, Math.min(1, share)) * limit / bread);
        int perChest = 27 * 64;
        int chests = Math.max(1, Math.min(rt.knobs.granaryMaxChests, (loaves + perChest - 1) / perChest));
        for (int i = 0; i < chests; i++) {
            BlockPos at = nexus.offset(2, 0, i - 1);
            level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
            String problem = service.link(keep, level, at, false, true);
            if (problem != null) throw new Failed(Text.get("scenario.refused", "step", "granary", "why", Text.get(problem, "max", rt.knobs.granaryMaxChests)));
            Container box = (Container) level.getBlockEntity(at);
            int put = Math.min(perChest, loaves);
            for (int slot = 0; put > 0 && slot < box.getContainerSize(); slot++) {
                int n = Math.min(64, put);
                box.setItem(slot, new ItemStack(net.minecraft.world.item.Items.BREAD, n));
                put -= n;
                loaves -= n;
            }
        }
        service.reconcile(service.book().site(keep.id), null);
    }

    /** A stone room three by three by two inside, with an iron door and a button outside it. */
    private void cell(Core keep, ChunkKey in, String name) {
        BlockPos floor = ground(in, 0, 0);
        pad(floor, 3);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -1; dy <= 2; dy++) {
                    boolean wall = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == -1 || dy == 2;
                    level.setBlockAndUpdate(floor.offset(dx, dy, dz), wall ? Blocks.STONE_BRICKS.defaultBlockState() : Blocks.AIR.defaultBlockState());
                }
            }
        }
        BlockPos door = floor.offset(0, 0, 2);
        level.setBlockAndUpdate(door, Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(door.above(), Blocks.IRON_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.SOUTH)
                .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        level.setBlockAndUpdate(door.offset(1, 1, 1), Blocks.STONE_BUTTON.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ButtonBlock.FACING, Direction.SOUTH));
        Cells.Done done = Cells.markAt(level, keep, floor, name);
        if (!done.ok()) throw new Failed(Text.get("scenario.refused", "step", "cell " + name, "why", done.text()));
        lines.add(done.text());
    }

    private void farm(Scenario.Farm f) {
        OrgId org = org(f.faction);
        for (int[] at : f.chunks) {
            ChunkKey chunk = chunk(at);
            engine(new ClaimLand(org, chunk), "farm at " + at[0] + "," + at[1]);
            BlockPos centre = ground(chunk, 0, 0);
            if (f.crops) {
                for (int dx = -2; dx <= 2; dx++) {
                    for (int dz = -2; dz <= 2; dz++) {
                        BlockPos soil = centre.offset(dx, -1, dz);
                        for (int dy = 1; dy <= 2; dy++) level.setBlockAndUpdate(soil.above(dy), Blocks.AIR.defaultBlockState());
                        if (dx == 0 && dz == 0) {
                            level.setBlockAndUpdate(soil, Blocks.WATER.defaultBlockState());
                            continue;
                        }
                        level.setBlockAndUpdate(soil, Blocks.FARMLAND.defaultBlockState());
                        level.setBlockAndUpdate(soil.above(), ((CropBlock) Blocks.WHEAT).getStateForAge(((CropBlock) Blocks.WHEAT).getMaxAge()));
                    }
                }
            }
            if (f.animals) {
                for (int i = 0; i < 3; i++) {
                    BlockPos at3 = ground(chunk, 4, -4 + 4 * i);
                    var cow = EntityTypes.COW.spawn(level, at3, EntitySpawnReason.COMMAND);
                    if (cow != null) cow.setPersistenceRequired();
                }
            }
        }
        lines.add(Text.get("scenario.farm", "count", f.chunks.size()));
    }

    private void camp(Scenario.Camp c) {
        OrgId org = org(c.faction);
        ChunkKey at = chunk(c.at);
        engine(new PlantCamp(org, at), "camp at " + c.at[0] + "," + c.at[1]);
        Core camp = rt.state.claimAt(at).flatMap(cl -> rt.state.core(cl.core())).orElseThrow();
        BlockPos banner = ground(at, 0, 0);
        pad(banner, 2);
        level.setBlockAndUpdate(banner, ((BlockItem) LandItems.warBanner().getItem()).getBlock().defaultBlockState());
        Fixtures fixtures = Fixtures.of(rt.state);
        fixtures.add(new Fixtures.Fixture(Fixtures.Kind.BANNER, dimension, banner.getX(), banner.getY(), banner.getZ(), camp.id));
        BlockPos fire = Placement.raiseSignal(level, banner);
        if (fire != null) fixtures.add(new Fixtures.Fixture(Fixtures.Kind.CAMPFIRE, dimension, fire.getX(), fire.getY(), fire.getZ(), camp.id));
        if (!c.store.isEmpty()) {
            Map<String, Integer> accepted = rt.sim.supply().deposit("scenario", camp.id, c.store, new ArrayList<>());
            if (!accepted.equals(c.store)) lines.add(Text.get("scenario.store_short", "site", rt.describe.core(camp.id), "accepted", accepted));
        }
        for (int[] claim : c.claims) engine(new ClaimLand(org, chunk(claim)), "camp claim at " + claim[0] + "," + claim[1]);
        rt.claims.rebuild();
        lines.add(Text.get("scenario.camp", "site", rt.describe.core(camp.id), "x", banner.getX(), "z", banner.getZ()));
    }

    // ---- things ----

    private void chest(Scenario.Chest c) {
        BlockPos at = ground(chunk(c.at), 0, 0);
        pad(at, 1);
        level.setBlockAndUpdate(at, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(at, Blocks.CHEST.defaultBlockState());
        Container box = (Container) level.getBlockEntity(at);
        int slot = 0;
        for (Map.Entry<String, Integer> e : c.items.entrySet()) {
            Item item = item(e.getKey());
            int left = e.getValue();
            while (left > 0 && slot < box.getContainerSize()) {
                int n = Math.min(item.getDefaultMaxStackSize(), left);
                box.setItem(slot++, new ItemStack(item, n));
                left -= n;
            }
        }
        lines.add(Text.get("scenario.chest", "x", at.getX(), "z", at.getZ()));
    }

    /** A straight rail line between two chunk centres on a stone bed, level with the start; a chest cart at the start. */
    private void rails(Scenario.Rails r) {
        BlockPos from = ground(chunk(r.from), 0, 2);
        BlockPos to = ground(chunk(r.to), 0, 2);
        int y = from.getY();
        int dx = Integer.signum(to.getX() - from.getX());
        int dz = Integer.signum(to.getZ() - from.getZ());
        if (dx != 0 && dz != 0) throw new Failed(Text.get("scenario.rails_straight"));
        int length = Math.abs(to.getX() - from.getX()) + Math.abs(to.getZ() - from.getZ());
        for (int i = 0; i <= length; i++) {
            BlockPos at = new BlockPos(from.getX() + dx * i, y, from.getZ() + dz * i);
            level.getChunk(at.getX() >> 4, at.getZ() >> 4);
            level.setBlockAndUpdate(at.below(), Blocks.STONE_BRICKS.defaultBlockState());
            for (int up = 1; up <= 2; up++) level.setBlockAndUpdate(at.above(up), Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(at, Blocks.RAIL.defaultBlockState());
        }
        if (r.cart) {
            MinecartChest cart = EntityTypes.CHEST_MINECART.create(level, EntitySpawnReason.COMMAND);
            if (cart != null) {
                cart.setPos(from.getX() + 0.5, y + 0.1, from.getZ() + 0.5);
                level.addFreshEntity(cart);
            }
        }
        lines.add(Text.get("scenario.rails", "length", length + 1));
    }

    private void give(Scenario.Give g) {
        ServerPlayer player = online.get(g.role);
        if (player == null) return;
        for (Map.Entry<String, Integer> e : g.items.entrySet()) player.getInventory().add(new ItemStack(item(e.getKey()), e.getValue()));
    }

    private void stand(Scenario.Stand st) {
        ServerPlayer player = online.get(st.role);
        if (player == null) return;
        BlockPos at = ground(chunk(st.at), 0, -3);
        player.teleportTo(level, at.getX() + 0.5, at.getY(), at.getZ() + 0.5, java.util.Set.of(), player.getYRot(), player.getXRot(), true);
    }

    private static Item item(String id) {
        Identifier key = Identifier.tryParse(id);
        Item item = key == null ? null : BuiltInRegistries.ITEM.getValue(key);
        if (item == null || item == net.minecraft.world.item.Items.AIR) throw new Failed(Text.get("scenario.bad_item", "item", id));
        return item;
    }

    // ---- the engine ----

    private void engine(Action action, String step) {
        engine(action, step, admin);
    }

    private void engine(Action action, String step, Actor actor) {
        Outcome outcome = rt.engine.run(actor, action);
        if (outcome instanceof Outcome.Refused(Reason reason)) {
            String key = "reason." + reason.key();
            throw new Failed(Text.get("scenario.refused", "step", step, "why", Text.has(key) ? Text.get(key) : reason.text()));
        }
    }

    // ---- clearing ----

    /** Takes down every faction a scenario made: cores, claims, granaries, and its members set free. Returns how many. */
    public static int clear(CharterRuntime rt) {
        int n = 0;
        Actor admin = new Actor.Admin("scenario clear");
        GranaryService service = Granaries.service();
        for (OrgId org : List.copyOf(MADE)) {
            if (rt.state.org(org).isEmpty()) continue;
            for (Core core : List.copyOf(rt.state.coresOf(org))) {
                if (service != null) service.book().remove(core.id);
                rt.engine.run(admin, new LoseCore(core.id));
            }
            for (Claim claim : List.copyOf(rt.state.claimsOf(org))) rt.state.removeClaim(claim.id);
            for (MemberRecord m : List.copyOf(rt.state.members(org))) rt.state.leave(m.player());
            n++;
        }
        MADE.clear();
        rt.claims.rebuild();
        return n;
    }
}
