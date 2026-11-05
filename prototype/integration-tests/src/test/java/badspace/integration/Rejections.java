package badspace.integration;

import badspace.common.partition.WriteMode;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.function.Executable;

/** Checks of the writes that the node rejects, in the two write modes. */
final class Rejections {

    private Rejections() {
    }

    /**
     * Checks that the node rejects the writes of a test with an exception of the type, and
     * returns it: in the direct mode the node rejects the writes at once, with the buffer the
     * commit that sends them. The writes stop at the first rejected one.
     */
    static <T extends Throwable> T assertRejected(WriteMode mode, Class<T> type, Executable writes, Executable commit) {
        if (mode == WriteMode.DIRECT) {
            return Assertions.assertThrows(type, writes);
        }
        Assertions.assertDoesNotThrow(writes);
        return Assertions.assertThrows(type, commit);
    }
}
