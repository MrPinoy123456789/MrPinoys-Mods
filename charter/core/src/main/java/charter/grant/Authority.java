package charter.grant;

/**
 * How an action was authorized: by the organization itself (a passed vote), by a
 * grant (named, so a derived grant can name it as parent), by an operator, or not
 * at all.
 */
public record Authority(Kind kind, Grant via) {

    public enum Kind { SOURCE, GRANT, ADMIN, NONE, NOT_NEEDED }

    public static final Authority SOURCE = new Authority(Kind.SOURCE, null);
    public static final Authority ADMIN = new Authority(Kind.ADMIN, null);
    public static final Authority NONE = new Authority(Kind.NONE, null);
    public static final Authority NOT_NEEDED = new Authority(Kind.NOT_NEEDED, null);

    public static Authority via(Grant grant) {
        return new Authority(Kind.GRANT, grant);
    }

    public boolean ok() {
        return kind != Kind.NONE;
    }
}
