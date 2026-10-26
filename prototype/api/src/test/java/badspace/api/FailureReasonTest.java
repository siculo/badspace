package badspace.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import badspace.common.partition.SnapshotNotAvailableException.Reason;
import org.junit.jupiter.api.Test;

class FailureReasonTest {

    @Test
    void eachReasonOfTheNodeHasTheFailureReasonWithTheSameName() {
        for (Reason reason : Reason.values()) {
            assertEquals(reason.name(), FailureReason.of(reason).name());
        }
    }
}
