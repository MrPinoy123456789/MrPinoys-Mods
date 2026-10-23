package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Glyphs (spec 3, decision 2026-10-21): the {@code require_item} handler refuses the wrong
 * item and leaves it in hand, accepts the right one, consumes one and removes every entity
 * of the glyph; a glyph's data survives the attachment save and reload; an unknown handler
 * id refuses without throwing.
 */
public final class GlyphGameTest {

    private static final Vec3 AT = new Vec3(2.5, 1.0, 2.5);

    /** The wrong item stays in hand; the right one is consumed once and the glyph is gone. */
    @GameTest(maxTicks = 20)
    public void requireItemConsumesTheRightItemOnce(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        ServerLevel level = helper.getLevel();
        Glyph glyph = requireItemGlyph(level, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)).add(AT),
                new ItemStack(Items.DIAMOND));

        // Wrong item in hand: refused, nothing consumed, the glyph still stands.
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 1));
        click(player, level, glyph, player.getMainHandItem());
        helper.assertValueEqual(1, player.getInventory().getItem(0).getCount(),
                "the wrong item is not consumed");
        helper.assertTrue(glyph.anchor().isAlive(), "the glyph stands after a refusal");

        // Right item in hand, a stack of two: one is consumed, the glyph vanishes whole.
        player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 2));
        click(player, level, glyph, player.getMainHandItem());
        helper.assertValueEqual(1, player.getInventory().getItem(0).getCount(),
                "exactly one of the right item is consumed");
        helper.assertTrue(!glyph.anchor().isAlive(), "the anchor is gone after the accept");
        helper.assertTrue(glyph.visuals(level).isEmpty(),
                "every entity of the glyph is removed");
        helper.succeed();
    }

    /** A glyph's handler and data survive the attachment's save and reload. */
    @GameTest(maxTicks = 20)
    public void glyphDataSurvivesASaveAndLoad(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        CompoundTag data = new CompoundTag();
        data.putString("item", "minecraft:diamond");
        data.putInt("count", 1);
        data.putString("label", "Diamond");
        Glyph glyph = Glyph.spawn(level, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)).add(AT),
                0.8f, 1.6f, GlyphHandlers.REQUIRE_ITEM, data, List.of());

        CompoundTag saved = new CompoundTag();
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(glyph.anchor().problemPath(), PocketDungeonsMod.LOG)) {
            TagValueOutput out = TagValueOutput.createWithContext(reporter, level.registryAccess());
            glyph.anchor().saveWithoutId(out);
            saved = out.buildResult();
        }
        helper.assertTrue(saved.contains("fabric:attachments"),
                "the attachment is written into the entity save");

        Entity fresh = EntityTypes.INTERACTION.create(level, EntitySpawnReason.COMMAND);
        try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(fresh.problemPath(), PocketDungeonsMod.LOG)) {
            fresh.load(TagValueInput.create(reporter, level.registryAccess(), saved));
        }
        Glyph restored = Glyph.of(fresh);
        helper.assertTrue(restored != null, "a reloaded anchor still reads as a glyph");
        helper.assertValueEqual(GlyphHandlers.REQUIRE_ITEM, restored.handler(),
                "the handler id survives the round trip");
        helper.assertValueEqual("minecraft:diamond", restored.data().getStringOr("item", ""),
                "the saved data survives the round trip");
        glyph.remove(level);
        helper.succeed();
    }

    /** An unknown handler id refuses the click and never throws. */
    @GameTest(maxTicks = 20)
    public void anUnknownHandlerIsRefused(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        ServerLevel level = helper.getLevel();
        Glyph glyph = Glyph.spawn(level, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)).add(AT),
                0.8f, 1.6f, Identifier.fromNamespaceAndPath("pocketdungeons", "no_such_handler"),
                new CompoundTag(), List.of());
        click(player, level, glyph, ItemStack.EMPTY);
        helper.assertTrue(glyph.anchor().isAlive(), "the glyph stands after an unknown handler");
        glyph.remove(level);
        helper.succeed();
    }

    /** The cycle and toggle handlers read and write the run's shared glyphState. */
    @GameTest(maxTicks = 20)
    public void cycleAndToggleWriteTheRunState(GameTestHelper helper) {
        ServerPlayer player = standInALoadedChunk(helper);
        MinecraftServer server = player.level().getServer();
        ServerLevel level = helper.getLevel();
        InstanceRecord record = floorRecord(helper, server, 9916, player);

        CompoundTag cycleData = new CompoundTag();
        cycleData.putString("key", "tab");
        ListTag values = new ListTag();
        values.add(StringTag.valueOf("objectives"));
        values.add(StringTag.valueOf("progress"));
        values.add(StringTag.valueOf("history"));
        cycleData.put("values", values);
        Glyph cycler = Glyph.spawn(level, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)).add(AT),
                0.8f, 1.6f, GlyphHandlers.CYCLE, cycleData, List.of());
        click(player, level, cycler, record, ItemStack.EMPTY);
        helper.assertValueEqual("objectives", record.glyphState.get("tab"),
                "an unset key starts at the first value");
        click(player, level, cycler, record, ItemStack.EMPTY);
        helper.assertValueEqual("progress", record.glyphState.get("tab"), "click cycles forward");
        clickSneaking(player, level, cycler, record, ItemStack.EMPTY);
        helper.assertValueEqual("objectives", record.glyphState.get("tab"), "sneak cycles back");
        cycler.remove(level);

        CompoundTag toggleData = new CompoundTag();
        toggleData.putString("key", "lamp");
        Glyph toggler = Glyph.spawn(level, Vec3.atCenterOf(helper.absolutePos(BlockPos.ZERO)).add(AT),
                0.8f, 1.6f, GlyphHandlers.TOGGLE, toggleData, List.of());
        click(player, level, toggler, record, ItemStack.EMPTY);
        helper.assertValueEqual("on", record.glyphState.get("lamp"), "toggle turns the key on");
        click(player, level, toggler, record, ItemStack.EMPTY);
        helper.assertValueEqual("off", record.glyphState.get("lamp"), "toggle turns the key off");
        toggler.remove(level);
        helper.succeed();
    }

    // ---- scaffolding ---------------------------------------------------------------------------

    private static Glyph requireItemGlyph(ServerLevel level, Vec3 at, ItemStack wants) {
        CompoundTag data = new CompoundTag();
        data.putString("item", net.minecraft.core.registries.BuiltInRegistries.ITEM
                .getKey(wants.getItem()).toString());
        data.putInt("count", 1);
        data.putString("label", wants.getHoverName().getString());
        return Glyph.itemGlyph(level, at, wants, wants.getHoverName(),
                GlyphHandlers.REQUIRE_ITEM, data, List.of());
    }

    /** Runs the dispatcher the same way a server-side right-click does. */
    private static void click(ServerPlayer player, ServerLevel level, Glyph glyph, ItemStack held) {
        GlyphHandlers.dispatch(new GlyphContext(player, level, held, false, null, glyph,
                glyph.data().copy()));
    }

    private static void click(ServerPlayer player, ServerLevel level, Glyph glyph,
                              InstanceRecord record, ItemStack held) {
        GlyphHandlers.dispatch(new GlyphContext(player, level, held, false, record, glyph,
                glyph.data().copy()));
    }

    private static void clickSneaking(ServerPlayer player, ServerLevel level, Glyph glyph,
                                      InstanceRecord record, ItemStack held) {
        GlyphHandlers.dispatch(new GlyphContext(player, level, held, true, record, glyph,
                glyph.data().copy()));
    }

    private static InstanceRecord floorRecord(GameTestHelper helper, MinecraftServer server, int slot,
                                              ServerPlayer owner) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        PlanGeometry geometry = PlanGeometry.of(origin, List.of(new PlanCell(0, 0)));
        InstanceLayout layout = new InstanceLayout(origin, geometry, origin.offset(3, 1, 3), 0.0f,
                origin.offset(8, 1, 8), geometry.bounds(), 1L, 4, 4, 1, true, java.util.Set.of(),
                3, origin, 0, 0, java.util.Set.of(), null, java.util.Set.of());
        return new InstanceRecord(slot, origin, server.getTickCount(), layout,
                java.util.Set.of(), owner.getUUID(), false);
    }

    private static ServerPlayer standInALoadedChunk(GameTestHelper helper) {
        @SuppressWarnings("removal")
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        BlockPos where = helper.absolutePos(new BlockPos(1, 2, 1));
        player.teleportTo(helper.getLevel(), where.getX() + 0.5, where.getY(), where.getZ() + 0.5,
                java.util.Set.of(), 0.0F, 0.0F, false);
        return player;
    }
}
