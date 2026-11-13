package prototest;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.UnrecoverableProtocolException;
import prototest.server.RequestsRegister;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.Assert.*;

public class RequestsRegisterTest {
    private static final UUID FIRST_PROXY = new UUID(0, 1);
    private static final UUID SECOND_PROXY = new UUID(0, 2);

    @Test
    public void expirationOfOneProxyDoesNotRejectAnotherProxysLowerSequence() {
        var clock = new TestClock();
        var register = new RequestsRegister(100, clock);
        var expired = new RequestId(FIRST_PROXY, 20);
        register.executeOnce(expired, () -> {});
        clock.now = 101;
        register.executeOnce(new RequestId(FIRST_PROXY, 21), () -> {});

        String result = register.executeOnceAndReturn(new RequestId(SECOND_PROXY, 0), () -> "accepted");

        assertEquals("accepted", result);
        assertThrows(UnrecoverableProtocolException.class,
                () -> register.executeOnce(expired, () -> fail("Expired request ran again")));
    }

    @Test
    public void anotherProxyDoesNotHideTheExpirationLimit() {
        var clock = new TestClock();
        var register = new RequestsRegister(100, clock);
        var first = new RequestId(FIRST_PROXY, 20);
        var second = new RequestId(SECOND_PROXY, 0);
        register.executeOnce(first, () -> {});
        clock.now = 101;
        register.executeOnce(second, () -> {});
        clock.now = 202;
        register.executeOnce(new RequestId(SECOND_PROXY, 1), () -> {});

        assertThrows(UnrecoverableProtocolException.class,
                () -> register.executeOnce(first, () -> fail("Expired request ran again")));
        assertThrows(UnrecoverableProtocolException.class,
                () -> register.executeOnce(second, () -> fail("Expired request ran again")));
    }

    @Test
    public void generationOrderDoesNotRequireTheSameArrivalOrderBeforeExpiration() {
        var register = new RequestsRegister(100, new TestClock());
        var newer = new RequestId(FIRST_PROXY, 1);
        var older = new RequestId(FIRST_PROXY, 0);

        assertEquals("newer", register.executeOnceAndReturn(newer, () -> "newer"));
        assertEquals("older", register.executeOnceAndReturn(older, () -> "older"));
        assertEquals("older", register.executeOnceAndReturn(older, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
    }

    @Test
    public void resultsAreRetainedSeparatelyForEachProxy() {
        var register = new RequestsRegister(100, new TestClock());
        var first = new RequestId(FIRST_PROXY, 0);
        var second = new RequestId(SECOND_PROXY, 0);

        assertEquals("first", register.executeOnceAndReturn(first, () -> "first"));
        assertEquals("second", register.executeOnceAndReturn(second, () -> "second"));
        assertEquals("first", register.executeOnceAndReturn(first, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
        assertEquals("second", register.executeOnceAndReturn(second, () -> {
            throw new AssertionError("Duplicate request ran again");
        }));
    }

    private static final class TestClock extends Clock {
        private long now;

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }
}
