package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.Entity2.entity;
import static badspace.common.partition.MetadataWrite.delete;
import static badspace.common.partition.MetadataWrite.put;
import static badspace.service.partition.Names.nameOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.WriteToken;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the application metadata of the entities on the node: the writes, the reads through
 * the snapshots of the commits, the removal of the entities and the discarded writes.
 */
class PartitionMetadata2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int THREADS = 8;

    private LocalPartitionNode2 node;
    private WriteToken token;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2();
        node.createPartition(P1, nameOf(P1), IndexConfig.uniformGrid(4));
        token = node.claim(P1, 0).orElseThrow();
        Writes2.of(node).insertAll(token, List.of(entity(1, 1, 1), entity(2, 2, 2), entity(3, 3, 3)));
    }

    @Test
    void committedMetadataAreReadThroughTheSnapshot() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 7), put(1, "owner", -3), put(2, "version", 1)));
        assertEquals(List.of(metadata(1, Map.of("version", 7L, "owner", -3L)), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(publish(), new long[] {1, 2}));
    }

    @Test
    void batchWithAnUnpairedSurrogateInAKeyChangesNothing() {
        String unpaired = String.valueOf((char) 0xd800);
        assertThrows(IllegalArgumentException.class,
                () -> Writes2.of(node).updateMetadataAll(token, List.of(put(1, "a", 1), put(1, unpaired, 2))));
        assertEquals(List.of(metadata(1, Map.of())), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void entityWithoutMetadataHasAnEmptyMapAndIdsNotInTheSnapshotAreSkipped() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(2, "version", 1)));
        assertEquals(List.of(metadata(3, Map.of()), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(publish(), new long[] {3, 99, 2}));
    }

    @Test
    void lastWriteOfAKeyWinsAndDeleteRemovesTheKey() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "a", 1), put(1, "b", 2), put(1, "a", 3), delete(1, "b"),
                delete(1, "missing"), put(2, "a", 1), delete(2, "a")));
        assertEquals(List.of(metadata(1, Map.of("a", 3L)), metadata(2, Map.of())),
                node.getMetadataAll(publish(), new long[] {1, 2}));
    }

    @Test
    void snapshotOfACommitDoesNotSeeLaterMetadataWrites() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        Acquisition first = publish();
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 2), put(2, "version", 1)));
        Acquisition second = publish();
        assertEquals(List.of(metadata(1, Map.of("version", 1L)), metadata(2, Map.of())),
                node.getMetadataAll(first, new long[] {1, 2}));
        assertEquals(List.of(metadata(1, Map.of("version", 2L)), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(second, new long[] {1, 2}));
    }

    @Test
    void metadataWritesNotCommittedAreNotInTheSnapshot() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        Writes2.of(node).commit(token);
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 2)));
        node.setTick(1);
        assertEquals(List.of(metadata(1, Map.of("version", 1L))),
                node.getMetadataAll(node.acquireLatest(P1, defaultTimeout()), new long[] {1}));
    }

    @Test
    void metadataAndPositionsChangeInTheSameCommit() {
        Acquisition before = publish();
        Writes2.of(node).setAll(token, List.of(entity(1, 5, 5)));
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        Acquisition after = publish();
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(before, new long[] {1}));
        assertEquals(List.of(metadata(1, Map.of())), node.getMetadataAll(before, new long[] {1}));
        assertEquals(List.of(entity(1, 5, 5)), node.getAll(after, new long[] {1}));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(after, new long[] {1}));
    }

    @Test
    void writeOnAnEntityNotInThePartitionIsSkippedAndDoesNotAddIt() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 2), put(99, "version", 1)));
        Acquisition acquisition = publish();
        assertEquals(3, node.size(acquisition));
        assertEquals(List.of(metadata(1, Map.of("version", 2L))), node.getMetadataAll(acquisition, new long[] {1, 99}));
    }

    @Test
    void writeBeforeTheSetThatAddsTheEntityIsSkipped() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(99, "version", 1)));
        Writes2.of(node).setAll(token, List.of(entity(99, 5, 5)));
        Writes2.of(node).updateMetadataAll(token, List.of(put(99, "owner", 7)));
        assertEquals(List.of(metadata(99, Map.of("owner", 7L))), node.getMetadataAll(publish(), new long[] {99}));
    }

    @Test
    void entityInsertedAndNotCommittedCanHaveMetadata() {
        Writes2.of(node).insertAll(token, List.of(entity(4, 4, 4)));
        Writes2.of(node).updateMetadataAll(token, List.of(put(4, "expiry", 30)));
        assertEquals(List.of(metadata(4, Map.of("expiry", 30L))), node.getMetadataAll(publish(), new long[] {4}));
    }

    @Test
    void setOfAnEntityInThePartitionKeepsItsMetadata() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        Writes2.of(node).setAll(token, List.of(entity(1, 5, 5)));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void removalOfAnEntityDeletesItsMetadata() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1), put(2, "version", 1)));
        Acquisition before = publish();
        Writes2.of(node).removeAll(token, new long[] {1});
        Writes2.of(node).setAll(token, List.of(entity(1, 1, 1)));
        Acquisition after = publish();
        assertEquals(List.of(metadata(1, Map.of()), metadata(2, Map.of("version", 1L))),
                node.getMetadataAll(after, new long[] {1, 2}));
        assertEquals(List.of(metadata(1, Map.of("version", 1L))), node.getMetadataAll(before, new long[] {1}));
    }

    @Test
    void claimDiscardsTheMetadataWritesNotCommittedAndKeepsTheCommits() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        Writes2.of(node).commit(token);
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 2), put(2, "version", 1)));
        token = node.claim(P1, token.generation()).orElseThrow();
        assertEquals(List.of(metadata(1, Map.of("version", 1L)), metadata(2, Map.of())),
                node.getMetadataAll(publish(), new long[] {1, 2}));
    }

    @Test
    void discardBeforeTheFirstCommitLeavesNoMetadata() {
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        token = node.claim(P1, token.generation()).orElseThrow();
        Writes2.of(node).insertAll(token, List.of(entity(1, 1, 1)));
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "owner", 2)));
        assertEquals(List.of(metadata(1, Map.of("owner", 2L))), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void commitOfMetadataWithAnOldTokenIsRejected() {
        Writes2.of(node).commit(token);
        WriteToken old = token;
        token = node.claim(P1, old.generation()).orElseThrow();
        Writes2.of(node).updateMetadataAll(old, List.of(put(1, "version", 1)));
        assertThrows(OwnershipChangedException.class, () -> Writes2.of(node).commit(old));
        assertEquals(List.of(metadata(1, Map.of())), node.getMetadataAll(publish(), new long[] {1}));
    }

    @Test
    void commitOfMetadataAfterTheRemovalIsRejected() {
        node.dropPartition(token);
        Writes2.of(node).updateMetadataAll(token, List.of(put(1, "version", 1)));
        assertThrows(IllegalStateException.class, () -> Writes2.of(node).commit(token));
    }

    private static ApplicationMetadata metadata(long entityId, Map<String, Long> values) {
        return new ApplicationMetadata(entityId, values);
    }

    /** Commits the writes, moves the tick and acquires the snapshot of the commit. */
    private Acquisition publish() {
        Writes2.of(node).commit(token);
        node.setTick(node.tick() + 1);
        return node.acquireLatest(P1, defaultTimeout());
    }
}
