package charter.mc.store;

import charter.engine.Action;
import charter.gov.GovEvents;
import charter.gov.actions.Appoint;
import charter.gov.actions.CreateOffice;
import charter.gov.actions.Vacate;
import charter.grant.GrantEvents;
import charter.grant.actions.GrantPower;
import charter.grant.actions.RevokeGrant;
import charter.history.HistoryEvent;
import charter.land.LandEvents;
import charter.land.actions.ClaimLand;
import charter.land.actions.LoseCore;
import charter.land.actions.PlaceNexus;
import charter.land.actions.PlantCamp;
import charter.land.actions.Unclaim;
import charter.org.OrgEvents;
import charter.org.actions.AcceptInvite;
import charter.org.actions.CreateSubOrg;
import charter.org.actions.Expel;
import charter.org.actions.Found;
import charter.org.actions.InviteMember;
import charter.org.actions.Leave;
import charter.org.actions.Rename;
import charter.siege.SiegeEvents;
import charter.siege.actions.DepositFood;
import charter.treasury.TreasuryEvents;
import charter.treasury.actions.Deposit;
import charter.treasury.actions.Transfer;
import charter.treasury.actions.Withdraw;

/**
 * The action and event types of M1 to M5, and the store's own events. A later
 * milestone registers its types the same way from its own class (M6: contracts
 * and justice), called from its own {@code register} line in {@code CharterMod}.
 *
 * <p>Action names are written into saved votes, so they never change once
 * released. Event names are the Chronicle's template keys.
 */
public final class CoreTypes {

    private CoreTypes() {
    }

    public static void register(TypeRegistry<Action> actions, TypeRegistry<HistoryEvent> events) {
        actions.register("org.found", Found.class)
                .register("org.invite", InviteMember.class)
                .register("org.accept_invite", AcceptInvite.class)
                .register("org.leave", Leave.class)
                .register("org.found_keep", charter.org.actions.FoundKeep.class)
                .register("org.secede", charter.org.actions.Secede.class)
                .register("org.dissolve", charter.org.actions.DissolveOrg.class)
                .register("npc.spawn", charter.npc.SpawnNpc.class)
                .register("org.break_away", charter.org.actions.BreakAway.class)
                .register("org.apply", charter.org.actions.Apply.class)
                .register("org.admit", charter.org.actions.Admit.class)
                .register("org.reject", charter.org.actions.Reject.class)
                .register("org.expel", Expel.class)
                .register("org.rename", Rename.class)
                .register("org.create_suborg", CreateSubOrg.class)
                .register("gov.create_office", CreateOffice.class)
                .register("gov.appoint", Appoint.class)
                .register("gov.vacate", Vacate.class)
                .register("gov.unseat", charter.gov.actions.Unseat.class)
                .register("gov.set_seats", charter.gov.actions.SetSeats.class)
                .register("grant.grant", GrantPower.class)
                .register("grant.revoke", RevokeGrant.class)
                .register("treasury.deposit", Deposit.class)
                .register("treasury.withdraw", Withdraw.class)
                .register("treasury.transfer", Transfer.class)
                .register("land.place_nexus", PlaceNexus.class)
                .register("land.plant_camp", PlantCamp.class)
                .register("land.claim", ClaimLand.class)
                .register("land.unclaim", Unclaim.class)
                .register("land.lose_core", LoseCore.class)
                .register("siege.deposit_food", DepositFood.class)
                .register("war.declare", charter.siege.actions.DeclareWar.class);

        events.registerNested(OrgEvents.class, HistoryEvent.class)
                .registerNested(GovEvents.class, HistoryEvent.class)
                .registerNested(GrantEvents.class, HistoryEvent.class)
                .registerNested(TreasuryEvents.class, HistoryEvent.class)
                .registerNested(LandEvents.class, HistoryEvent.class)
                .registerNested(SiegeEvents.class, HistoryEvent.class)
                .registerNested(charter.npc.NpcEvents.class, HistoryEvent.class)
                .registerNested(StoreEvents.class, HistoryEvent.class);
        Persistence.block(CoreBlocks.block("join_requests", CoreTypes::writeRequests, CoreTypes::readRequests));
        Persistence.block(CoreBlocks.block("breakaways", CoreTypes::writeBreakaways, CoreTypes::readBreakaways));
        Persistence.block(CoreBlocks.block("npc", CoreTypes::writeNpc, CoreTypes::readNpc));
        Persistence.block(CoreBlocks.block("wars", CoreTypes::writeWars, CoreTypes::readWars));
    }

    private static com.google.gson.JsonElement writeRequests(charter.state.CharterState state, com.google.gson.Gson gson) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("requests", gson.toJsonTree(state.joinRequests()));
        return root;
    }

    private static void readRequests(com.google.gson.JsonObject json, charter.state.CharterState state, com.google.gson.Gson gson, Repairs repairs) {
        if (!json.has("requests")) return;
        java.util.List<charter.org.JoinRequest> read = gson.fromJson(json.get("requests"),
                new com.google.gson.reflect.TypeToken<java.util.List<charter.org.JoinRequest>>() { }.getType());
        for (charter.org.JoinRequest request : read) {
            if (state.org(request.org()).isEmpty()) {
                repairs.note("request_dropped", "a request of " + request.player() + " to join " + request.org() + ", which is gone");
                continue;
            }
            state.addJoinRequest(request);
        }
    }

    private static com.google.gson.JsonElement writeBreakaways(charter.state.CharterState state, com.google.gson.Gson gson) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("breakaways", gson.toJsonTree(state.breakaways()));
        return root;
    }

    private static void readBreakaways(com.google.gson.JsonObject json, charter.state.CharterState state, com.google.gson.Gson gson, Repairs repairs) {
        if (!json.has("breakaways")) return;
        java.util.List<charter.org.Breakaway> read = gson.fromJson(json.get("breakaways"),
                new com.google.gson.reflect.TypeToken<java.util.List<charter.org.Breakaway>>() { }.getType());
        for (charter.org.Breakaway b : read) {
            if (state.org(b.org()).isEmpty()) {
                repairs.note("breakaway_dropped", "a break away of " + b.org() + ", which is gone");
                continue;
            }
            state.addBreakaway(b);
        }
    }

    private static com.google.gson.JsonElement writeWars(charter.state.CharterState state, com.google.gson.Gson gson) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("goals", gson.toJsonTree(state.wars.goals));
        root.add("truces", gson.toJsonTree(state.wars.truces));
        root.add("windows", gson.toJsonTree(state.wars.windows));
        root.addProperty("nextId", state.wars.nextId);
        return root;
    }

    private static void readWars(com.google.gson.JsonObject json, charter.state.CharterState state, com.google.gson.Gson gson, Repairs repairs) {
        state.wars.goals.clear();
        state.wars.truces.clear();
        state.wars.windows.clear();
        if (json.has("goals")) {
            java.util.List<charter.siege.WarBook.Goal> goals = gson.fromJson(json.get("goals"),
                    new com.google.gson.reflect.TypeToken<java.util.List<charter.siege.WarBook.Goal>>() { }.getType());
            for (charter.siege.WarBook.Goal g : goals) {
                // A goal whose outpost is gone is dropped: there is nothing left to capture.
                if (g != null && g.outpost != null && state.core(g.outpost).isPresent()) state.wars.goals.add(g);
                else repairs.note("war_goal_dropped", "a war goal on an outpost that is gone");
            }
        }
        if (json.has("truces")) {
            state.wars.truces.addAll(gson.<java.util.List<charter.siege.WarBook.Truce>>fromJson(json.get("truces"),
                    new com.google.gson.reflect.TypeToken<java.util.List<charter.siege.WarBook.Truce>>() { }.getType()));
        }
        if (json.has("windows")) {
            state.wars.windows.addAll(gson.<java.util.List<charter.siege.WarBook.Window>>fromJson(json.get("windows"),
                    new com.google.gson.reflect.TypeToken<java.util.List<charter.siege.WarBook.Window>>() { }.getType()));
        }
        if (json.has("nextId")) state.wars.nextId = json.get("nextId").getAsLong();
    }

    private static com.google.gson.JsonElement writeNpc(charter.state.CharterState state, com.google.gson.Gson gson) {
        com.google.gson.JsonObject root = new com.google.gson.JsonObject();
        root.add("sites", gson.toJsonTree(state.npc.sites));
        root.add("respawns", gson.toJsonTree(state.npc.respawns));
        root.add("live", gson.toJsonTree(state.npc.live));
        root.add("crumbles", gson.toJsonTree(state.npc.crumbles));
        root.addProperty("nextId", state.npc.nextId);
        return root;
    }

    private static void readNpc(com.google.gson.JsonObject json, charter.state.CharterState state, com.google.gson.Gson gson, Repairs repairs) {
        state.npc.sites.clear();
        state.npc.respawns.clear();
        if (json.has("sites")) {
            java.util.List<charter.npc.NpcBook.Site> sites = gson.fromJson(json.get("sites"),
                    new com.google.gson.reflect.TypeToken<java.util.List<charter.npc.NpcBook.Site>>() { }.getType());
            for (charter.npc.NpcBook.Site site : sites) {
                // A site whose core is not in the state is dropped; the scheduler then replaces it after the usual wait.
                state.npc.sites.add(site);
            }
        }
        if (json.has("respawns")) {
            java.util.List<charter.npc.NpcBook.Respawn> respawns = gson.fromJson(json.get("respawns"),
                    new com.google.gson.reflect.TypeToken<java.util.List<charter.npc.NpcBook.Respawn>>() { }.getType());
            state.npc.respawns.addAll(respawns);
        }
        state.npc.crumbles.clear();
        if (json.has("crumbles") && json.get("crumbles").isJsonArray()) {
            java.util.List<charter.npc.NpcBook.Crumble> crumbles = gson.fromJson(json.get("crumbles"),
                    new com.google.gson.reflect.TypeToken<java.util.List<charter.npc.NpcBook.Crumble>>() { }.getType());
            for (charter.npc.NpcBook.Crumble c : crumbles) {
                if (c == null) continue;
                if (c.pending == null) c.pending = new java.util.ArrayList<>();
                if (c.restore == null) c.restore = new charter.npc.NpcBook.Snapshot();
                state.npc.crumbles.add(c);
            }
        }
        // A save from before waves has no "live": each site then gets its tier's supply the first time it is tended.
        state.npc.live.clear();
        if (json.has("live") && json.get("live").isJsonObject()) {
            java.util.Map<Long, charter.npc.NpcBook.Live> live = gson.fromJson(json.get("live"),
                    new com.google.gson.reflect.TypeToken<java.util.Map<Long, charter.npc.NpcBook.Live>>() { }.getType());
            for (var e : live.entrySet()) {
                charter.npc.NpcBook.Live l = e.getValue();
                if (l == null) continue;
                if (l.raiders == null) l.raiders = new java.util.ArrayList<>();
                state.npc.live.put(e.getKey(), l);
            }
        }
        if (json.has("nextId")) state.npc.nextId = json.get("nextId").getAsLong();
    }
}
