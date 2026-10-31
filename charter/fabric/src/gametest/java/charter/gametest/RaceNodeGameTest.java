package charter.gametest;

import charter.mc.RaceNodes;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.fabric.api.permission.v1.PermissionContextOwner;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

/** Kingdom membership as the permission node race:<kingdom>, read the way another mod reads it. */
public final class RaceNodeGameTest {

    private static boolean holds(ServerPlayer player, String kingdomName) {
        return ((PermissionContextOwner) player).checkPermission(RaceNodes.nodeOf(kingdomName), false);
    }

    @GameTest(maxTicks = 100)
    public void aMemberHoldsTheirKingdomsNodeAndNoOthers(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer elf = kit.faction("Elf");
            ServerPlayer stranger = kit.outsider();
            String dwarfKingdom = kit.rt.state.org(kit.orgOf(dwarf)).orElseThrow().name();
            String elfKingdom = kit.rt.state.org(kit.orgOf(elf)).orElseThrow().name();
            helper.assertTrue(holds(dwarf, dwarfKingdom), "a member holds their own kingdom's node");
            helper.assertTrue(!holds(dwarf, elfKingdom), "and not another kingdom's");
            helper.assertTrue(holds(elf, elfKingdom) && !holds(elf, dwarfKingdom), "each kingdom has its own people");
            helper.assertTrue(!holds(stranger, dwarfKingdom), "someone in no kingdom holds none");
            helper.assertTrue(!((PermissionContextOwner) dwarf).checkPermission(
                    net.minecraft.resources.Identifier.parse("other:" + RaceNodes.slug(dwarfKingdom)), false),
                    "only the race namespace is answered");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aMercenaryHoldsTheRaceTheyChoseWithoutJoining(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer elf = kit.faction("Elf");
            ServerPlayer merc = kit.outsider();
            charter.id.OrgId dwarfOrg = kit.orgOf(dwarf);
            String dwarfKingdom = kit.rt.state.org(dwarfOrg).orElseThrow().name();
            String elfKingdom = kit.rt.state.org(kit.orgOf(elf)).orElseThrow().name();
            charter.id.PlayerId id = new charter.id.PlayerId(merc.getUUID());
            helper.assertTrue(!holds(merc, dwarfKingdom), "before choosing, no race");
            var outcome = kit.rt.engine.run(new charter.engine.Actor.Player(id), new charter.org.actions.ChooseMercenaryRace(id, dwarfOrg));
            helper.assertTrue(outcome.done(), "the choice is accepted");
            helper.assertTrue(holds(merc, dwarfKingdom), "a mercenary holds the chosen race");
            helper.assertTrue(!holds(merc, elfKingdom), "and no other");
            helper.assertTrue(kit.rt.state.membership(id).isEmpty(), "while belonging to no kingdom");
            var again = kit.rt.engine.run(new charter.engine.Actor.Player(id),
                    new charter.org.actions.ChooseMercenaryRace(id, kit.orgOf(elf)));
            helper.assertTrue(again.refused() && holds(merc, dwarfKingdom), "and it cannot be changed");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aMemberKeepsTheirRaceAfterLeaving(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer elf = kit.faction("Elf");
            charter.id.PlayerId id = new charter.id.PlayerId(dwarf.getUUID());
            charter.id.OrgId dwarfOrg = kit.orgOf(dwarf);
            charter.id.OrgId elfOrg = kit.orgOf(elf);
            String dwarfKingdom = kit.rt.state.org(dwarfOrg).orElseThrow().name();
            kit.rt.engine.run(new charter.engine.Actor.Player(id), new charter.org.actions.Leave(id));
            helper.assertTrue(holds(dwarf, dwarfKingdom), "leaving a kingdom keeps its race");
            kit.rt.state.join(id, elfOrg, kit.rt.clock.now());
            helper.assertTrue(holds(dwarf, kit.rt.state.org(elfOrg).orElseThrow().name()),
                    "joining another kingdom makes the player of its race");
            kit.rt.state.leave(id);
            kit.rt.state.org(dwarfOrg).orElseThrow().mixedRace = true;
            kit.rt.state.join(id, dwarfOrg, kit.rt.clock.now());
            helper.assertTrue(holds(dwarf, kit.rt.state.org(elfOrg).orElseThrow().name()),
                    "but a mixed kingdom lets them keep their race");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aRaceGivesItsPeopleItsStandingEffect(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer elf = kit.faction("Elf");
            ServerPlayer merc = kit.outsider();
            charter.id.OrgId dwarfOrg = kit.orgOf(dwarf);
            charter.id.PlayerId mercId = new charter.id.PlayerId(merc.getUUID());
            kit.rt.knobs.racePassives = java.util.Map.of(RaceNodes.slug(kit.rt.state.org(dwarfOrg).orElseThrow().name()),
                    java.util.List.of("minecraft:water_breathing"));
            charter.mc.RacePassives.applyTo(dwarf, kit.rt);
            charter.mc.RacePassives.applyTo(elf, kit.rt);
            charter.mc.RacePassives.applyTo(merc, kit.rt);
            helper.assertTrue(dwarf.hasEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING), "a member of the race has the effect");
            helper.assertTrue(!elf.hasEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING), "another race does not");
            helper.assertTrue(!merc.hasEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING), "someone with no race does not");
            kit.rt.engine.run(new charter.engine.Actor.Player(mercId), new charter.org.actions.ChooseMercenaryRace(mercId, dwarfOrg));
            charter.mc.RacePassives.applyTo(merc, kit.rt);
            helper.assertTrue(merc.hasEffect(net.minecraft.world.effect.MobEffects.WATER_BREATHING), "a mercenary of the race has it too");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 100)
    public void aPlayerWearsTheirKingdomsTag(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer dwarf = kit.faction("Dwarf");
            ServerPlayer merc = kit.outsider();
            ServerPlayer nobody = kit.outsider();
            charter.id.OrgId dwarfOrg = kit.orgOf(dwarf);
            String kingdom = kit.rt.state.org(dwarfOrg).orElseThrow().name();
            kit.rt.knobs.tagColors = java.util.Map.of(kingdom.toLowerCase(java.util.Locale.ROOT), "#FF69B4", "mercenary", "#8A0303");
            charter.id.PlayerId mercId = new charter.id.PlayerId(merc.getUUID());
            kit.rt.engine.run(new charter.engine.Actor.Player(mercId), new charter.org.actions.ChooseMercenaryRace(mercId, dwarfOrg));
            charter.mc.Tags.applyTo(dwarf, kit.rt);
            charter.mc.Tags.applyTo(merc, kit.rt);
            charter.mc.Tags.applyTo(nobody, kit.rt);
            helper.assertTrue(dwarf.getTeam() != null && dwarf.getTeam().getPlayerPrefix().getString().equals("[" + kingdom + "] "),
                    "a member wears their kingdom's tag: " + (dwarf.getTeam() == null ? "no team" : dwarf.getTeam().getPlayerPrefix().getString()));
            helper.assertTrue(dwarf.getTeam().getPlayerPrefix().getStyle().getColor().getValue() == 0xFF69B4, "in its colour");
            helper.assertTrue(merc.getTeam() != null && merc.getTeam().getPlayerPrefix().getString().equals("[Mercenary] "),
                    "a mercenary wears the Mercenary tag");
            helper.assertTrue(nobody.getTeam() == null, "someone with no race and no kingdom has none");
            helper.succeed();
        }
    }
}
