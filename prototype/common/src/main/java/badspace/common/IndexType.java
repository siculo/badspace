package badspace.common;

/**
 * Spatial index of a partition, chosen when the partition is created.
 * All the indices give the same query results; they differ only in speed and memory.
 */
public enum IndexType {

    /** No structure: each query reads all the entities of the partition. */
    LINEAR_SCAN
}
