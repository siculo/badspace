package badspace.common.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AcquireOptionsTest {

    @Test
    void defaultOptionsHaveTheDefaultTimeout() {
        assertEquals(AcquireOptions.DEFAULT_TIMEOUT.toNanos(), AcquireOptions.defaultTimeout().timeoutNanos());
    }

    @Test
    void positiveTimeoutIsKeptInNanoseconds() {
        assertEquals(1_500_000_000L, AcquireOptions.timeout(Duration.ofMillis(1500)).timeoutNanos());
        assertEquals(1, AcquireOptions.timeout(Duration.ofNanos(1)).timeoutNanos());
    }

    @Test
    void zeroAndNegativeTimeoutsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> AcquireOptions.timeout(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> AcquireOptions.timeout(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class, () -> new AcquireOptions(0));
        assertThrows(IllegalArgumentException.class, () -> new AcquireOptions(-1));
        assertThrows(NullPointerException.class, () -> AcquireOptions.timeout(null));
    }

    @Test
    void maxTimeoutIsAcceptedAndGreaterTimeoutsAreRejected() {
        Duration max = AcquireOptions.MAX_TIMEOUT;
        assertEquals(max.toNanos(), AcquireOptions.timeout(max).timeoutNanos());
        assertThrows(IllegalArgumentException.class, () -> AcquireOptions.timeout(max.plusNanos(1)));
        assertThrows(IllegalArgumentException.class, () -> new AcquireOptions(max.toNanos() + 1));
        // Too large for nanoseconds in a long: rejected, not an overflow.
        assertThrows(IllegalArgumentException.class, () -> AcquireOptions.timeout(Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
