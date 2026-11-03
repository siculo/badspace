package badspace.service.partition.slots;

/**
 * Makes the arrays of the slots of the writer and of their snapshots. The slots use
 * {@link #DEFAULT}; the tests give arrays that fail, as if the memory ran out, to check that
 * a failure leaves the slots consistent.
 */
interface SlotArrays {

    /** Makes new arrays. */
    SlotArrays DEFAULT = new SlotArrays() {
        @Override
        public long[] newLongs(int length) {
            return new long[length];
        }

        @Override
        public double[] newDoubles(int length) {
            return new double[length];
        }
    };

    /**
     * Returns a new array of longs with the given length, all zero.
     *
     * @param length the length of the array, not negative
     * @return the new array
     */
    long[] newLongs(int length);

    /**
     * Returns a new array of doubles with the given length, all zero.
     *
     * @param length the length of the array, not negative
     * @return the new array
     */
    double[] newDoubles(int length);
}
