package au.clock.display;

final class LampState {
    interface Reader {
        LampState read() throws Exception;
    }

    private static final long[] RETRY_DELAYS_MS = {0, 250, 450, 700, 1000};

    final String id;
    final boolean available;
    final boolean on;
    final int level;
    final boolean dimmable;

    LampState(String id, String state, int brightness, boolean dimmable) {
        this.id = id;
        this.on = "on".equals(state);
        this.available = on || "off".equals(state);
        this.level = !available ? -1 : !on ? 0
                : brightness < 0 ? -1 : Math.round(Math.min(255, brightness) * 100f / 255);
        this.dimmable = dimmable;
    }

    boolean matches(boolean turnOn, int brightness) {
        return available && on == turnOn
                && (!turnOn || brightness < 0 || (level >= 0 && Math.abs(level - brightness) <= 2));
    }

    static LampState confirm(Reader reader, boolean turnOn, int brightness) throws InterruptedException {
        LampState latest = null;
        for (long delay : RETRY_DELAYS_MS) {
            if (delay > 0) Thread.sleep(delay);
            try {
                latest = reader.read();
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception unavailable) {
                continue;
            }
            if (latest.matches(turnOn, brightness)) return latest;
        }
        return latest;
    }
}
