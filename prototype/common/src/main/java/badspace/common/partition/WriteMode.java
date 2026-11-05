package badspace.common.partition;

/**
 * How the writes of the owner reach the node of the partition. The node chooses it, and the
 * writer follows it (see {@code WriteSession2} and {@code WriteSession3}). The two modes
 * give the same result: the same writes, in the same order, make the same commits. Only two
 * things change: when the node rejects the writes for the token or the state of the partition,
 * and how long each call holds the lock of the partition.
 */
public enum WriteMode {
    /**
     * Each write of the owner goes to the node at once, with {@code write}, and the commit only
     * confirms them. The node keeps the writes not committed, and a rollback, a claim or a
     * release discards them. The mode of a node in the same process as the writer: the writer
     * keeps no buffer, and the calls cost nothing more than the work of the node.
     */
    DIRECT,
    /**
     * The writer keeps the writes in a buffer, composed by entity, and the commit sends them to
     * the node in one call, which applies and confirms them. Between the commits the node has
     * no writes of the owner. The mode of a remote node: one call for each commit, whatever the
     * number of writes.
     */
    BUFFERED
}
