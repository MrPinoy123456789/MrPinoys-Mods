package charter.mc.store;

import charter.contract.ContractBook;
import charter.contract.ContractEvents;
import charter.contract.Term;
import charter.contract.actions.Arrest;
import charter.contract.actions.Breach;
import charter.contract.actions.Confine;
import charter.contract.actions.DepositPersonal;
import charter.contract.actions.Escape;
import charter.contract.actions.Execute;
import charter.contract.actions.FineCustody;
import charter.contract.actions.Pardon;
import charter.contract.actions.Release;
import charter.contract.actions.Sentence;
import charter.history.HistoryEvent;
import charter.state.CharterState;
import com.google.gson.Gson;
import com.google.gson.InstanceCreator;

/**
 * M6's store names, its {@code Term} adapter and its {@code contracts} block
 * (BUILDING.md, "Plugging in state" and "Plugging in types").
 *
 * <p>The block writes the whole {@link ContractBook} through the store's JSON
 * setup, which already knows every id type and complex map key. Reading fills the
 * state's existing book in place, since {@code CharterState.contracts} is final.
 */
public final class ContractTypes {

    private ContractTypes() {
    }

    static void register() {
        Persistence.adapter(new SealedAdapter<>(Term.class));
        Persistence.ACTIONS.register("justice.arrest", Arrest.class)
                .register("justice.confine", Confine.class)
                .register("justice.sentence", Sentence.class)
                .register("justice.execute", Execute.class)
                .register("justice.fine", FineCustody.class)
                .register("justice.pardon", Pardon.class)
                .register("justice.release", Release.class)
                .register("justice.escape", Escape.class)
                .register("contract.breach", Breach.class)
                .register("contract.deposit_personal", DepositPersonal.class);
        Persistence.EVENTS.registerNested(ContractEvents.class, HistoryEvent.class);
        Persistence.block(CoreBlocks.block("contracts", ContractTypes::write, ContractTypes::read));
    }

    private static com.google.gson.JsonElement write(CharterState state, Gson gson) {
        return gson.toJsonTree(state.contracts, ContractBook.class);
    }

    private static void read(com.google.gson.JsonObject json, CharterState state, Gson gson, Repairs repairs) {
        Gson into = gson.newBuilder()
                .registerTypeAdapter(ContractBook.class, (InstanceCreator<ContractBook>) type -> state.contracts)
                .create();
        into.fromJson(json, ContractBook.class);
    }
}
