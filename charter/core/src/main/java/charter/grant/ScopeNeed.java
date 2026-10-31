package charter.grant;

/** What an action needs from a grant's scope: an amount of Marks to spend, or nothing. */
public record ScopeNeed(long marks) {

    public static final ScopeNeed NONE = new ScopeNeed(0);

    public static ScopeNeed spend(long marks) {
        return new ScopeNeed(marks);
    }
}
