package fortune.mc;

/** The running service, for commands, events and gametests. Null before the server has started. */
public final class Fortune {

    private static volatile FortuneService service;

    private Fortune() {
    }

    public static FortuneService service() {
        return service;
    }

    static void set(FortuneService running) {
        service = running;
    }
}
