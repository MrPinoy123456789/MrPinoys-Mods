package charter.time;

/** Wall time in milliseconds. Injected so tests drive time directly. */
@FunctionalInterface
public interface Clock {
    long now();

    /** A settable clock for tests and admin tools. */
    final class Manual implements Clock {
        private long now;

        public Manual(long start) {
            now = start;
        }

        @Override
        public long now() {
            return now;
        }

        public void advance(long millis) {
            now += millis;
        }

        public void set(long millis) {
            now = millis;
        }
    }
}
