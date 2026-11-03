package badspace.common.partition;

/** The structure of a partition where a failure happened, in a {@link PartitionFailure}. */
public enum PartitionComponent {
    /** The slots: IDs and positions of the entities. */
    SLOTS,
    /** The spatial index. */
    INDEX,
    /** The application metadata. */
    METADATA,
    /** The failure did not happen in one of the structures, or the partition does not know where. */
    UNKNOWN
}
