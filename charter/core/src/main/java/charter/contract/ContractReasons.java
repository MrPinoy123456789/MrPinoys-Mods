package charter.contract;

import charter.engine.Reason;

/** Refusals for contracts and justice. Keys never change; text can. */
public final class ContractReasons {

    private ContractReasons() {
    }

    public static final Reason NOT_ALLOWED = new Reason("term_not_allowed", "That kind of term is not allowed on this basis.");
    public static final Reason TOO_SHORT = new Reason("term_too_short", "That is shorter than the least this term may run.");
    public static final Reason BAD_TERM = new Reason("bad_term", "That term makes no sense as written.");
    public static final Reason BOND_OFF = new Reason("bond_off", "This server does not allow bonds.");
    public static final Reason NO_TERMS = new Reason("no_terms", "A sentence needs at least one term.");
    public static final Reason NO_CUSTODY = new Reason("no_custody", "That player is not in your custody, or the custody has lapsed.");
    public static final Reason NOT_YOUR_LAND = new Reason("not_your_land", "You can only arrest someone standing on your own faction's land.");
    public static final Reason ALREADY_HELD = new Reason("already_held", "That player is already held.");
    public static final Reason SELF = new Reason("self", "You cannot do that to yourself.");
    public static final Reason COOLDOWN = new Reason("same_player_cooldown", "Your faction sentenced that player too recently.");
    public static final Reason WEEKLY_CAP = new Reason("weekly_cap", "That player has already served the most punishment allowed this week.");
    public static final Reason NO_CELL = new Reason("no_cell", "Confinement needs a cell in one of your keeps.");
    public static final Reason ONE_HOLD = new Reason("one_hold", "A sentence can hold a player in only one place.");
    public static final Reason NOT_HELD = new Reason("not_held", "That player is not being held.");
    public static final Reason NO_SUCH_CONTRACT = new Reason("no_such_contract", "There is no such contract.");
    public static final Reason NOT_LIVE = new Reason("not_live", "That contract is not running.");
    public static final Reason NOT_YOUR_CONTRACT = new Reason("not_your_contract", "That contract was not imposed by your organization.");
    public static final Reason NOT_A_PARTY = new Reason("not_a_party", "That is not a party to this contract.");
    public static final Reason IMPOSED_NO_BREACH = new Reason("imposed_no_breach", "An imposed contract can be resisted, never breached.");
    public static final Reason NOT_YOU = new Reason("not_you", "Only the prisoner can do this.");
    public static final Reason BAD_AMOUNT = new Reason("bad_amount", "Deposit at least one.");
}
