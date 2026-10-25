package charter.contract;

/**
 * The leverage that lets one party impose terms (DESIGN.md §15.4). The war goal
 * basis replaced the war score basis (D11); in the first build only custody and
 * occupation can be reached, but the model holds all three.
 */
public enum Basis {
    /** Holding a player arrested on the imposer's own land. */
    CUSTODY,
    /** A declared goal, once the enemy's war exhaustion reaches 100%. */
    WAR_GOAL,
    /** Starving a keep and choosing to occupy it. */
    OCCUPATION
}
