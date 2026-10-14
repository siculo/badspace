package badspace.common.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class AcquireOptionsTest {

    @Test
    void noTimeoutHasNoTimeout() {
        assertFalse(AcquireOptions.noTimeout().hasTimeout());
        assertEquals(AcquireOptions.NO_TIMEOUT, AcquireOptions.noTimeout().timeoutNanos());
    }

    @Test
    void positiveTimeoutIsKeptInNanoseconds() {
        AcquireOptions options = AcquireOptions.timeout(Duration.ofMillis(1500));
        assertTrue(options.hasTimeout());
        assertEquals(1_500_000_000L, options.timeoutNanos());
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
    void timeoutTooLargeForTheClockIsNoTimeout() {
        assertEquals(AcquireOptions.noTimeout(), AcquireOptions.timeout(Duration.ofNanos(Long.MAX_VALUE)));
        assertEquals(AcquireOptions.noTimeout(), AcquireOptions.timeout(Duration.ofSeconds(Long.MAX_VALUE)));
        assertTrue(AcquireOptions.timeout(Duration.ofNanos(Long.MAX_VALUE - 1)).hasTimeout());
    }
}
