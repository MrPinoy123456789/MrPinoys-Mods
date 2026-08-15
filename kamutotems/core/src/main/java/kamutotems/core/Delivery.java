package kamutotems.core;

public enum Delivery {
    HIT,
    ECHO,
    SPLASH;

    public static Delivery fromKamuId(String id) {
        if (id == null) {
            return null;
        }
        return switch (id) {
            case "hit" -> HIT;
            case "echo" -> ECHO;
            case "splash" -> SPLASH;
            default -> null;
        };
    }

    public int delayTicks() {
        return this == ECHO ? 20 : 0;
    }

    public double areaRadius() {
        return this == SPLASH ? 3.5 : 0.0;
    }

    public double powerScale() {
        return this == SPLASH ? 0.6 : 1.0;
    }

    public int maxTargets() {
        return this == SPLASH ? 6 : 1;
    }

    public boolean sumsSelfBenefit() {
        return this == SPLASH;
    }
}
