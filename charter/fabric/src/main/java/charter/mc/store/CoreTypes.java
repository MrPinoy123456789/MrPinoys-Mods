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
import charter.siege.actions.ChooseFall;
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
                .register("siege.choose_fall", ChooseFall.class);

        events.registerNested(OrgEvents.class, HistoryEvent.class)
                .registerNested(GovEvents.class, HistoryEvent.class)
                .registerNested(GrantEvents.class, HistoryEvent.class)
                .registerNested(TreasuryEvents.class, HistoryEvent.class)
                .registerNested(LandEvents.class, HistoryEvent.class)
                .registerNested(SiegeEvents.class, HistoryEvent.class)
                .registerNested(StoreEvents.class, HistoryEvent.class);
        Persistence.block(CoreBlocks.block("join_requests", CoreTypes::writeRequests, CoreTypes::readRequests));
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
}
