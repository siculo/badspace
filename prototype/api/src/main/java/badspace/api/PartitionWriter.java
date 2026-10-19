package badspace.api;

import badspace.common.partition.MetadataWrite;
import badspace.common.partition.WriteToken;
import java.util.List;

/**
 * Operations shared by the writer handles of 2D and 3D partitions. A writer handle carries the
 * {@link WriteToken} of the owner of a partition, and every write, commit and removal goes to
 * the node with it: if the ownership changed after the token was given, they fail with
 * {@code OwnershipChangedException} and the partition does not change. The operations that
 * depend on the number of dimensions, like insert and update, are only in PartitionWriter2 and
 * PartitionWriter3. The application metadata of the entities do not depend on them, so their
 * writes are here. The handle has no reads: the writer reads the snapshots, like any reader.
 * <p>
 * {@link #close()} releases the ownership and discards the writes not committed, so in a
 * {@code try}-with-resources a writer that leaves with an exception before its commit loses its
 * writes, like in a rollback. One writer uses a handle at a time.
 */
public interface PartitionWriter extends AutoCloseable {

    /** Returns the token of the handle, for example to send it to another writer. */
    WriteToken token();

    /** Removes an entity. Fails with NoSuchElementException if the ID is not in the partition. */
    void remove(long entityId);

    /**
     * Removes many entities in one call. Fails with NoSuchElementException if an ID is not
     * in the partition, and with IllegalArgumentException if an ID appears twice.
     * If it fails, no entity is removed.
     */
    void removeAll(long[] entityIds);

    /**
     * Sets a key of the application metadata of an entity to the value. Fails with
     * NoSuchElementException if the ID is not in the partition.
     */
    void putMetadata(long entityId, String key, long value);

    /**
     * Deletes a key of the application metadata of an entity; if the key has no value, it does
     * nothing. Fails with NoSuchElementException if the ID is not in the partition.
     */
    void deleteMetadata(long entityId, String key);

    /**
     * Writes the application metadata of many entities in one call: each write sets or deletes
     * one key of one entity, in the order of the list, so if more writes change the same key
     * the last one wins. The metadata are confirmed by the same commit as the positions.
     * Removing an entity deletes its metadata. Fails with NoSuchElementException if an ID is not
     * in the partition; in this case no metadata change.
     */
    void updateMetadataAll(List<MetadataWrite> writes);

    /**
     * Sets a key of the application metadata of an entity to the value only if the key has
     * the expected value now, with the writes not committed yet: a compare-and-set. Returns
     * whether it set the value; if the key has another value or no value, nothing changes.
     * Fails with NoSuchElementException if the ID is not in the partition. For more conditions
     * and writes in one batch, {@code updateIf} of PartitionWriter2 and PartitionWriter3.
     */
    boolean compareAndSetMetadata(long entityId, String key, long expected, long value);

    /**
     * Confirms the writes in the current tick of the partition: the snapshot of the tick will
     * have them.
     * Each commit confirms the full current state, including the effects of earlier commits
     * unless later writes change or remove them. Only the last commit of a tick defines its
     * snapshot; readers cannot acquire the intermediate states. Writes after the last commit
     * stay uncommitted and carry over to the next tick. A commit with no writes is valid.
     * This call does not advance the tick, wait for the next tick or stop further writes
     * in the same tick. The writer decides whether to wait before writing again.
     * The snapshot becomes acquirable when the tick moves forward, with {@code acquire(tick)}
     * and {@code acquireLatest()} of Partition2 and Partition3.
     */
    void commit();

    /**
     * Removes the partition from its node, following its removal policy: the last operation of
     * the writer. After this call the writes and the commits fail with IllegalStateException,
     * and the writes not committed are discarded. The removal becomes effective at the next move
     * of the tick; readers can still acquire the snapshots of the ticks before it while they are
     * in the retention window. Fails with IllegalStateException if the policy is REQUIRE_EMPTY
     * and the last commit has entities (in this case the partition does not change), or if the
     * partition was already removed.
     */
    void removePartition();

    /**
     * Releases the ownership if the token of the handle is still the current one: the partition
     * has no owner and the writes not committed are discarded. It does nothing if the ownership
     * already went to someone else, after the removal of the partition, after its destruction,
     * and when it is called again, so it never fails.
     */
    @Override
    void close();
}
