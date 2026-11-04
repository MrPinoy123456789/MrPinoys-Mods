package foundry.gametest;

import eu.pb4.trinkets.api.TrinketsApi;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/** Worn items: the while_worn trigger through a Trinkets slot, the race lock on its effects, and the wings' glider parts. */
public final class WornGameTest {
    private static Item item(String id) {
        return BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
    }

    @SuppressWarnings("removal")
    private static ServerPlayer player(GameTestHelper helper, String node) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.setGameMode(GameType.SURVIVAL);
        var at = helper.absoluteVec(new net.minecraft.world.phys.Vec3(2.5, 2, 2.5));
        player.snapTo(at.x, at.y, at.z, 0f, 0f);
        if (node != null) TestPerms.grant(player, node);
        return player;
    }

    private static void wear(ServerPlayer player, String slot, ItemStack stack) {
        var attachment = TrinketsApi.getAttachment(player);
        var access = attachment.getSlotAccess(slot, 0, false);
        if (access == null) {
            throw new AssertionError("No slot " + slot + "; the player has " + attachment.getInventories().keySet() + " in groups "
                    + attachment.getGroups().keySet());
        }
        if (!access.set(stack)) throw new AssertionError("The slot " + slot + " did not take " + stack);
    }

    /** A race shows its look on the player, and losing the race removes it. */
    @GameTest(maxTicks = 100)
    public void aRaceLookIsDrawnAndRemovedWithTheRace(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, "race:azrak");
        TestPerms.grant(wearer, "race:azrak");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 1, "A Dwarf has no pauldron");
            TestPerms.revoke(wearer, "race:azrak");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 0, "The pauldron stayed after the race was taken away");
                helper.succeed();
            });
        });
    }

    /** Two races (two race nodes, as a test) draw all their looks (the Fae have two, the Humans one); dying removes them. */
    @GameTest(maxTicks = 120)
    public void displaysFollowTheWearerThroughDeath(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        TestPerms.grant(wearer, "race:faeloria");
        TestPerms.grant(wearer, "race:aurelia");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 3, "A Fae and a Human together should show three looks");
            wearer.setHealth(0f);
            helper.assertFalse(wearer.isAlive(), "The wearer is still alive after their health went to zero");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 0, "The displays stayed after the wearer died");
                helper.succeed();
            });
        });
    }

    /**
     * The wearer does not see their own trinkets unless they ask (/trinketview), because the game draws anything attached
     * to them in first person too. With it on they get their own copies, which nobody else receives.
     */
    @GameTest(maxTicks = 100)
    public void theWearerSeesTheirOwnTrinketsOnlyWhenTheyAsk(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        ServerPlayer observer = player(helper, null);
        TestPerms.grant(wearer, "race:aurelia");
        TestPerms.grant(wearer, "race:azurius");
        var me = wearer.getUUID();
        helper.runAfterDelay(25, () -> {
            var hidden = foundry.mc.WornDisplays.watchers(me);
            // the banner, the Merfolk fin ears (a head item) and the dorsal fin
            helper.assertTrue(hidden.size() == 3 && hidden.stream().noneMatch(w -> w.contains(me)),
                    "By default no display should reach its wearer: " + hidden);
            foundry.mc.WornDisplays.setSelfView(me, true);
            foundry.mc.WornDisplays.refresh(wearer);
            foundry.mc.WornDisplays.startTracking(me, observer);
            var shown = foundry.mc.WornDisplays.watchers(me);
            long own = shown.stream().filter(w -> w.contains(me)).count();
            long forObserver = shown.stream().filter(w -> w.contains(observer.getUUID())).count();
            foundry.mc.WornDisplays.setSelfView(me, false);
            // the head item never gets a wearer's copy as a display (the wearer sees it in their own helmet slot)
            helper.assertTrue(shown.size() == 5, "Expected three copies for others and two for the wearer, found " + shown);
            helper.assertTrue(own == 2, "Both of the wearer's own copies should reach them, but " + own + " do: " + shown);
            helper.assertTrue(forObserver == 3, "The observer should get only the three copies for others, but gets " + forObserver);
            helper.assertTrue(shown.stream().noneMatch(w -> w.contains(me) && w.contains(observer.getUUID())),
                    "No display may go to both the wearer and someone else: " + shown);
            helper.succeed();
        });
    }

    /** A viewer that Polymer starts tracking receives the display once; the wearer does not get it. */
    @GameTest(maxTicks = 120)
    public void anObserverReceivesTheDisplays(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        ServerPlayer observer = player(helper, null);
        TestPerms.grant(wearer, "race:azrak");
        helper.runAfterDelay(25, () -> {
            foundry.mc.WornDisplays.startTracking(wearer.getUUID(), observer);
            foundry.mc.WornDisplays.startTracking(wearer.getUUID(), observer);
            var watchers = foundry.mc.WornDisplays.watchers(wearer.getUUID());
            helper.assertTrue(watchers.size() == 1, "Expected one display but found " + watchers.size());
            helper.assertTrue(watchers.get(0).contains(observer.getUUID()), "The observer is not watching the pauldron: " + watchers);
            helper.assertTrue(!watchers.get(0).contains(wearer.getUUID()), "The wearer got their own pauldron by default: " + watchers);
            helper.assertTrue(watchers.get(0).size() == 1, "The pauldron is sent more than once: " + watchers);
            helper.succeed();
        });
    }

    private static org.joml.Vector3f front(org.joml.Matrix4f m) {
        // a model's front (its north face) shows toward the display's -z
        return m.transformDirection(new org.joml.Vector3f(0, 0, -1)).normalize();
    }

    /** A back model is built mirrored front to back, so its outer face is its +z. */
    private static org.joml.Vector3f outward(org.joml.Matrix4f m) {
        return m.transformDirection(new org.joml.Vector3f(0, 0, 1)).normalize();
    }

    private static org.joml.Vector3f flat(org.joml.Vector3f v) {
        return new org.joml.Vector3f(v.x, 0, v.z).normalize();
    }

    private static boolean near(org.joml.Vector3f v, double x, double y, double z) {
        return Math.abs(v.x - x) < 0.05 && Math.abs(v.y - y) < 0.05 && Math.abs(v.z - z) < 0.05;
    }

    /**
     * Head items follow the head and body items follow the body: with the body turned west (yaw 90) and the head looking
     * south (yaw 0), the gills face south and the cape's outer face points east (behind a body facing west).
     */
    @GameTest(maxTicks = 20)
    public void headItemsFollowTheHeadAndBodyItemsFollowTheBody(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        wearer.yBodyRot = 90f;
        wearer.setYHeadRot(0f);
        wearer.setXRot(0f);
        var gills = foundry.core.WornPlacement.of("head/face").orElseThrow();
        var cape = foundry.core.WornPlacement.of("chest/cape").orElseThrow();
        var g = front(foundry.mc.WornDisplays.transformFor(wearer, gills));
        // the cape hangs tilted (vanilla's 6 degrees at rest), so only its horizontal facing is compared
        var c = flat(outward(foundry.mc.WornDisplays.transformFor(wearer, cape)));
        helper.assertTrue(near(g, 0, 0, 1), "The gills should face where the head looks (south) but face " + g);
        helper.assertTrue(near(c, 1, 0, 0), "The cape should face out of the back of a body turned west (east) but faces " + c);
        wearer.yBodyRot = 0f;
        var c2 = flat(outward(foundry.mc.WornDisplays.transformFor(wearer, cape)));
        helper.assertTrue(near(c2, 0, 0, -1), "Turning the body south should turn the cape to face north but it faces " + c2);
        helper.succeed();
    }

    /**
     * Left and right are never swapped: a back model's +x (the pauldron's lames, the right wing) stays on the player's
     * right. Facing south, the player's right is west (-x); facing east, it is south (+z).
     */
    @GameTest(maxTicks = 20)
    public void backItemsKeepLeftAndRight(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        var pauldron = foundry.core.WornPlacement.of("chest/back").orElseThrow();
        wearer.yBodyRot = 0f;
        var right = flat(foundry.mc.WornDisplays.transformFor(wearer, pauldron).transformDirection(new org.joml.Vector3f(1, 0, 0)));
        helper.assertTrue(near(right, -1, 0, 0), "Facing south, the pauldron's outward side should point west but points " + right);
        wearer.yBodyRot = -90f;
        var right2 = flat(foundry.mc.WornDisplays.transformFor(wearer, pauldron).transformDirection(new org.joml.Vector3f(1, 0, 0)));
        helper.assertTrue(near(right2, 0, 0, 1), "Facing east, the pauldron's outward side should point south but points " + right2);
        var at = foundry.mc.WornDisplays.transformFor(wearer, pauldron).getTranslation(new org.joml.Vector3f());
        helper.assertTrue(at.z > 0.2f, "Facing east, the pauldron should sit on the right (south) shoulder but sits at " + at);
        helper.succeed();
    }

    /** A head item tips with the head's pitch: facing west and looking 30 degrees down, the gills face west and down. */
    @GameTest(maxTicks = 20)
    public void aHeadItemTipsWithTheHeadPitch(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        wearer.yBodyRot = 90f;
        wearer.setYHeadRot(90f);
        wearer.setXRot(30f);
        var g = front(foundry.mc.WornDisplays.transformFor(wearer, foundry.core.WornPlacement.of("head/face").orElseThrow()));
        helper.assertTrue(near(g, -Math.cos(Math.toRadians(30)), -Math.sin(Math.toRadians(30)), 0), "The gills face " + g);
        helper.succeed();
    }

    /** Placements are measured from the feet; crouching lowers a body item and leans it forward, as vanilla's model does. */
    @GameTest(maxTicks = 20)
    public void crouchingLowersAndLeansABodyItem(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        wearer.yBodyRot = 0f;
        var cape = foundry.core.WornPlacement.of("chest/cape").orElseThrow();
        var flat = new foundry.core.WornPlacement(cape.up(), cape.back(), cape.side(), cape.scale(), false, 0f, 0f, 0f, false, false, false, false);
        var standing = foundry.mc.WornDisplays.transformFor(wearer, flat).getTranslation(new org.joml.Vector3f());
        helper.assertTrue(Math.abs(standing.y - cape.up()) < 0.01, "A standing body item should sit at its up height but sits at " + standing.y);
        wearer.setPose(net.minecraft.world.entity.Pose.CROUCHING);
        var crouched = foundry.mc.WornDisplays.transformFor(wearer, flat);
        var at = crouched.getTranslation(new org.joml.Vector3f());
        var up = crouched.transformDirection(new org.joml.Vector3f(0, 1, 0)).normalize();
        helper.assertTrue(at.y < standing.y - 0.05, "Crouching did not lower the cape: " + standing.y + " to " + at.y);
        helper.assertTrue(up.z > 0.3, "Crouching did not lean the cape forward (facing south, its up should tip south): " + up);
        helper.succeed();
    }

    /**
     * The cape hangs from the top of the back and swings back as it trails: with more tilt its bottom edge moves further
     * behind the player and rises. Facing south, "behind" is north (negative z).
     */
    @GameTest(maxTicks = 20)
    public void theCapeHangsFromTheBackAndSwingsBack(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        var cape = foundry.core.WornPlacement.of("chest/cape").orElseThrow();
        helper.assertTrue(cape.swing(), "The cape slot does not swing");
        var still = foundry.mc.WornDisplays.transform(wearer, cape, 0f, 6f);
        var trailing = foundry.mc.WornDisplays.transform(wearer, cape, 0f, 60f);
        // the cape's middle, in the display's own space, is the origin; its bottom edge is a quarter block below (8 model units at the fixed display scale)
        var bottomStill = still.transformPosition(new org.joml.Vector3f(0, -0.25f, 0));
        var bottomTrailing = trailing.transformPosition(new org.joml.Vector3f(0, -0.25f, 0));
        helper.assertTrue(bottomTrailing.z < bottomStill.z - 0.2f, "A trailing cape's hem did not move behind the player: "
                + bottomStill + " to " + bottomTrailing);
        helper.assertTrue(bottomTrailing.y > bottomStill.y + 0.1f, "A trailing cape's hem did not rise: " + bottomStill + " to " + bottomTrailing);
        helper.succeed();
    }

    /**
     * The antlers are served on the head to everyone, the wearer included: alone with no helmet, and on a copy of the
     * player's own helmet when one is worn (equipment asset removed, item model "antlers on iron"), while the real
     * helmet stays exactly as it was. Every served copy is marked.
     */
    @GameTest(maxTicks = 80)
    public void theAntlersAreServedOnTheHeadWithOrWithoutAHelmet(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        TestPerms.grant(wearer, "race:faeloria");
        helper.runAfterDelay(25, () -> {
            var alone = foundry.mc.WornDisplays.headItemFor(wearer.getUUID());
            helper.assertTrue(alone != null && alone.is(item("kingdoms:antler_circlet")) && foundry.mc.HeadVisual.isMarked(alone),
                    "With no helmet the marked antlers should be served on the head, got " + alone);
            helper.assertTrue(foundry.mc.WornDisplays.showsOwnHelmet(wearer.getUUID()), "The wearer is not shown the antlers on their own head");
            helper.assertTrue(wearer.getItemBySlot(EquipmentSlot.HEAD).isEmpty(), "The server's own helmet slot was changed");
            ItemStack iron = new ItemStack(net.minecraft.world.item.Items.IRON_HELMET);
            wearer.setItemSlot(EquipmentSlot.HEAD, iron);
            helper.runAfterDelay(5, () -> {
                var combined = foundry.mc.WornDisplays.headItemFor(wearer.getUUID());
                helper.assertTrue(combined != null && combined.is(net.minecraft.world.item.Items.IRON_HELMET),
                        "With an iron helmet on, a copy of it should be served, got " + combined);
                helper.assertTrue(!combined.has(net.minecraft.core.component.DataComponents.EQUIPPABLE)
                        || combined.get(net.minecraft.core.component.DataComponents.EQUIPPABLE) == null,
                        "The served copy still has its equipment asset, so it would be drawn as plain armour");
                helper.assertTrue(String.valueOf(combined.get(net.minecraft.core.component.DataComponents.ITEM_MODEL)).equals("kingdoms:antler_circlet_on_iron"),
                        "The served copy has the wrong model: " + combined.get(net.minecraft.core.component.DataComponents.ITEM_MODEL));
                helper.assertTrue(foundry.mc.HeadVisual.isMarked(combined), "The served copy is not marked");
                var real = wearer.getItemBySlot(EquipmentSlot.HEAD);
                helper.assertTrue(real.has(net.minecraft.core.component.DataComponents.EQUIPPABLE) && !foundry.mc.HeadVisual.isMarked(real),
                        "The real helmet was changed");
                helper.succeed();
            });
        });
    }

    /** An invisible player's displays shrink to nothing. */
    @GameTest(maxTicks = 20)
    public void anInvisiblePlayersItemsAreHidden(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        wearer.setInvisible(true);
        var m = foundry.mc.WornDisplays.transformFor(wearer, foundry.core.WornPlacement.of("chest/cape").orElseThrow());
        helper.assertTrue(m.getScale(new org.joml.Vector3f()).length() < 0.001, "An invisible player's cape still shows");
        helper.succeed();
    }

    /** The antlers go to other players on the head; under a head item that is not a helmet (a pumpkin) they become a display. */
    @GameTest(maxTicks = 160)
    public void theAntlersFallBackToADisplayUnderAPumpkin(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        ServerPlayer observer = player(helper, null);
        TestPerms.grant(wearer, "race:faeloria");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue("head_slot".equals(foundry.mc.WornDisplays.modeOf(wearer.getUUID(), 0)), "The antlers are not served on the head");
            foundry.mc.WornDisplays.startTracking(wearer.getUUID(), observer);
            var watchers = foundry.mc.WornDisplays.watchers(wearer.getUUID()).get(0);
            helper.assertTrue(watchers.contains(observer.getUUID()) && !watchers.contains(wearer.getUUID()),
                    "The head item packet should go to the observer (the wearer gets theirs in their own slot): " + watchers);
            wearer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN));
            helper.runAfterDelay(5, () -> {
                helper.assertTrue("display".equals(foundry.mc.WornDisplays.modeOf(wearer.getUUID(), 0)),
                        "Under a pumpkin the antlers should fall back to a display");
                helper.assertFalse(foundry.mc.WornDisplays.showsOwnHelmet(wearer.getUUID()), "The pumpkin was replaced on the wearer's own head");
                wearer.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
                helper.runAfterDelay(5, () -> {
                    helper.assertTrue("head_slot".equals(foundry.mc.WornDisplays.modeOf(wearer.getUUID(), 0)),
                            "Taking the pumpkin off should serve the antlers on the head again");
                    helper.succeed();
                });
            });
        });
    }

    /** In creative the client is trusted with item stacks, so a served copy sent back must be refused, and a real item not. */
    @GameTest(maxTicks = 20)
    public void creativeCannotTurnAServedCopyIntoARealItem(GameTestHelper helper) {
        ServerPlayer p = player(helper, null);
        p.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        ItemStack copy = new ItemStack(item("kingdoms:antler_circlet"));
        net.minecraft.world.item.component.CustomData.update(net.minecraft.core.component.DataComponents.CUSTOM_DATA, copy,
                tag -> tag.putBoolean(foundry.mc.HeadVisual.MARK, true));
        p.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36, copy));
        helper.assertTrue(p.getInventory().getItem(0).isEmpty(), "A marked copy became a real item in creative");
        p.connection.handleSetCreativeModeSlot(new net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket(36,
                new ItemStack(net.minecraft.world.item.Items.STONE)));
        helper.assertTrue(p.getInventory().getItem(0).is(net.minecraft.world.item.Items.STONE), "A normal creative item was refused");
        helper.succeed();
    }

    /**
     * Bedrock gliding needs an elytra in the chest slot on the Bedrock side, so a Bedrock Fairy wearing the wings with an
     * empty chest slot is shown a stand in elytra; never a Java player, never with a chestplate on, never when dead.
     * The chest slot index is the inventory screen's (5 head, 6 chest).
     */
    @GameTest(maxTicks = 60)
    public void onlyBedrockWingWearersAreShownAStandInElytra(GameTestHelper helper) {
        helper.assertTrue(foundry.mc.Wings.standInWanted(true, true, true, true), "A Bedrock Fairy with an empty chest gets no stand in");
        helper.assertFalse(foundry.mc.Wings.standInWanted(false, true, true, true), "A Java player would get a stand in");
        helper.assertFalse(foundry.mc.Wings.standInWanted(true, true, false, true), "A Bedrock player in a chestplate would get a stand in");
        helper.assertFalse(foundry.mc.Wings.standInWanted(true, false, true, true), "A Bedrock player without allowed wings would get a stand in");
        helper.assertFalse(foundry.mc.Wings.standInWanted(true, true, true, false), "A dead Bedrock player would get a stand in");
        ServerPlayer fairy = player(helper, "race:everdelle");
        TestPerms.grant(fairy, "race:everdelle");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.Wings.isWinged(fairy.getUUID()), "The Java Fairy is not winged");
            helper.assertFalse(foundry.mc.Wings.showsStandIn(fairy.getUUID()), "A Java Fairy was sent a stand in elytra");
            fairy.setItemSlot(EquipmentSlot.CHEST, new ItemStack(net.minecraft.world.item.Items.IRON_CHESTPLATE));
            boolean chestIsSix = fairy.inventoryMenu.getSlot(6).getItem().is(net.minecraft.world.item.Items.IRON_CHESTPLATE);
            fairy.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            helper.assertTrue(chestIsSix, "Slot 6 of the inventory screen is not the chest slot");
            helper.succeed();
        });
    }

    /**
     * The client item definition Foundry builds for a worn item selects the 3D model for every display context the
     * game can send (all but none), so no context falls through to the flat fallback. (The client's own codecs cannot
     * be loaded on a server, so this checks the context names against the game's enum instead.)
     */
    @GameTest(maxTicks = 20)
    public void theWornSelectCoversEveryDisplayContext(GameTestHelper helper) throws Exception {
        var generated = foundry.core.PackFiles.generate(foundry.mc.FoundryMod.loaded());
        var assets = java.nio.file.Path.of(System.getProperty("foundry.assets"));
        int checked = 0;
        for (var def : foundry.mc.FoundryMod.loaded().items().values()) {
            if (def.wornModel() == null) continue;
            String json = generated.get(foundry.core.PackFiles.clientItemPath(def.id()));
            helper.assertTrue(json != null, def.id() + " has no client item definition");
            var cases = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("model")
                    .getAsJsonArray("cases").get(0).getAsJsonObject().getAsJsonArray("when");
            var covered = new java.util.HashSet<String>();
            cases.forEach(e -> covered.add(e.getAsString()));
            for (var context : net.minecraft.world.item.ItemDisplayContext.values()) {
                if (context == net.minecraft.world.item.ItemDisplayContext.NONE) continue;
                helper.assertTrue(covered.contains(context.getSerializedName()),
                        def.id() + " select does not cover the " + context.getSerializedName() + " context");
            }
            helper.assertTrue(java.nio.file.Files.isRegularFile(assets.resolve(foundry.core.PackFiles.modelPath(def.wornModel()))),
                    def.id() + " worn model file is missing");
            checked++;
        }
        helper.assertTrue(checked == 11, "Expected eleven items with a worn model but checked " + checked);
        helper.succeed();
    }

    /** What a viewer's client receives for a worn item: the base item with item_model naming the Foundry item, so the pack's select applies. */
    @GameTest(maxTicks = 20)
    public void theClientIsSentTheItemModelThePackSelectsOn(GameTestHelper helper) {
        ServerPlayer viewer = player(helper, null);
        for (String id : new String[] {"forgeman_pauldron", "kingdom_cape", "antler_circlet", "tidal_gills", "fairy_wings", "dwarf_beard", "aurelia_banner", "fae_wings", "merfolk_fins", "dorsal_fin", "bombadil_cap"}) {
            String seen = foundry.mc.WornDisplays.clientView(viewer, new ItemStack(item("kingdoms:" + id)));
            foundry.mc.FoundryMod.LOG.info("client stack for kingdoms:{}: {}", id, seen);
            helper.assertTrue(seen.contains("item_model=kingdoms:" + id), "The client does not get item_model kingdoms:" + id + ": " + seen);
        }
        helper.succeed();
    }

    /** A player with no race shows no look at all. */
    @GameTest(maxTicks = 40)
    public void aPlayerOfNoRaceShowsNothing(GameTestHelper helper) {
        ServerPlayer nobody = player(helper, null);
        nobody.getInventory().add(new ItemStack(item("kingdoms:kingdom_cape")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(nobody.getUUID()) == 0, "A player of no race shows a look");
            helper.succeed();
        });
    }

    /** The Fairies glide by race, with nothing in the chest slot; nobody else can. */
    @GameTest(maxTicks = 60)
    public void fairiesGlideByRace(GameTestHelper helper) {
        ServerPlayer fairy = player(helper, "race:everdelle");
        ServerPlayer other = player(helper, null);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.Wings.isWinged(fairy.getUUID()) && !foundry.mc.Wings.isWinged(other.getUUID()),
                    "Only the Fairy should be able to glide");
            fairy.setOnGround(false);
            other.setOnGround(false);
            helper.assertTrue(fairy.getItemBySlot(EquipmentSlot.CHEST).isEmpty(), "The Fairy has something in the chest slot");
            helper.assertTrue(fairy.tryToStartFallFlying(), "A Fairy with an empty chest slot cannot start gliding");
            helper.assertFalse(other.tryToStartFallFlying(), "A player of no race started gliding");
            helper.succeed();
        });
    }

    /**
     * A Fairy gliding by race reaches vanilla's once a second glider wear tick with no glider equipped; vanilla picked a
     * random glider slot from an empty list there ("Bound must be positive") and the player was kicked. The tick must
     * pass with the Fairy still gliding and nothing thrown, and with a real elytra on, vanilla's wear still applies.
     */
    @GameTest(maxTicks = 60)
    public void aFairyGlidingByRaceSurvivesTheGliderWearTick(GameTestHelper helper) {
        ServerPlayer fairy = player(helper, "race:everdelle");
        helper.runAfterDelay(25, () -> {
            fairy.setOnGround(false);
            helper.assertTrue(fairy.tryToStartFallFlying(), "The Fairy could not start gliding");
            helper.assertTrue(foundry.mc.Wings.glidesByRaceOnly(fairy), "A Fairy with nothing equipped should glide by race only");
            try {
                var ticks = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("fallFlyTicks");
                ticks.setAccessible(true);
                for (int wear : new int[] {19, 39}) {
                    ticks.setInt(fairy, wear);
                    fairy.setOnGround(false);
                    fairy.aiStep();
                }
            } catch (ReflectiveOperationException e) {
                throw new AssertionError("Cannot reach fallFlyTicks: " + e);
            }
            helper.assertTrue(fairy.isFallFlying(), "The Fairy stopped gliding at the wear tick");
            ItemStack elytra = new ItemStack(net.minecraft.world.item.Items.ELYTRA);
            fairy.setItemSlot(EquipmentSlot.CHEST, elytra);
            helper.assertFalse(foundry.mc.Wings.glidesByRaceOnly(fairy), "With a real elytra on, vanilla's glide tick should run");
            fairy.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            helper.succeed();
        });
    }

    /** The trinket items are retired: they cannot go in a trinket slot, and anything left in one is cleared. */
    @GameTest(maxTicks = 60)
    public void trinketsAreLockedAway(GameTestHelper helper) {
        ServerPlayer p = player(helper, null);
        var access = TrinketsApi.getAttachment(p).getSlotAccess("chest/back", 0, false);
        helper.assertTrue(access != null, "The player has no chest/back slot to test with");
        boolean placed = access.set(new ItemStack(item("kingdoms:forgeman_pauldron")));
        helper.runAfterDelay(25, () -> {
            var after = TrinketsApi.getAttachment(p).getSlotAccess("chest/back", 0, false);
            helper.assertTrue(!placed || after.get().isEmpty(), "A retired trinket stayed in its slot");
            helper.assertFalse(new ItemStack(item("kingdoms:forgeman_pauldron")).has(
                    eu.pb4.trinkets.api.component.TrinketDataComponents.EQUIPMENT), "A retired trinket can still be equipped");
            helper.succeed();
        });
    }

    /** A Dwarf wears the beard on the head, and with an iron helmet on gets the helmet with the beard below it. */
    @GameTest(maxTicks = 60)
    public void dwarvesWearTheBeardWithOrWithoutAHelmet(GameTestHelper helper) {
        ServerPlayer dwarf = player(helper, "race:azrak");
        helper.runAfterDelay(25, () -> {
            var alone = foundry.mc.WornDisplays.headItemFor(dwarf.getUUID());
            helper.assertTrue(alone != null && alone.is(item("kingdoms:dwarf_beard")), "A Dwarf has no beard on the head: " + alone);
            dwarf.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.IRON_HELMET));
            helper.runAfterDelay(5, () -> {
                var combined = foundry.mc.WornDisplays.headItemFor(dwarf.getUUID());
                helper.assertTrue(combined != null && String.valueOf(combined.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))
                        .equals("kingdoms:dwarf_beard_on_iron"), "With an iron helmet the Dwarf should get the beard on iron: " + combined);
                helper.succeed();
            });
        });
    }

    /** A Human carries the war banner on the back: one display that rides the player, standing above the head. */
    @GameTest(maxTicks = 60)
    public void humansCarryTheWarBanner(GameTestHelper helper) {
        ServerPlayer human = player(helper, "race:aurelia");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(human.getUUID()) == 1, "A Human shows no banner");
            helper.assertTrue("display".equals(foundry.mc.WornDisplays.modeOf(human.getUUID(), 0)), "The banner is not a display");
            var place = foundry.core.WornPlacement.of("chest/banner").orElseThrow();
            // the finial (model y 32, 24 units above the model's middle) stands above the head (about 1.9 high)
            float top = place.up() + 24f / 16f * 0.5f * place.scale();
            helper.assertTrue(top > 2.0f, "The banner's top should stand above the head but is at " + top);
            helper.succeed();
        });
    }

    /**
     * The Fae are not Fairies: antlers in a wreath on the head and leaf wings that flap on the back, and no gliding; they
     * jump twice instead, and the second jump lifts them and clears their fall.
     */
    @GameTest(maxTicks = 60)
    public void theFaeWearLeafWingsAndDoubleJumpInsteadOfGliding(GameTestHelper helper) {
        ServerPlayer fae = player(helper, "race:faeloria");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(fae.getUUID()) == 2, "The Fae should show the antlers and the leaf wings");
            helper.assertTrue("head_slot".equals(foundry.mc.WornDisplays.modeOf(fae.getUUID(), 0)), "The antlers are not on the head");
            helper.assertFalse(foundry.mc.Wings.isWinged(fae.getUUID()), "The Fae can glide like a Fairy");
            fae.setOnGround(false);
            helper.assertFalse(fae.tryToStartFallFlying(), "A Fae started gliding");
            helper.assertTrue(foundry.mc.DoubleJump.cooldownOf(fae.getUUID()) == foundry.core.WornPlacement.FAE_DOUBLE_JUMP,
                    "The Fae has no double jump: " + foundry.mc.DoubleJump.cooldownOf(fae.getUUID()));
            fae.fallDistance = 6;
            foundry.mc.DoubleJump.hop(fae, foundry.core.AirJump.push(0f, true, false, false, false));
            helper.assertTrue(fae.getDeltaMovement().y > 0.5 && fae.getDeltaMovement().z > 0.3, "The hop did not lift the Fae forward: "
                    + fae.getDeltaMovement());
            helper.assertTrue(fae.fallDistance == 0, "The hop left the fall distance at " + fae.fallDistance);
            helper.assertFalse(foundry.mc.DoubleJump.learned(fae.getUUID()), "A new Fae already knows the double jump");
            foundry.mc.DoubleJump.learn(fae.getUUID());
            helper.assertTrue(foundry.mc.DoubleJump.learned(fae.getUUID()), "A Fae who double jumped is still taught it");
            helper.succeed();
        });
    }

    /**
     * The Merfolk wear fin ears on the head (on a helmet's sides when one is worn) and a dorsal fin on the back, and dash
     * through the water: the dash throws them the way they look, fast, and only a Merfolk has it.
     */
    @GameTest(maxTicks = 60)
    public void theMerfolkWearFinsAndDashThroughTheWater(GameTestHelper helper) {
        ServerPlayer mer = player(helper, "race:azurius");
        ServerPlayer fae = player(helper, "race:faeloria");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(mer.getUUID()) == 2, "The Merfolk should show the fin ears and the dorsal fin");
            var head = foundry.mc.WornDisplays.headItemFor(mer.getUUID());
            helper.assertTrue(head != null && head.is(item("kingdoms:merfolk_fins")), "The fin ears are not on the head: " + head);
            helper.assertTrue(foundry.mc.SwimDash.cooldownOf(mer.getUUID()) == foundry.core.WornPlacement.MERFOLK_SWIM_DASH,
                    "The Merfolk has no swim dash");
            helper.assertTrue(foundry.mc.SwimDash.cooldownOf(fae.getUUID()) == 0, "A Fae can swim dash");
            mer.setXRot(0f);
            mer.setYRot(0f);
            foundry.mc.SwimDash.dash(mer);
            helper.assertTrue(mer.getDeltaMovement().z > 0.9, "The dash did not throw the Merfolk the way they look: " + mer.getDeltaMovement());
            mer.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.TURTLE_HELMET));
            helper.runAfterDelay(5, () -> {
                var combined = foundry.mc.WornDisplays.headItemFor(mer.getUUID());
                helper.assertTrue(combined != null && String.valueOf(combined.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))
                        .equals("kingdoms:merfolk_fins_on_turtle_scute"), "A turtle shell should get the fins on its sides: " + combined);
                helper.succeed();
            });
        });
    }

    /**
     * The folk of Mossbeard's Muddle wear the old blue hat on the head, served to everyone as a head item, and with a
     * vanilla helmet on, the helmet with the hat on top of it. They glide, jump and swim like anyone else.
     */
    @GameTest(maxTicks = 60)
    public void mossbeardsMuddleWearsTheFeatheredHat(GameTestHelper helper) {
        ServerPlayer muddle = player(helper, "race:mossbeard_s_muddle");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(muddle.getUUID()) == 1, "The hat is not shown");
            helper.assertTrue("head_slot".equals(foundry.mc.WornDisplays.modeOf(muddle.getUUID(), 0)), "The hat is not served on the head");
            var alone = foundry.mc.WornDisplays.headItemFor(muddle.getUUID());
            helper.assertTrue(alone != null && alone.is(item("kingdoms:bombadil_cap")) && foundry.mc.HeadVisual.isMarked(alone),
                    "With no helmet the marked hat should be on the head: " + alone);
            helper.assertFalse(foundry.mc.Wings.isWinged(muddle.getUUID()), "The hat's wearer can glide");
            helper.assertTrue(foundry.mc.DoubleJump.cooldownOf(muddle.getUUID()) == 0 && foundry.mc.SwimDash.cooldownOf(muddle.getUUID()) == 0,
                    "The hat's wearer has another race's ability");
            muddle.setItemSlot(EquipmentSlot.HEAD, new ItemStack(net.minecraft.world.item.Items.GOLDEN_HELMET));
            helper.runAfterDelay(5, () -> {
                var combined = foundry.mc.WornDisplays.headItemFor(muddle.getUUID());
                helper.assertTrue(combined != null && String.valueOf(combined.get(net.minecraft.core.component.DataComponents.ITEM_MODEL))
                        .equals("kingdoms:bombadil_cap_on_gold"), "A gold helmet should get the hat on top: " + combined);
                helper.succeed();
            });
        });
    }

    /**
     * Bedrock players see the race looks in the skin (BedrockLooks): every scan records which looks a player wears that
     * the jar has Bedrock bones for, and taking the race away clears it. The jar's looks load (no Geyser here).
     */
    @GameTest(maxTicks = 100)
    public void bedrockSkinsKnowEachPlayersLook(GameTestHelper helper) {
        ServerPlayer dwarf = player(helper, "race:azrak");
        ServerPlayer mer = player(helper, "race:azurius");
        helper.assertTrue(foundry.mc.BedrockLooks.pieces().size() == 4, "The jar's Bedrock looks did not load: "
                + foundry.mc.BedrockLooks.pieces().keySet());
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.BedrockLooks.worn(dwarf.getUUID()).equals(java.util.List.of("kingdoms:dwarf_beard")),
                    "A Dwarf's Bedrock look is not the beard: " + foundry.mc.BedrockLooks.worn(dwarf.getUUID()));
            helper.assertTrue(foundry.mc.BedrockLooks.worn(mer.getUUID()).equals(java.util.List.of("kingdoms:merfolk_fins")),
                    "A Merfolk's Bedrock look should be the fin ears: " + foundry.mc.BedrockLooks.worn(mer.getUUID()));
            TestPerms.revoke(dwarf, "race:azrak");
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(foundry.mc.BedrockLooks.worn(dwarf.getUUID()).isEmpty(),
                        "The beard stayed in the Bedrock skin after the race went");
                helper.succeed();
            });
        });
    }

    /**
     * The Fae fly by wing dash: a burst the way they look, then a slow fall that is the effect's own (short, hidden) and
     * never a potion's; a mob's hit costs a charge, poison does not.
     */
    @GameTest(maxTicks = 120)
    public void theFaeWingDashBurstsAndFallsSlowly(GameTestHelper helper) {
        ServerPlayer fae = player(helper, "race:faeloria");
        helper.runAfterDelay(25, () -> {
            var d = foundry.core.RaceTraits.Dash.DEFAULT;
            var state = foundry.mc.DoubleJump.dashOf(fae.getUUID());
            helper.assertTrue(state != null, "The Fae has no wing dash");
            fae.setYRot(0f);
            fae.setXRot(0f);
            fae.setDeltaMovement(0, -0.3, 0);
            fae.fallDistance = 6;
            helper.assertFalse(foundry.mc.DoubleJump.overCeiling(fae, d.ceiling()), "The floor below is not found");
            foundry.mc.DoubleJump.burst(fae, d);
            var v = fae.getDeltaMovement();
            helper.assertTrue(Math.abs(v.z - d.forward()) < 1e-6 && Math.abs(v.y - d.up() / 2) < 1e-6 && fae.fallDistance == 0,
                    "A level burst south should push forward and lift: " + v);
            foundry.mc.DoubleJump.slowFall(fae, true);
            var effect = fae.getEffect(MobEffects.SLOW_FALLING);
            helper.assertTrue(effect != null && !effect.showIcon() && !effect.isVisible() && effect.getDuration() <= 12,
                    "The slow fall should be a short hidden Slow Falling: " + effect);
            foundry.mc.DoubleJump.slowFall(fae, false);
            helper.assertTrue(fae.getEffect(MobEffects.SLOW_FALLING) == null, "The slow fall did not end");
            foundry.mc.DoubleJump.slowFall(fae, true);
            fae.addEffect(new net.minecraft.world.effect.MobEffectInstance(MobEffects.SLOW_FALLING, 600));
            foundry.mc.DoubleJump.slowFall(fae, false);
            helper.assertTrue(fae.getEffect(MobEffects.SLOW_FALLING) != null && fae.getEffect(MobEffects.SLOW_FALLING).getDuration() > 500,
                    "A potion's Slow Falling was taken off");
        });
        // a new player cannot be hurt for their first 60 ticks, so the hits wait
        helper.runAfterDelay(70, () -> {
            var d = foundry.core.RaceTraits.Dash.DEFAULT;
            var state = foundry.mc.DoubleJump.dashOf(fae.getUUID());
            long now = helper.getLevel().getServer().getTickCount();
            state.tick(false, false, false, now, d);
            int before = state.charges();
            foundry.mc.DoubleJump.hurt(fae, helper.getLevel().damageSources().magic());
            helper.assertTrue(state.charges() == before, "Damage with no attacker took a charge");
            var zombie = helper.spawn(net.minecraft.world.entity.EntityTypes.ZOMBIE, 4, 2, 4);
            fae.hurtServer(helper.getLevel(), helper.getLevel().damageSources().mobAttack(zombie), 1f);
            helper.assertTrue(state.charges() == before - 1, "A mob's hit did not take a charge: " + state.charges() + " of " + before);
            zombie.discard();
            helper.succeed();
        });
    }

    /**
     * A Fae with a mace in hand folds the wings for a dive: a burst looking straight up steers without climbing and keeps
     * the fall so far (so the smash counts the whole drop); without the mace the same burst lifts and resets the fall.
     */
    @GameTest(maxTicks = 60)
    public void aFaeWithAMaceDivesInsteadOfClimbing(GameTestHelper helper) {
        ServerPlayer fae = player(helper, "race:faeloria");
        helper.runAfterDelay(25, () -> {
            var d = foundry.core.RaceTraits.Dash.DEFAULT;
            helper.assertFalse(foundry.mc.DoubleJump.diving(fae), "An empty hand counts as a mace");
            fae.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.MACE));
            helper.assertTrue(foundry.mc.DoubleJump.diving(fae), "A mace in hand does not fold the wings");
            fae.setYRot(0f);
            fae.setXRot(-90f);
            fae.setDeltaMovement(0, -0.6, 0);
            fae.fallDistance = 8;
            foundry.mc.DoubleJump.burst(fae, d);
            var v = fae.getDeltaMovement();
            helper.assertTrue(v.y <= -0.6 + 1e-6 && fae.fallDistance == 8,
                    "Diving with a mace a burst climbed or broke the fall: " + v + ", fall " + fae.fallDistance);
            fae.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
            helper.assertFalse(foundry.mc.DoubleJump.diving(fae), "A sword counts as a mace");
            fae.setDeltaMovement(0, -0.6, 0);
            foundry.mc.DoubleJump.burst(fae, d);
            helper.assertTrue(Math.abs(fae.getDeltaMovement().y - d.up()) < 1e-6 && fae.fallDistance == 0,
                    "Without the mace a burst looking up should lift and reset the fall: " + fae.getDeltaMovement());
            helper.succeed();
        });
    }

    /** Only a race with a double jump gets one. */
    @GameTest(maxTicks = 40)
    public void onlyTheFaeDoubleJump(GameTestHelper helper) {
        ServerPlayer fairy = player(helper, "race:everdelle");
        ServerPlayer nobody = player(helper, null);
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.DoubleJump.cooldownOf(fairy.getUUID()) == 0, "A Fairy can double jump");
            helper.assertTrue(foundry.mc.DoubleJump.cooldownOf(nobody.getUUID()) == 0, "A player of no race can double jump");
            helper.succeed();
        });
    }

    /** A Fairy's wings are two halves that flap: two displays, hinged at the spine, turning opposite ways. */
    @GameTest(maxTicks = 60)
    public void fairyWingsFlapInTwoHalves(GameTestHelper helper) {
        ServerPlayer fairy = player(helper, "race:everdelle");
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.watchers(fairy.getUUID()).size() == 2, "The wings are not two displays");
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(fairy.getUUID()) == 1, "The two halves count as more than one look");
            var body = foundry.mc.WornDisplays.body(fairy);
            float left = foundry.mc.WornDisplays.hinge(fairy, body, -1), right = foundry.mc.WornDisplays.hinge(fairy, body, 1);
            helper.assertTrue(Math.abs(left + right) < 0.001f && left != 0f, "The halves should turn opposite ways: " + left + ", " + right);
            helper.assertTrue(foundry.mc.WornDisplays.hinge(fairy, body, 0) == 0f, "Something that is not a wing turned");
            helper.succeed();
        });
    }
}
