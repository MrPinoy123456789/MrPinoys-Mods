package pocketdungeons;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pre-registration ({@code /dungeon party}), in-run invites, and the roster
 * commands that read and mutate both. Third of {@code Instances}' six M9 C3
 * extractions: self-contained apart from two calls back into {@code Instances}
 * for the run-admission side effects ({@link Instances#admit} and
 * {@link Instances#announce}) that {@link #join} triggers, since a joining
 * companion is stepping into a run this class does not otherwise own any
 * part of.
 */
final class PartyService {

    /** Invitee -> outstanding invitation. */
    private static final Map<UUID, Invite> invites = new HashMap<>();

    /**
     * Companions pre-registered via {@code /dungeon party}, keyed by the
     * leader who will open the dungeon. Consumed (and cleared) the moment that
     * leader runs {@code /dungeon} -- see U3 Stage 5: {@code invite}/{@code join}
     * only work from inside an instance, so this is what lets a party's size be
     * known <em>before</em> stamping, without re-stamping on a later join.
     */
    private static final Map<UUID, Set<UUID>> pendingParty = new HashMap<>();

    /** Staged kicks awaiting confirmation, keyed by leader. One at a time. */
    private static final Map<UUID, PendingKick> pendingKicks = new HashMap<>();

    private PartyService() {}

    private record Invite(UUID leader, int slot, long expiresAtMillis) {}

    /**
     * A staged {@code /dungeon party kick}, waiting on its clickable confirmation.
     *
     * <p>Keyed by the leader and holding the target itself, so the confirm command
     * takes no arguments. That is deliberate: a companion who has logged off still
     * occupies a party slot and still needs removing, and
     * {@code EntityArgument.player()} cannot name someone who is not online.
     * Staging the UUID here sidesteps that entirely.
     *
     * @param target the companion to drop, or {@code null} for "all of them"
     */
    private record PendingKick(UUID target, long expiresAtMillis) {}

    /**
     * Drops every trace of {@code player} from this class's own state: an
     * outstanding invite, a pending-party registration, a staged kick. Called
     * on disconnect, alongside whatever {@code Instances} itself does for the
     * instance the player may have been standing in.
     */
    static void clearFor(UUID player) {
        invites.remove(player);
        pendingParty.remove(player);
        pendingKicks.remove(player);
    }

    // ---- parties ------------------------------------------------------------

    /**
     * Pre-registers a companion for the <em>next</em> dungeon this leader
     * opens (U3 Stage 5). {@code invite}/{@code join} only work once the
     * leader is already inside, which is too late for the difficulty curve --
     * it is computed once, at stamp time, from the party size known then.
     */
    /**
     * @return whether {@code target} was actually pre-registered (PD-38): the
     * command executor's brigadier result now agrees with the refusal
     * messages below, for {@code execute if}.
     */
    static boolean party(ServerPlayer leader, ServerPlayer target) {
        if (InstanceRegistry.hasInstance(leader)) {
            leader.sendSystemMessage(Component.literal(
                    "You are already in a dungeon. Use /dungeon invite instead.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (target.getUUID().equals(leader.getUUID())) {
            leader.sendSystemMessage(Component.literal("You are already coming.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (InstanceRegistry.hasInstance(target)) {
            leader.sendSystemMessage(Component.literal(
                    target.getName().getString() + " is already in a dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (bannedFrom(leader.level().getServer(), leader.getUUID(), target.getUUID())) {
            leader.sendSystemMessage(Component.literal(target.getName().getString() + " is banned.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        Set<UUID> companions = pendingParty.computeIfAbsent(leader.getUUID(), k -> new LinkedHashSet<>());
        if (companions.contains(target.getUUID())) {
            leader.sendSystemMessage(Component.literal(
                    target.getName().getString() + " is already pre-registered.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (1 + companions.size() >= PocketDungeonsConfig.maxPartyMembers()) {
            leader.sendSystemMessage(Component.literal(
                    "Your party is full (" + PocketDungeonsConfig.maxPartyMembers() + " players).")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        companions.add(target.getUUID());
        RoomCompany.forServer(leader.level().getServer()).note(leader.getUUID(), target.getUUID());
        leader.sendSystemMessage(Component.literal(
                target.getName().getString() + " will come with you when you open a dungeon.")
                .withStyle(ChatFormatting.GOLD));
        target.sendSystemMessage(Component.literal(
                leader.getName().getString() + " pre-registered you for their next dungeon. "
                        + "You'll be brought in automatically when they run /dungeon.")
                .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /**
     * Consumes and resolves this leader's pre-registered {@code /dungeon party}
     * companions into online, still-eligible players. Registration is
     * one-shot: whether or not the build below succeeds, the reservation is
     * spent.
     */
    static List<ServerPlayer> resolveParty(MinecraftServer server, ServerPlayer leader) {
        Set<UUID> pending = pendingParty.remove(leader.getUUID());
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        List<ServerPlayer> companions = new ArrayList<>();
        int cap = PocketDungeonsConfig.maxPartyMembers() - 1;
        for (UUID id : pending) {
            if (companions.size() >= cap) {
                break;
            }
            ServerPlayer companion = server.getPlayerList().getPlayer(id);
            if (companion == null || companion.getUUID().equals(leader.getUUID())
                    || InstanceRegistry.hasInstance(companion)) {
                continue;
            }
            companions.add(companion);
        }
        return companions;
    }

    /**
     * Stages {@code /dungeon party kick}, to be confirmed by clicking.
     *
     * @param target the companion to drop, or {@code null} to drop every one
     */
    /** @return whether a kick was actually staged (PD-38). */
    static boolean stageKick(ServerPlayer leader, UUID target, String targetName) {
        Set<UUID> companions = pendingParty.get(leader.getUUID());
        if (companions == null || companions.isEmpty()) {
            leader.sendSystemMessage(Component.literal("You have nobody pre-registered.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (target != null && !companions.contains(target)) {
            leader.sendSystemMessage(Component.literal(
                    targetName + " is not in your party.").withStyle(ChatFormatting.RED));
            return false;
        }

        pendingKicks.put(leader.getUUID(), new PendingKick(target,
                System.currentTimeMillis() + PocketDungeonsConfig.inviteTtlSeconds() * 1000L));

        String what = target == null
                ? "all " + companions.size() + " companion" + (companions.size() == 1 ? "" : "s")
                : targetName;
        // Was a "Remove X?" line plus a [ Confirm ] chat link; it is the same
        // question and the same /dungeon party kickconfirm behind a real yes/no
        // screen now. The leader ran a command a tick ago, so this is the answer
        // to something they just did rather than an unprompted push.
        DialogKit.show(leader, DialogScreens.kickConfirm(what));
        return true;
    }

    /**
     * Executes whatever {@link #stageKick} staged for this leader.
     *
     * @return whether at least one companion was actually removed (PD-38)
     */
    static boolean confirmKick(ServerPlayer leader) {
        PendingKick kick = pendingKicks.remove(leader.getUUID());
        if (kick == null || kick.expiresAtMillis() < System.currentTimeMillis()) {
            leader.sendSystemMessage(Component.literal(
                    "Nothing to confirm. Run /dungeon party kick again.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        Set<UUID> companions = pendingParty.get(leader.getUUID());
        if (companions == null || companions.isEmpty()) {
            leader.sendSystemMessage(Component.literal("You have nobody pre-registered.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        MinecraftServer server = leader.level().getServer();
        int removed;
        if (kick.target() == null) {
            removed = companions.size();
            for (UUID id : new ArrayList<>(companions)) {
                notifyKicked(server, leader, id);
            }
            pendingParty.remove(leader.getUUID());
        } else {
            removed = companions.remove(kick.target()) ? 1 : 0;
            if (removed > 0) {
                notifyKicked(server, leader, kick.target());
            }
            if (companions.isEmpty()) {
                pendingParty.remove(leader.getUUID());
            }
        }

        leader.sendSystemMessage(Component.literal(
                removed == 0 ? "Nobody was removed; your party had already changed."
                        : "Removed " + removed + " from your party.")
                .withStyle(ChatFormatting.GOLD));

        // Back to the roster, rebuilt from what the party is now. There is no
        // history stack in the dialog API -- a screen returns nowhere on its own,
        // so "back to the list" is this call and nothing else. An emptied party
        // gets a plain notice instead of a buttonless list; partyRoster handles
        // that case itself.
        if (server != null) {
            DialogKit.show(leader, DialogScreens.partyRoster(server,
                    partyCompanions(leader.getUUID())));
        }
        return removed > 0;
    }

    /** Whether {@code owner} has banned {@code target} (Manage Party). */
    static boolean bannedFrom(MinecraftServer server, UUID owner, UUID target) {
        return server != null && RoomBans.forServer(server).isBanned(owner, target);
    }

    /**
     * Everyone in {@code owner}\u0027s party right now: the companions they pre-registered at Home plus the
     * members of their live dungeon, the owner left out. Pre-registered first, in the order they were added.
     */
    static List<UUID> currentParty(UUID owner) {
        java.util.LinkedHashSet<UUID> party = new java.util.LinkedHashSet<>(partyCompanions(owner));
        InstanceRecord record = InstanceRegistry.byMember.get(owner);
        if (record != null && record.owner.equals(owner)) {
            party.addAll(record.members.keySet());
        }
        party.remove(owner);
        return List.copyOf(party);
    }

    /**
     * A ban takes effect (Manage Party): {@code target} leaves the owner\u0027s pre-registered party and, if they
     * are standing in the owner\u0027s dungeon or room, they are sent out under the ordinary leaving rules.
     */
    static void evict(MinecraftServer server, UUID owner, UUID target) {
        Set<UUID> companions = pendingParty.get(owner);
        if (companions != null) {
            companions.remove(target);
            if (companions.isEmpty()) {
                pendingParty.remove(owner);
            }
        }
        invites.remove(target);
        ServerPlayer player = server.getPlayerList().getPlayer(target);
        InstanceRecord record = InstanceRegistry.byMember.get(target);
        if (player != null && record != null && record.owner.equals(owner) && !target.equals(owner)) {
            player.sendSystemMessage(Component.literal("You can't stay here.").withStyle(ChatFormatting.RED));
            RunLifecycle.exit(player, RunLifecycle.ExitReason.COMMAND);
        }
    }

    /**
     * Read-only view of a leader\u0027s pre-registered companions, in the order they
     * were added, for the roster screen. A copy: {@code pendingParty}'s sets are
     * mutated in place by {@code party} and {@code confirmKick}.
     */
    static List<UUID> partyCompanions(UUID leader) {
        Set<UUID> companions = pendingParty.get(leader);
        return companions == null ? List.of() : List.copyOf(companions);
    }

    /** Tells a dropped companion, if they are around to hear it. */
    private static void notifyKicked(MinecraftServer server, ServerPlayer leader, UUID id) {
        if (server == null) {
            return;
        }
        ServerPlayer companion = server.getPlayerList().getPlayer(id);
        if (companion != null) {
            companion.sendSystemMessage(Component.literal(
                    leader.getName().getString() + " removed you from their dungeon party.")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    /** @return whether an invite was actually sent (PD-38). */
    static boolean invite(ServerPlayer inviter, ServerPlayer target) {
        InstanceRecord record = InstanceRegistry.byMember.get(inviter.getUUID());
        if (record == null) {
            inviter.sendSystemMessage(Component.literal(
                    "You are not in a dungeon. Run /dungeon first, then invite people in.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (target.getUUID().equals(inviter.getUUID())) {
            inviter.sendSystemMessage(Component.literal("You are already here.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        // PD-191: a player inside a different dungeon may be invited; joining ends their own as a fail.
        InstanceRecord targetRecord = InstanceRegistry.byMember.get(target.getUUID());
        if (targetRecord == record) {
            inviter.sendSystemMessage(Component.literal(
                    target.getName().getString() + " is already in this dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (bannedFrom(inviter.level().getServer(), record.owner, target.getUUID())) {
            inviter.sendSystemMessage(Component.literal(target.getName().getString() + " is banned.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (record.members.size() >= PocketDungeonsConfig.maxPartyMembers()) {
            inviter.sendSystemMessage(Component.literal(
                    "This dungeon is full (" + PocketDungeonsConfig.maxPartyMembers() + " players).")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        invites.put(target.getUUID(), new Invite(inviter.getUUID(), record.slot,
                System.currentTimeMillis() + PocketDungeonsConfig.inviteTtlSeconds() * 1000L));

        inviter.sendSystemMessage(Component.literal(
                "Invited " + target.getName().getString() + ". The invitation lasts two minutes.")
                .withStyle(ChatFormatting.GOLD));
        // The invitee's line was plain, unclickable text until now. It keeps every
        // word of it -- the command is still the documented way in -- and grows a
        // click that opens an accept/decline screen. Hung off the message rather
        // than pushed: an invitation arrives while they are doing something else,
        // and seizing their screen for it would be exactly the push every sibling
        // mod's rule forbids.
        String inviterName = inviter.getName().getString();
        target.sendSystemMessage(Component.literal(
                inviterName + " invites you into their dungeon. "
                        + "Run /dungeon join " + inviterName
                        + " within two minutes to go in."
                        + (targetRecord == null ? "" : " Joining ends your own dungeon as a failed dungeon: "
                                + "your haul keeps " + PocketDungeonsConfig.failHaulKeepPercent() + " percent"
                                + (target.getUUID().equals(targetRecord.owner)
                                        && targetRecord.members.size() > 1 ? ", and your party is sent home." : ".")))
                .withStyle(s -> s.withColor(ChatFormatting.GOLD)
                        .withClickEvent(DialogKit.open(DialogScreens.inviteOffer(inviterName)))));
        return true;
    }

    /**
     * PD-191 (owner ruling 2026-10-09): joining another party counts as having left one's own dungeon
     * first, so the same leaving rules apply: between floors the haul is cashed out, on a floor it fails.
     */
    static void leaveOwnDungeon(MinecraftServer server, ServerPlayer player, InstanceRecord own) {
        // Joining another party is leaving this one, so the leaving rules apply (owner ruling 2026-10-09):
        // between floors a member cashes out, on a floor they fail. An owner between floors ends the
        // run as at a checkpoint; on a floor they fail it for the party.
        if (own.isKeystoneRun() && player.getUUID().equals(own.owner)
                && !RunLifecycle.betweenFloors(own)) {
            Instances.failRunLeft(server, own, player);
            return;
        }
        RunLifecycle.exit(player, RunLifecycle.ExitReason.COMMAND);
    }

    /** @return whether the player actually joined (PD-38). */
    static boolean join(ServerPlayer player, ServerPlayer leader) {
        MinecraftServer server = player.level().getServer();
        if (server == null) {
            return false;
        }
        InstanceRecord own = InstanceRegistry.byMember.get(player.getUUID());
        if (own != null && own.members.containsKey(leader.getUUID())) {
            player.sendSystemMessage(Component.literal("You are already in that dungeon.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        Invite invite = invites.get(player.getUUID());
        if (invite == null || invite.expiresAtMillis() < System.currentTimeMillis()
                || !invite.leader().equals(leader.getUUID())) {
            invites.remove(player.getUUID());
            player.sendSystemMessage(Component.literal(
                    "You have no open invitation from " + leader.getName().getString() + ".")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        InstanceRecord record = InstanceRegistry.bySlot.get(invite.slot());
        if (record == null || !record.members.containsKey(invite.leader())) {
            invites.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("That dungeon has already closed.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }
        if (record.members.size() >= PocketDungeonsConfig.maxPartyMembers()) {
            player.sendSystemMessage(Component.literal(
                    "That dungeon is full (" + PocketDungeonsConfig.maxPartyMembers() + " players).")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        if (bannedFrom(server, record.owner, player.getUUID())) {
            invites.remove(player.getUUID());
            player.sendSystemMessage(Component.literal("You can't join this party.")
                    .withStyle(ChatFormatting.RED));
            return false;
        }

        invites.remove(player.getUUID());
        // PD-191: the invite is good, so leave the player's own dungeon first, as a failed one.
        if (own != null) {
            leaveOwnDungeon(server, player, own);
        }
        PlaytestJournal.hintEnter(player.getUUID(), "invite");
        record.guests.remove(player.getUUID());
        Instances.admit(server, record, player);
        RoomCompany.forServer(server).note(record.owner, player.getUUID());

        player.sendSystemMessage(Component.literal("You step into the dungeon.")
                .withStyle(ChatFormatting.GOLD));
        Instances.announce(server, record, player.getName().getString() + " joins the dungeon.",
                player.getUUID());
        PocketDungeonsMod.LOG.info("{} joined dungeon slot {}",
                player.getName().getString(), record.slot);
        return true;
    }
}
