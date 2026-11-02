package charter.gametest;

import charter.contract.Justice;
import charter.engine.Actor;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.mc.book.AdminPages;
import charter.mc.book.BookButton;
import charter.mc.book.BookDialogs;
import charter.mc.Text;
import charter.mc.moderation.LedgerGrants;
import charter.mc.moderation.LordModeration;
import charter.moderation.ModerationEvents;
import charter.org.actions.AddMember;
import charter.time.Durations;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.Commands;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Lords moderating their own citizens through the typed commands, and nobody else; operators beyond their reach; and
 * the operators' one hour Ledger grant, run against a stand in {@code lp} command that records what it was asked.
 */
public final class LordModerationGameTest {

    private static PlayerId id(ServerPlayer p) {
        return new PlayerId(p.getUUID());
    }

    private static void as(ServerPlayer player, String command) {
        player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), command);
    }

    private static void console(LandKit kit, String command) {
        kit.server.getCommands().performPrefixedCommand(kit.server.createCommandSourceStack(), command);
    }

    private static ServerPlayer citizen(LandKit kit, OrgId org) {
        ServerPlayer p = kit.outsider();
        kit.rt.state.touch(id(p), p.getPlainTextName(), kit.rt.clock.now());
        kit.rt.engine.run(new Actor.Admin("gametest"), new AddMember(org, id(p)));
        return p;
    }

    private static long mutedUntil(LandKit kit, ServerPlayer p) {
        return kit.rt.state.player(id(p)).mutedUntil;
    }

    @GameTest(maxTicks = 200)
    public void aLordMutesTheirOwnCitizenAndNobodyElse(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Mute");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = citizen(kit, org);
            ServerPlayer dee = kit.faction("Other");
            ServerPlayer eve = citizen(kit, kit.orgOf(dee));
            ServerPlayer loner = kit.outsider();
            kit.rt.state.touch(id(loner), loner.getPlainTextName(), kit.rt.clock.now());

            as(ann, "charter lord mute " + bob.getUUID() + " 10m spamming the chat");
            long now = kit.rt.clock.now();
            helper.assertTrue(mutedUntil(kit, bob) > now + Durations.minutes(9), "The Lord's mute did not take");
            helper.assertTrue(!LordModeration.mayTalk(bob), "A muted citizen may still talk");
            helper.assertTrue(LordModeration.mayTalk(ann), "The Lord was silenced too");

            as(ann, "charter lord mute " + eve.getUUID() + " 10m rude words");
            as(ann, "charter lord mute " + dee.getUUID() + " 10m rude words");
            as(ann, "charter lord mute " + loner.getUUID() + " 10m rude words");
            helper.assertTrue(mutedUntil(kit, eve) == 0 && mutedUntil(kit, dee) == 0 && mutedUntil(kit, loner) == 0,
                    "A Lord muted someone outside their kingdom");
            as(bob, "charter lord mute " + ann.getUUID() + " 10m revenge");
            helper.assertTrue(mutedUntil(kit, ann) == 0, "A citizen muted their Lord");

            ServerPlayer cy = citizen(kit, org);
            as(ann, "charter lord mute " + cy.getUUID() + " 2h too long");
            helper.assertTrue(mutedUntil(kit, cy) == 0, "A mute over the hour cap took");
            as(ann, "charter lord mute " + cy.getUUID() + " 10m");
            helper.assertTrue(mutedUntil(kit, cy) == 0, "A mute with no reason took");

            as(ann, "charter lord unmute " + bob.getUUID());
            helper.assertTrue(LordModeration.mayTalk(bob), "Unmute did not let the citizen talk");
            long journaled = kit.rt.state.history.of(ModerationEvents.Muted.class).stream().filter(e -> e.player().equals(id(bob))).count();
            helper.assertTrue(journaled == 1, "The mute was journaled " + journaled + " times");
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void operatorsAreBeyondALord(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Reach");
            OrgId org = kit.orgOf(ann);
            ServerPlayer staff = citizen(kit, org);
            kit.server.getPlayerList().op(staff.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            try {
                as(ann, "charter lord mute " + staff.getUUID() + " 10m rude words");
                as(ann, "charter lord suspend " + staff.getUUID() + " 1h rude words");
                helper.assertTrue(mutedUntil(kit, staff) == 0 && kit.rt.state.player(id(staff)).suspendedUntil == 0,
                        "A Lord moderated an operator");
            } finally {
                kit.server.getPlayerList().deop(staff.nameAndId());
            }

            // Muted first, made an operator after: the mute is dropped on their next word.
            as(ann, "charter lord mute " + staff.getUUID() + " 10m rude words");
            helper.assertTrue(mutedUntil(kit, staff) > 0, "setup: the citizen was not muted");
            kit.server.getPlayerList().op(staff.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            try {
                helper.assertTrue(LordModeration.mayTalk(staff), "An operator was held by an old mute");
                helper.assertTrue(mutedUntil(kit, staff) == 0, "The old mute was not dropped");
            } finally {
                kit.server.getPlayerList().deop(staff.nameAndId());
            }
            helper.succeed();
        }
    }

    @GameTest(maxTicks = 200)
    public void aSuspendedCitizenIsKeptOffTheLandAndStaysAMember(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Bar");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = citizen(kit, org);
            as(ann, "charter lord suspend " + bob.getUUID() + " 13h griefing the farm");
            helper.assertTrue(kit.rt.state.player(id(bob)).suspendedUntil == 0, "A suspension over the 12 hour cap took");
            as(ann, "charter lord suspend " + bob.getUUID() + " 2h griefing the farm");
            long now = kit.rt.clock.now();
            helper.assertTrue(Justice.banished(kit.rt.state, kit.rt.knobs, id(bob), org, now), "The suspension does not bar the land");
            helper.assertTrue(kit.rt.state.isMember(id(bob), org), "The suspension took the citizen out of the kingdom");
            as(ann, "charter lord unsuspend " + bob.getUUID());
            helper.assertTrue(!Justice.banished(kit.rt.state, kit.rt.knobs, id(bob), org, kit.rt.clock.now()), "Unsuspend did not lift the bar");
            helper.succeed();
        }
    }

    /** What the stand in {@code lp} command was asked, in order. */
    private static final List<String> LP = new CopyOnWriteArrayList<>();

    @GameTest(maxTicks = 200)
    public void theLedgerGrantIsReadOnlyOneHourAndOnlyForALord(GameTestHelper helper) {
        try (LandKit kit = new LandKit(helper)) {
            ServerPlayer ann = kit.faction("Ledger");
            OrgId org = kit.orgOf(ann);
            ServerPlayer bob = citizen(kit, org);

            if (!LedgerGrants.luckPermsPresent(kit.server)) {
                console(kit, "charter admin ledger grant " + ann.getUUID());
                helper.assertTrue(kit.rt.state.player(id(ann)).ledgerUntil == 0, "A grant was recorded with no LuckPerms to give it");
                kit.server.getCommands().getDispatcher().register(Commands.literal("lp")
                        .then(Commands.argument("rest", StringArgumentType.greedyString()).executes(c -> {
                            LP.add("lp " + StringArgumentType.getString(c, "rest"));
                            return 1;
                        })));
            }
            LP.clear();

            console(kit, "charter admin ledger grant " + bob.getUUID());
            helper.assertTrue(LP.isEmpty() && kit.rt.state.player(id(bob)).ledgerUntil == 0, "A citizen who is not a Lord was given Ledger");

            long before = kit.rt.clock.now();
            console(kit, "charter admin ledger grant " + ann.getUUID());
            helper.assertTrue(LP.equals(LedgerGrants.grantCommands(ann.getUUID())), "The grant ran " + LP);
            for (String line : LP) {
                helper.assertTrue(line.endsWith(" true 1h replace"), "Not one hour, replaced: " + line);
                for (String banned : List.of("rollback", "restore", "preview", "teleport", "tp", "player", "purge")) {
                    helper.assertTrue(!line.contains("ledger.commands." + banned), "A non read node was granted: " + line);
                }
            }
            long until = kit.rt.state.player(id(ann)).ledgerUntil;
            helper.assertTrue(until >= before + Durations.HOUR && until <= kit.rt.clock.now() + Durations.HOUR, "The expiry is not one hour out");
            helper.assertTrue(kit.rt.state.history.entries().stream()
                    .anyMatch(e -> e.event() instanceof ModerationEvents.LedgerGranted && e.admin()), "The grant was not journaled as an admin event");

            LP.clear();
            console(kit, "charter admin ledger grant " + ann.getUUID());
            helper.assertTrue(LP.isEmpty() && kit.rt.state.player(id(ann)).ledgerUntil == until, "A standing grant was extended");

            // The Admin Book offers to take it back on the Lord's page.
            ServerPlayer op = kit.outsider();
            kit.server.getPlayerList().op(op.nameAndId(), java.util.Optional.of(
                    net.minecraft.server.permissions.LevelBasedPermissionSet.GAMEMASTER), java.util.Optional.empty());
            try {
                AdminPages.playerPage(op, ann.getUUID());
                List<String> labels = BookDialogs.LAST.get(op.getUUID()).buttons().stream().map(BookButton::label).toList();
                helper.assertTrue(labels.contains(Text.get("admin.player.button.ledger_revoke")), "No take back button: " + labels);
            } finally {
                kit.server.getPlayerList().deop(op.nameAndId());
            }

            // No longer a Lord: the sweep takes it back at once.
            console(kit, "charter admin faction leader \"" + kit.rt.describe.org(org) + "\" " + bob.getUUID());
            helper.assertTrue(!charter.moderation.LordRules.isLord(kit.rt.state, id(ann)), "setup: Ann is still a Lord");
            LP.clear();
            LedgerGrants.sweep(kit.server);
            helper.assertTrue(kit.rt.state.player(id(ann)).ledgerUntil == 0, "A former Lord kept Ledger access");
            helper.assertTrue(LP.equals(LedgerGrants.revokeCommands(ann.getUUID())), "The take back ran " + LP);
            helper.succeed();
        }
    }
}
