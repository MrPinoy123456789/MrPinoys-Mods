package charter.gametest;

import charter.engine.Actor;
import charter.gov.Preset;
import charter.id.ChunkKey;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.land.Core;
import charter.land.actions.ClaimLand;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.mc.CharterRuntime;
import charter.mc.CharterServer;
import charter.mc.granary.GranaryService;
import charter.mc.granary.Granaries;
import charter.mc.supply.Crossings;
import charter.mc.supply.SupplyLines;
import charter.org.actions.AcceptInvite;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.siege.KeepSupply;
import charter.siege.Supply;
import charter.siege.actions.DepositFood;
import charter.treasury.Currency;
import charter.treasury.actions.Deposit;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * M10: the supply lines in the world (PLAN.md §5.2 to §5.4; tests K, K2 and L).
 * Real players and real carts cross real chunk borders into a besieged keep, and
 * the crossing tracker the tick drives decides what counts.
 *
 * <p>Every test runs in one tick. The siege clock is shared by all gametests, so a
 * besieged world that waited across ticks could see another test age it.
 */
public final class SupplyGameTest {

    private static final String OW = "minecraft:overworld";
    private static final int BASE_CHUNK_X = -6100 - 300 * (int) ((System.currentTimeMillis() / 1000) % 90);
    private static final int BASE_CHUNK_Z = 4200;
    private static final int Y = 100;
    private static final String BREAD = "minecraft:bread";

    /** Ashfall's keep at chunk cx,cz with a stocked granary chest; Brinewatch's camp east of it; besieged. */
    private static final class World implements AutoCloseable {
        final GameTestHelper helper;
        final MinecraftServer server;
        final CharterRuntime rt = CharterServer.runtime();
        final GranaryService svc = Granaries.service();
        final ServerLevel level;
        final int cx;
        final int cz;
        final ServerPlayer lord;
        final PlayerId lordId;
        final OrgId ashfall;
        final OrgId brine;
        final PlayerId zed;
        final Core keep;
        final List<Core> camps = new ArrayList<>();
        final Container granary;
        private final int savedProtection;

        World(GameTestHelper helper, int slot) {
            this.helper = helper;
            this.server = helper.getLevel().getServer();
            this.level = server.overworld();
            this.cx = BASE_CHUNK_X + 40 * slot;
            this.cz = BASE_CHUNK_Z;
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) level.setChunkForced(x, z, true);
            }
            savedProtection = rt.knobs.newFactionProtectionDays;
            rt.knobs.newFactionProtectionDays = 0;
            lord = helper.makeMockServerPlayerInLevel();
            lordId = new PlayerId(lord.getUUID());
            Actor admin = new Actor.Admin("m10");
            rt.state.touch(lordId, "Ashlord", rt.clock.now());
            String tag = Long.toString(lord.getUUID().getLeastSignificantBits() & 0xFFFFFF, 36);
            check(rt.engine.run(admin, new Found(lordId, "Supply " + tag, Preset.LORDSHIP)).done(), "Ashfall was not founded");
            ashfall = rt.state.orgNamed("Supply " + tag).orElseThrow().id;
            zed = seen("Brinelord");
            check(rt.engine.run(new Actor.Player(zed), new Found(zed, "Raiders " + tag, Preset.LORDSHIP)).done(), "Brinewatch was not founded");
            brine = rt.state.orgNamed("Raiders " + tag).orElseThrow().id;
            join(ashfall, lordId, seen("Ashhand"));
            join(brine, zed, seen("Brinehand"));
            for (OrgId org : List.of(ashfall, brine)) {
                rt.engine.run(admin, new Deposit(org, Currency.DIAMOND, 400));
                rt.engine.run(admin, new Deposit(org, Currency.COBBLESTONE, 5000));
            }
            check(rt.engine.run(admin, new PlaceNexus(ashfall, new ChunkKey(OW, cx, cz))).done(), "The keep was refused");
            keep = rt.state.coresOf(ashfall).getFirst();

            BlockPos chestAt = new BlockPos(cx * 16 + 3, Y, cz * 16 + 3);
            level.setBlock(chestAt, Blocks.AIR.defaultBlockState(), 3); // an empty chest, never one left by an earlier test
            level.setBlock(chestAt, Blocks.CHEST.defaultBlockState(), 3);
            command("charter admin granary set " + chestAt.getX() + " " + chestAt.getY() + " " + chestAt.getZ());
            granary = (ChestBlockEntity) level.getBlockEntity(chestAt);
            for (int i = 0; i < 20; i++) granary.setItem(i, new ItemStack(Items.BREAD, 64));
            svc.reconcile(svc.book().site(keep.id), null);

            camp(cx + 2, cz);
            command("charter admin time advance 2h");
            check(rt.state.siege.keep(keep.id).supply != KeepSupply.SUPPLIED, "The keep is not besieged");
            SupplyLines.crossings().forget(SupplyLines.playerCarrier(lord));
        }

        void camp(int x, int z, int[]... extra) {
            Actor admin = new Actor.Admin("m10");
            check(rt.engine.run(admin, new PlantCamp(brine, new ChunkKey(OW, x, z))).done(), "A camp was refused at " + x + "," + z);
            Core camp = rt.state.coresOf(brine).stream().filter(c -> !c.isKeep() && c.center.equals(new ChunkKey(OW, x, z))).findFirst().orElseThrow();
            camps.add(camp);
            rt.sim.supply().deposit("setup", camp.id, Map.of(BREAD, 2000), new ArrayList<>());
            for (int[] at : extra) {
                check(rt.engine.run(admin, new ClaimLand(brine, new ChunkKey(OW, at[0], at[1]))).done(), "A camp claim was refused");
            }
        }

        PlayerId seen(String name) {
            PlayerId id = new PlayerId(UUID.randomUUID());
            rt.state.touch(id, name, rt.clock.now());
            return id;
        }

        void join(OrgId org, PlayerId lordOf, PlayerId other) {
            check(rt.engine.run(new Actor.Player(lordOf), new InviteMember(org, other)).done(), "Invite refused");
            check(rt.engine.run(new Actor.Player(other), new AcceptInvite(other, org)).done(), "Accept refused");
        }

        void command(String command) {
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
        }

        void check(boolean ok, String why) {
            helper.assertTrue(ok, why);
        }

        /** Moves the lord to a block x on chunk row cz and observes him there. */
        Crossings.Result walk(double x, double z) {
            lord.teleportTo(x, Y, z);
            return SupplyLines.observe(lord);
        }

        /** The block x just inside the west edge of chunk column {@code chunkX}, and just inside its east edge. */
        double westOf(int chunkX) {
            return chunkX * 16 + 0.5;
        }

        double eastOf(int chunkX) {
            return chunkX * 16 + 15.5;
        }

        double rowZ() {
            return cz * 16 + 8.5;
        }

        Supply supply() {
            return rt.sim.supply();
        }

        int allowance(String carrier) {
            return supply().allowance(carrier, keep.id).map(a -> a.left(BREAD)).orElse(0);
        }

        @Override
        public void close() {
            rt.knobs.newFactionProtectionDays = savedProtection;
            svc.book().remove(keep.id);
            for (Core core : camps) {
                if (rt.state.core(core.id).isPresent()) rt.engine.run(new Actor.Admin("m10"), new LoseCore(core.id));
            }
            if (rt.state.core(keep.id).isPresent()) rt.engine.run(new Actor.Admin("m10"), new LoseCore(keep.id));
            for (int x = cx - 3; x <= cx + 3; x++) {
                for (int z = cz - 3; z <= cz + 3; z++) level.setChunkForced(x, z, false);
            }
        }
    }

    // ---- K: runners ----

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aRunnerWalksFoodInThroughAnOpenSide(GameTestHelper helper) {
        try (World w = new World(helper, 0)) {
            String carrier = SupplyLines.playerCarrier(w.lord);
            w.lord.getInventory().add(new ItemStack(Items.BREAD, 30));
            ItemStack shulker = new ItemStack(Items.SHULKER_BOX);
            shulker.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(List.of(new ItemStack(Items.BREAD, 10))));
            w.lord.getInventory().add(shulker);

            w.walk(w.eastOf(w.cx - 2), w.rowZ());
            Crossings.Result entered = w.walk(w.westOf(w.cx - 1), w.rowZ());
            helper.assertTrue(entered == Crossings.Result.ENTERED, "Walking in from the open west side was " + entered);
            helper.assertTrue(w.allowance(carrier) == 40, "The bread carried, shulker box included, should count: " + w.allowance(carrier));

            var deposited = w.rt.engine.run(new Actor.Player(w.lordId),
                    new DepositFood(w.ashfall, w.keep.id, carrier, Map.of(BREAD, 25)));
            helper.assertTrue(deposited.done(), "The runner's deposit was refused: " + deposited);
            helper.assertTrue(w.allowance(carrier) == 15, "The deposit should use the allowance: " + w.allowance(carrier));
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void crossingTheCampTeleportsAndPearlsCarryNothing(GameTestHelper helper) {
        try (World w = new World(helper, 1)) {
            String carrier = SupplyLines.playerCarrier(w.lord);
            w.lord.getInventory().add(new ItemStack(Items.BREAD, 30));

            w.walk(w.westOf(w.cx + 2), w.rowZ());
            Crossings.Result fromCamp = w.walk(w.eastOf(w.cx + 1), w.rowZ());
            helper.assertTrue(fromCamp == Crossings.Result.CLOSED, "Crossing in from the camp's chunk was " + fromCamp);
            helper.assertTrue(w.allowance(carrier) == 0, "Crossing from the camp noted food");

            SupplyLines.crossings().forget(carrier);
            w.walk(w.eastOf(w.cx - 2) - 20, w.rowZ());
            Crossings.Result jumped = w.walk(w.westOf(w.cx - 1), w.rowZ());
            helper.assertTrue(jumped == Crossings.Result.JUMP, "A jump of more than 16 blocks was " + jumped);

            SupplyLines.crossings().forget(carrier);
            w.walk(w.eastOf(w.cx - 2), w.rowZ());
            SupplyLines.crossings().inTransit(carrier, SupplyLines.tick() + SupplyLines.TRANSIT_TICKS);
            Crossings.Result pearled = w.walk(w.westOf(w.cx - 1), w.rowZ());
            helper.assertTrue(pearled == Crossings.Result.IN_TRANSIT, "A crossing in transit by pearl was " + pearled);
            helper.assertTrue(w.allowance(carrier) == 0, "Teleports and pearls noted food");

            var refused = w.rt.engine.run(new Actor.Player(w.lordId),
                    new DepositFood(w.ashfall, w.keep.id, carrier, Map.of(BREAD, 5)));
            helper.assertTrue(refused.refused(), "A deposit with no allowance went in");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void anEnderChestCannotBeOpenedInABesiegedKeep(GameTestHelper helper) {
        try (World w = new World(helper, 2)) {
            BlockPos inKeep = new BlockPos(w.cx * 16 + 6, Y, w.cz * 16 + 6);
            BlockPos outside = new BlockPos((w.cx - 2) * 16 + 6, Y, w.cz * 16 + 6);
            w.level.setBlock(inKeep, Blocks.ENDER_CHEST.defaultBlockState(), 3);
            w.level.setBlock(outside, Blocks.ENDER_CHEST.defaultBlockState(), 3);
            helper.assertTrue(SupplyLines.enderChestBlocked(w.level, inKeep), "An ender chest opened inside a besieged keep");
            helper.assertTrue(!SupplyLines.enderChestBlocked(w.level, outside), "An ender chest on an open side should open");
            helper.succeed();
        }
    }

    // ---- K2: carts ----

    MinecartChest cart(World w, double x, int bread) {
        MinecartChest cart = EntityTypes.CHEST_MINECART.create(w.level, EntitySpawnReason.COMMAND);
        cart.setItem(0, new ItemStack(Items.BREAD, bread));
        cart.snapTo(x, Y, w.rowZ());
        w.level.addFreshEntity(cart);
        return cart;
    }

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void aCartLineFeedsABesiegedGranary(GameTestHelper helper) {
        try (World w = new World(helper, 3)) {
            Supply supply = w.supply();
            MinecartChest cart = cart(w, w.eastOf(w.cx - 2), 20);
            SupplyLines.observeCart(cart);
            cart.snapTo(w.westOf(w.cx - 1), Y, w.rowZ());
            Crossings.Result entered = SupplyLines.observeCart(cart);
            helper.assertTrue(entered == Crossings.Result.ENTERED, "A cart rolling in from the open side was " + entered);
            helper.assertTrue(supply.poolLeft(w.keep.id, BREAD) == 20, "The cart pool should hold the cart's bread: " + supply.poolLeft(w.keep.id, BREAD));

            int before = count(w.granary);
            ItemStack left = HopperBlockEntity.addItem(new SimpleContainer(1), w.granary, new ItemStack(Items.BREAD, 1), Direction.DOWN);
            helper.assertTrue(left.isEmpty() && count(w.granary) == before + 1, "A hopper should unload what the cart brought");
            helper.assertTrue(supply.poolLeft(w.keep.id, BREAD) == 19, "The hopper should debit the pool: " + supply.poolLeft(w.keep.id, BREAD));
            ItemStack beef = HopperBlockEntity.addItem(new SimpleContainer(1), w.granary, new ItemStack(Items.COOKED_BEEF, 1), Direction.DOWN);
            helper.assertTrue(beef.getCount() == 1, "Beef the cart did not bring went in by hopper");

            MinecartChest roundTrip = cart(w, w.eastOf(w.cx - 2), 10);
            SupplyLines.observeCart(roundTrip);
            roundTrip.snapTo(w.westOf(w.cx - 1), Y, w.rowZ());
            SupplyLines.observeCart(roundTrip);
            roundTrip.snapTo(w.eastOf(w.cx - 2), Y, w.rowZ());
            Crossings.Result out = SupplyLines.observeCart(roundTrip);
            helper.assertTrue(out == Crossings.Result.LEFT, "Rolling back out was " + out);
            helper.assertTrue(supply.poolLeft(w.keep.id, BREAD) == 19, "A cart that rolled in full and out full minted " + (supply.poolLeft(w.keep.id, BREAD) - 19));

            MinecartChest raider = cart(w, w.westOf(w.cx + 2), 50);
            SupplyLines.observeCart(raider);
            raider.snapTo(w.eastOf(w.cx + 1), Y, w.rowZ());
            Crossings.Result fromCamp = SupplyLines.observeCart(raider);
            helper.assertTrue(fromCamp == Crossings.Result.CLOSED, "A cart from the camp's chunk was " + fromCamp);
            helper.assertTrue(supply.poolLeft(w.keep.id, BREAD) == 19, "A cart from the camp added to the pool");
            for (MinecartChest c : List.of(cart, roundTrip, raider)) c.discard();
            helper.succeed();
        }
    }

    static int count(Container box) {
        int n = 0;
        for (int i = 0; i < box.getContainerSize(); i++) {
            if (box.getItem(i).is(Items.BREAD)) n += box.getItem(i).getCount();
        }
        return n;
    }

    // ---- L: encirclement ----

    @GameTest(maxTicks = 200, environment = "charter-gametest:long_siege")
    public void anEncircledKeepTakesNoFoodFromAnySide(GameTestHelper helper) {
        try (World w = new World(helper, 4)) {
            int cx = w.cx;
            int cz = w.cz;
            w.check(w.rt.engine.run(new Actor.Admin("m10"), new ClaimLand(w.brine, new ChunkKey(OW, cx + 2, cz + 1))).done(), "East claim refused");
            w.check(w.rt.engine.run(new Actor.Admin("m10"), new ClaimLand(w.brine, new ChunkKey(OW, cx + 2, cz - 1))).done(), "East claim refused");
            w.camp(cx - 2, cz, new int[]{cx - 2, cz + 1}, new int[]{cx - 2, cz - 1});
            w.camp(cx, cz + 2, new int[]{cx + 1, cz + 2}, new int[]{cx - 1, cz + 2});
            w.camp(cx, cz - 2, new int[]{cx + 1, cz - 2}, new int[]{cx - 1, cz - 2});
            w.command("charter admin time advance 2h");
            helper.assertTrue(w.rt.state.siege.keep(w.keep.id).supply == KeepSupply.ENCIRCLED,
                    "The keep should be encircled: " + w.rt.state.siege.keep(w.keep.id).supply);

            String carrier = SupplyLines.playerCarrier(w.lord);
            w.lord.getInventory().add(new ItemStack(Items.BREAD, 30));
            w.walk(w.eastOf(cx - 2), w.rowZ());
            Crossings.Result west = w.walk(w.westOf(cx - 1), w.rowZ());
            helper.assertTrue(west == Crossings.Result.CLOSED, "Walking in from the held west side was " + west);
            var refused = w.rt.engine.run(new Actor.Player(w.lordId),
                    new DepositFood(w.ashfall, w.keep.id, carrier, Map.of(BREAD, 5)));
            helper.assertTrue(refused.refused(), "An encircled keep took food in");
            helper.succeed();
        }
    }
}
