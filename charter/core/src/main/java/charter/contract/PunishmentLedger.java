package charter.contract;

import charter.config.Knobs;
import charter.id.ContractId;
import charter.id.OrgId;
import charter.id.PlayerId;
import charter.time.Durations;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Who has been punished, by whom and for how long, so nobody can be chain-jailed
 * (DESIGN.md §15.5). Two rules read it: the weekly cap per player across every
 * faction, and the cooldown before the same faction may sentence the same player
 * again. Confinement, bonds and respawn delays count toward the weekly total;
 * banishment and fines do not. The week is a rolling seven days from each sentence.
 *
 * <p>An entry counts what was granted until the sentence ends early (a pardon, an
 * escape, a fallen keep), when it counts only what was served.
 */
public final class PunishmentLedger {

    public static final long WEEK = Durations.days(7);

    /** One sentence. {@code counted} is the weekly total it uses, in milliseconds. */
    public static final class Entry {
        public final ContractId contract;
        public final PlayerId player;
        /** The sentencing faction (the root of the sentencing organization). */
        public final OrgId faction;
        public final long at;
        private long counted;

        Entry(ContractId contract, PlayerId player, OrgId faction, long at, long counted) {
            this.contract = contract;
            this.player = player;
            this.faction = faction;
            this.at = at;
            this.counted = counted;
        }

        public long counted() {
            return counted;
        }
    }

    private final List<Entry> entries = new ArrayList<>();

    public List<Entry> entries() {
        return Collections.unmodifiableList(entries);
    }

    public void record(ContractId contract, PlayerId player, OrgId faction, long at, long counted) {
        entries.add(new Entry(contract, player, faction, at, Math.max(0, counted)));
    }

    /** The player's punishment counted in the week up to {@code now}. */
    public long counted(PlayerId player, long now) {
        long total = 0;
        for (Entry entry : entries) {
            if (entry.player.equals(player) && entry.at > now - WEEK) total += entry.counted;
        }
        return total;
    }

    /** How much more confinement, bond and respawn delay the player may be given this week. */
    public long remaining(PlayerId player, long now, Knobs knobs) {
        return Math.max(0, Durations.hours(knobs.weeklyPunishmentCapHours) - counted(player, now));
    }

    /** True if this faction sentenced this player within the cooldown. */
    public boolean coolingDown(OrgId faction, PlayerId player, long now, Knobs knobs) {
        long since = now - Durations.days(knobs.sameFactionSamePlayerCooldownDays);
        for (Entry entry : entries) {
            if (entry.player.equals(player) && entry.faction.equals(faction) && entry.at > since) return true;
        }
        return false;
    }

    /** A sentence ended early: it counts only what was served. */
    public void served(ContractId contract, long servedMillis) {
        for (Entry entry : entries) {
            if (entry.contract.equals(contract)) entry.counted = Math.min(entry.counted, Math.max(0, servedMillis));
        }
    }
}
