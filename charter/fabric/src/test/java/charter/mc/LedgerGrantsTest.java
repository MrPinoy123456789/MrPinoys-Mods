package charter.mc;

import charter.mc.moderation.LedgerGrants;
import charter.time.Durations;

import java.util.List;
import java.util.UUID;

import static charter.mc.FabricSuite.check;

/** The LuckPerms lines behind an operator's Ledger grant: the read nodes only, one hour, replaced and never added to. */
final class LedgerGrantsTest {

    private LedgerGrantsTest() {
    }

    static void run() {
        UUID lord = new UUID(7, 9);
        List<String> grant = LedgerGrants.grantCommands(lord);
        check("ledger: exactly the four read nodes", LedgerGrants.NODES.equals(List.of(
                "ledger.commands.root", "ledger.commands.inspect", "ledger.commands.search", "ledger.commands.status")));
        check("ledger: one command per node", grant.size() == LedgerGrants.NODES.size());
        for (int i = 0; i < grant.size(); i++) {
            String expected = "lp user " + lord + " permission settemp " + LedgerGrants.NODES.get(i) + " true 1h replace";
            check("ledger: grant line " + i + " reads " + expected + ", got " + grant.get(i), grant.get(i).equals(expected));
        }
        for (String line : grant) {
            for (String banned : List.of("rollback", "restore", "preview", "teleport", "tp", "player", "purge")) {
                check("ledger: no " + banned + " node in " + line, !line.contains("ledger.commands." + banned));
            }
            check("ledger: by UUID, never a name: " + line, line.startsWith("lp user " + lord + " "));
            check("ledger: never accumulate: " + line, !line.contains("accumulate"));
        }
        check("ledger: the duration is one hour", LedgerGrants.DURATION_MILLIS == Durations.HOUR && LedgerGrants.DURATION_TEXT.equals("1h"));
        List<String> revoke = LedgerGrants.revokeCommands(lord);
        check("ledger: a revoke unsets each node it granted", revoke.size() == 4
                && revoke.get(1).equals("lp user " + lord + " permission unsettemp ledger.commands.inspect"));
        check("ledger: the expiry clock reads in UTC", LedgerGrants.clock(0).equals("00:00 UTC"));
    }
}
