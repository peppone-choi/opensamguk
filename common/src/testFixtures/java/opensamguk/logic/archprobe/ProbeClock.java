package opensamguk.logic.archprobe;

import java.time.Instant;

public final class ProbeClock {
    public Instant readWallClock() {
        return Instant.now();
    }
}
