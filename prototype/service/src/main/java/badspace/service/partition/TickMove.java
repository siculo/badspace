package badspace.service.partition;

/** The result of a move of the tick of a partition that does not wait for its lock. */
enum TickMove {
    /** Another thread holds the lock of the partition: the tick did not move. */
    BUSY,
    /** The partition is at the new tick. */
    MOVED,
    /** The partition is at the new tick, and the move destroyed it after its removal. */
    DESTROYED
}
