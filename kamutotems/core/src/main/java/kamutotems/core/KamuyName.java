package kamutotems.core;

public final class KamuyName {

    public static String sanitise(String raw, int maxLength) {
        if (raw == null) {
            return null;
        }

        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '§') {
                i++;
                if (i < raw.length()) {
                    i++;
                }
                continue;
            }
            if (Character.isISOControl(c)) {
                sb.append(' ');
            } else {
                sb.append(c);
            }
            i++;
        }

        String s = sb.toString().replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) {
            return null;
        }
        if (s.length() > maxLength) {
            s = s.substring(0, maxLength).trim();
        }
        if (s.isEmpty()) {
            return null;
        }
        return s;
    }
}
