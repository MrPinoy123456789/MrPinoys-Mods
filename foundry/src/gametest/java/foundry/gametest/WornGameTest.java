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

    @GameTest(maxTicks = 100)
    public void tidalGillsHelpOnlyMerfolk(GameTestHelper helper) {
        ServerPlayer merfolk = player(helper, "race:azurius");
        ServerPlayer other = player(helper, null);
        wear(merfolk, "head/face", new ItemStack(item("kingdoms:tidal_gills")));
        wear(other, "head/face", new ItemStack(item("kingdoms:tidal_gills")));
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(merfolk.hasEffect(MobEffects.WATER_BREATHING), "Merfolk wearing the gills have no water breathing");
            helper.assertTrue(merfolk.hasEffect(MobEffects.DOLPHINS_GRACE), "Merfolk wearing the gills have no dolphin's grace");
            helper.assertFalse(other.hasEffect(MobEffects.WATER_BREATHING), "A player of no race got water breathing from the gills");
            helper.assertFalse(other.hasEffect(MobEffects.DOLPHINS_GRACE), "A player of no race got dolphin's grace from the gills");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void gillsOnTheShelfDoNothing(GameTestHelper helper) {
        ServerPlayer merfolk = player(helper, "race:azurius");
        merfolk.getInventory().add(new ItemStack(item("kingdoms:tidal_gills")));
        helper.runAfterDelay(45, () -> {
            helper.assertFalse(merfolk.hasEffect(MobEffects.WATER_BREATHING), "Gills in the pack gave water breathing");
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 20)
    public void fairyWingsAreAnElytra(GameTestHelper helper) {
        ItemStack wings = new ItemStack(item("kingdoms:fairy_wings"));
        helper.assertTrue(wings.has(DataComponents.GLIDER), "The wings cannot glide");
        helper.assertTrue(wings.has(DataComponents.EQUIPPABLE)
                && wings.get(DataComponents.EQUIPPABLE).slot() == EquipmentSlot.CHEST, "The wings do not go in the chest slot");
        helper.succeed();
    }

    /** Wears one trinket as its race and as a player with no race; only the race gets the effect. */
    private static void raceOnly(GameTestHelper helper, String item, String slot, String node,
            net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) {
        ServerPlayer race = player(helper, node);
        ServerPlayer other = player(helper, null);
        wear(race, slot, new ItemStack(item(item)));
        wear(other, slot, new ItemStack(item(item)));
        helper.runAfterDelay(45, () -> {
            helper.assertTrue(race.hasEffect(effect), "The wearer of the right race got no effect from " + item);
            helper.assertFalse(other.hasEffect(effect), "A player of no race got the effect from " + item);
            helper.succeed();
        });
    }

    @GameTest(maxTicks = 100)
    public void theForgemansPauldronHastensOnlyDwarves(GameTestHelper helper) {
        raceOnly(helper, "kingdoms:forgeman_pauldron", "chest/back", "race:azrak", MobEffects.HASTE);
    }

    @GameTest(maxTicks = 100)
    public void theKingdomCapeBringsLuckOnlyToHumans(GameTestHelper helper) {
        raceOnly(helper, "kingdoms:kingdom_cape", "chest/cape", "race:aurelia", MobEffects.LUCK);
    }

    @GameTest(maxTicks = 100)
    public void theAntlerCircletGivesNightVisionOnlyToTheFae(GameTestHelper helper) {
        raceOnly(helper, "kingdoms:antler_circlet", "head/hat", "race:faeloria", MobEffects.NIGHT_VISION);
    }

    /** A worn trinket gets a display on the player, and taking it off removes the display. */
    @GameTest(maxTicks = 100)
    public void aWornTrinketIsDrawnAndRemovedOnUnequip(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, "race:azrak");
        wear(wearer, "chest/back", new ItemStack(item("kingdoms:forgeman_pauldron")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 1, "The pauldron has no display on its wearer");
            TrinketsApi.getAttachment(wearer).getSlotAccess("chest/back", 0, false).set(ItemStack.EMPTY);
            helper.runAfterDelay(25, () -> {
                helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 0, "The display stayed after the pauldron came off");
                helper.succeed();
            });
        });
    }

    /** The display is not tied to the race lock (it is decoration), and a trinket in the pack draws nothing. */
    @GameTest(maxTicks = 100)
    public void aTrinketInThePackIsNotDrawn(GameTestHelper helper) {
        ServerPlayer carrier = player(helper, null);
        carrier.getInventory().add(new ItemStack(item("kingdoms:kingdom_cape")));
        ServerPlayer wearer = player(helper, null);
        wear(wearer, "chest/cape", new ItemStack(item("kingdoms:kingdom_cape")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(carrier.getUUID()) == 0, "A cape in the pack was drawn");
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 1, "A worn cape was not drawn for a player of no race");
            helper.succeed();
        });
    }

    /** Wearing two trinkets draws two displays; dying removes them. */
    @GameTest(maxTicks = 120)
    public void displaysFollowTheWearerThroughDeath(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        wear(wearer, "head/hat", new ItemStack(item("kingdoms:antler_circlet")));
        wear(wearer, "chest/cape", new ItemStack(item("kingdoms:kingdom_cape")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.WornDisplays.shownFor(wearer.getUUID()) == 2, "Two worn trinkets did not make two displays");
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
        wear(wearer, "chest/cape", new ItemStack(item("kingdoms:kingdom_cape")));
        wear(wearer, "head/face", new ItemStack(item("kingdoms:tidal_gills")));
        var me = wearer.getUUID();
        helper.runAfterDelay(25, () -> {
            var hidden = foundry.mc.WornDisplays.watchers(me);
            helper.assertTrue(hidden.size() == 2 && hidden.stream().noneMatch(w -> w.contains(me)),
                    "By default no display should reach its wearer: " + hidden);
            foundry.mc.WornDisplays.setSelfView(me, true);
            foundry.mc.WornDisplays.refresh(wearer);
            foundry.mc.WornDisplays.startTracking(me, observer);
            var shown = foundry.mc.WornDisplays.watchers(me);
            long own = shown.stream().filter(w -> w.contains(me)).count();
            long forObserver = shown.stream().filter(w -> w.contains(observer.getUUID())).count();
            foundry.mc.WornDisplays.setSelfView(me, false);
            helper.assertTrue(shown.size() == 4, "Expected two copies for others and two for the wearer, found " + shown);
            helper.assertTrue(own == 2, "Both of the wearer's own copies should reach them, but " + own + " do: " + shown);
            helper.assertTrue(forObserver == 2, "The observer should get only the two copies for others, but gets " + forObserver);
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
        wear(wearer, "chest/back", new ItemStack(item("kingdoms:forgeman_pauldron")));
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
        wear(wearer, "head/hat", new ItemStack(item("kingdoms:antler_circlet")));
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
        wear(wearer, "head/hat", new ItemStack(item("kingdoms:antler_circlet")));
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

    /** Wings worn in the cape slot let a Fairy glide, and a player of no race is marked as wearing wings they cannot use. */
    @GameTest(maxTicks = 100)
    public void wingsInTheCapeSlotAreForFairies(GameTestHelper helper) {
        ServerPlayer fairy = player(helper, "race:everdelle");
        ServerPlayer other = player(helper, null);
        wear(fairy, "chest/cape", new ItemStack(item("kingdoms:fairy_wings")));
        wear(other, "chest/cape", new ItemStack(item("kingdoms:fairy_wings")));
        helper.runAfterDelay(25, () -> {
            helper.assertTrue(foundry.mc.Wings.isWinged(fairy.getUUID()) && !foundry.mc.Wings.isDenied(fairy.getUUID()),
                    "A Fairy wearing the wings is not marked as winged");
            helper.assertTrue(foundry.mc.Wings.isDenied(other.getUUID()) && !foundry.mc.Wings.isWinged(other.getUUID()),
                    "A player of no race is not marked as wearing wings they cannot use");
            fairy.setOnGround(false);
            helper.assertTrue(fairy.tryToStartFallFlying(), "A Fairy wearing the wings in the cape slot cannot start gliding");
            helper.succeed();
        });
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
        wear(fairy, "chest/cape", new ItemStack(item("kingdoms:fairy_wings")));
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

    /** The cape and the wings both fit the cape slot at once (the slot has room for two). */
    @GameTest(maxTicks = 60)
    public void theCapeAndTheWingsShareTheCapeSlot(GameTestHelper helper) {
        ServerPlayer wearer = player(helper, null);
        var attachment = TrinketsApi.getAttachment(wearer);
        var first = attachment.getSlotAccess("chest/cape", 0, false);
        var second = attachment.getSlotAccess("chest/cape", 1, false);
        helper.assertTrue(first != null && second != null, "The cape slot has no second place");
        helper.assertTrue(first.set(new ItemStack(item("kingdoms:kingdom_cape"))), "The first cape slot refused the cape");
        helper.assertTrue(second.set(new ItemStack(item("kingdoms:fairy_wings"))), "The second cape slot refused the wings");
        helper.succeed();
    }

    /** The wings never wear out and still have the elytra's parts. */
    @GameTest(maxTicks = 20)
    public void theWingsNeverWearOut(GameTestHelper helper) {
        ItemStack wings = new ItemStack(item("kingdoms:fairy_wings"));
        helper.assertTrue(wings.getMaxDamage() == 0, "The wings still have durability");
        helper.assertTrue(wings.has(DataComponents.GLIDER), "The wings cannot glide");
        helper.succeed();
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
        helper.assertTrue(checked == 5, "Expected five worn items with a worn model but checked " + checked);
        helper.succeed();
    }

    /** What a viewer's client receives for a worn item: the base item with item_model naming the Foundry item, so the pack's select applies. */
    @GameTest(maxTicks = 20)
    public void theClientIsSentTheItemModelThePackSelectsOn(GameTestHelper helper) {
        ServerPlayer viewer = player(helper, null);
        for (String id : new String[] {"forgeman_pauldron", "kingdom_cape", "antler_circlet", "tidal_gills", "fairy_wings"}) {
            String seen = foundry.mc.WornDisplays.clientView(viewer, new ItemStack(item("kingdoms:" + id)));
            foundry.mc.FoundryMod.LOG.info("client stack for kingdoms:{}: {}", id, seen);
            helper.assertTrue(seen.contains("item_model=kingdoms:" + id), "The client does not get item_model kingdoms:" + id + ": " + seen);
        }
        helper.succeed();
    }

    /** MEASUREMENT (temporary): what the server knows about yaw when a player turns with real rotation packets. */
    @GameTest(maxTicks = 80)
    public void measureTurning(GameTestHelper helper) {
        ServerPlayer p = player(helper, null);
        int[] tick = {0};
        String[] log = {""};
        for (int t = 1; t <= 60; t++) {
            final int at = t;
            helper.runAfterDelay(t, () -> {
                if (at == 5) p.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(90f, 0f, true, false));
                if (at == 30) p.connection.handleMovePlayer(new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(200f, 20f, true, false));
                if (at % 3 == 0 || at == 5 || at == 6 || at == 30 || at == 31)
                    foundry.mc.FoundryMod.LOG.info("MEASURE t={} yRot={} yHeadRot={} yBodyRot={} xRot={}", at, p.getYRot(), p.getYHeadRot(), p.yBodyRot, p.getXRot());
                if (at == 60) helper.succeed();
            });
        }
    }
}
