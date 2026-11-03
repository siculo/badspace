package badspace.service.partition.slots;

/**
 * Arrays of the slots for the tests: when the test sets a failure, the next allocation of
 * that kind throws it instead, once, as if the memory ran out.
 */
final class FailingSlotArrays implements SlotArrays {

    // The failures of the next allocations, if not null. Only the test thread uses them.
    Error longsFailure;
    Error doublesFailure;

    @Override
    public long[] newLongs(int length) {
        Error failure = longsFailure;
        longsFailure = null;
        if (failure != null) {
            throw failure;
        }
        return new long[length];
    }

    @Override
    public double[] newDoubles(int length) {
        Error failure = doublesFailure;
        doublesFailure = null;
        if (failure != null) {
            throw failure;
        }
        return new double[length];
    }
}
