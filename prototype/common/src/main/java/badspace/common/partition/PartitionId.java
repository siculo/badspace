package badspace.common.partition;

/** Identifies a partition. The API generates it; it is unique across spaces and never used again. */
public record PartitionId(long value) {
}
