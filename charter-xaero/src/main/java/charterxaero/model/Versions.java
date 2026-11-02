package charterxaero.model;

/** Comparing mod versions such as {@code 1.47.2} or {@code 26.6.0+build}. Plain JDK, unit tested. */
public final class Versions {

    private Versions() {
    }

    /**
     * Whether {@code have} is at least {@code need}, comparing the leading dotted numbers ({@code 26.6.2-beta} reads as
     * 26.6.2). A version with no leading number is never enough.
     */
    public static boolean atLeast(String have, String need) {
        int[] a = numbers(have);
        int[] b = numbers(need);
        if (a.length == 0) return false;
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return true;
    }

    static int[] numbers(String version) {
        if (version == null) return new int[0];
        String head = version.trim();
        int end = 0;
        while (end < head.length() && (Character.isDigit(head.charAt(end)) || head.charAt(end) == '.')) end++;
        head = head.substring(0, end);
        if (head.isEmpty()) return new int[0];
        String[] parts = head.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException tooBig) {
                out[i] = Integer.MAX_VALUE;
            }
        }
        return out;
    }
}
