package badspace.common;

/** Identifies a partition. The API generates it; it is unique within a space. */
public record PartitionId(long value) {
}
