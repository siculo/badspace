package badspace.service.partition;

import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.Entity2.entity;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Acquisition;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.Ownership;
import badspace.common.partition.PartitionId;
import badspace.common.partition.WriteToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks the ownership of a partition on the node: generation, claim, release and the discarded writes. */
class PartitionOwnership2Test {

    private static final PartitionId P1 = new PartitionId(1);
    private static final PartitionId P2 = new PartitionId(2);
    private static final List<IndexConfig> CONFIGS = List.of(
            IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4));
    private static final Box2 EVERYWHERE = new Box2(new Point2(-1000, -1000), new Point2(1000, 1000));
    /** More than the IDs that the tests use, so that content() reads all the entities. */
    private static final int MAX_ENTITY_ID = 1000;

    private LocalPartitionNode2 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2();
        node.createPartition(P1);
    }

    @Test
    void newPartitionHasNoOwnerAtGenerationZero() {
        assertEquals(new Ownership(0, false), node.ownership(P1));
    }

    @Test
    void claimOfAFreePartitionGivesTheTokenOfTheNextGeneration() {
        assertEquals(Optional.of(new WriteToken(P1, 1)), node.claim(P1, 0));
        assertEquals(new Ownership(1, true), node.ownership(P1));
    }

    @Test
    void claimWithAnOldOrAFutureGenerationFailsAndChangesNothing() {
        node.claim(P1, 0).orElseThrow();
        assertEquals(Optional.empty(), node.claim(P1, 0));
        assertEquals(Optional.empty(), node.claim(P1, 5));
        assertEquals(new Ownership(1, true), node.ownership(P1));
    }

    @Test
    void claimWithTheGenerationOfTheOwnerGivesANewTokenAndTheOldOneCannotClaimAgain() {
        // The same call passes the ownership, if the owner makes it, or takes it away, if
        // someone else makes it: the node does not know who calls.
        WriteToken first = node.claim(P1, 0).orElseThrow();
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        assertEquals(new WriteToken(P1, 2), second);
        assertEquals(new Ownership(2, true), node.ownership(P1));
        assertEquals(Optional.empty(), node.claim(P1, first.generation()));
    }

    @Test
    void releaseLeavesThePartitionWithoutOwnerAtTheNextGeneration() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.release(token);
        assertEquals(new Ownership(2, false), node.ownership(P1));
        assertEquals(Optional.of(new WriteToken(P1, 3)), node.claim(P1, 2));
    }

    @Test
    void releaseIsIdempotentAndIgnoresTokensThatAreNotCurrent() {
        WriteToken first = node.claim(P1, 0).orElseThrow();
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        node.release(first);
        assertEquals(new Ownership(2, true), node.ownership(P1));
        node.release(second);
        node.release(second);
        assertEquals(new Ownership(3, false), node.ownership(P1));
        // A token with the generation of a free partition names no owner.
        node.release(new WriteToken(P1, 3));
        assertEquals(new Ownership(3, false), node.ownership(P1));
    }

    @Test
    void claimDiscardsTheWritesNotCommittedAndKeepsTheCommits() {
        for (IndexConfig config : CONFIGS) {
            setUp();
            node.createPartition(P2, config);
            node.insertAll(P2, List.of(entity(1, 1, 1), entity(2, 2, 2), entity(3, 3, 3)));
            node.commit(P2);
            node.insertAll(P2, List.of(entity(4, 4, 4)));
            node.updateAll(P2, List.of(entity(1, 9, 9)));
            node.removeAll(P2, new long[] {2});
            node.claim(P2, 0).orElseThrow();
            assertEquals(List.of(entity(1, 1, 1), entity(2, 2, 2), entity(3, 3, 3)), content(publish(P2)), config.toString());
        }
    }

    @Test
    void releaseDiscardsTheWritesNotCommitted() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.commit(P1);
        node.updateAll(P1, List.of(entity(1, 5, 5)));
        node.insertAll(P1, List.of(entity(2, 2, 2)));
        // The owner can decide not to commit and leave the ownership.
        node.release(token);
        assertEquals(List.of(entity(1, 1, 1)), content(publish(P1)));
    }

    @Test
    void discardBeforeTheFirstCommitLeavesThePartitionEmpty() {
        node.insertAll(P1, List.of(entity(1, 1, 1), entity(2, 2, 2)));
        node.claim(P1, 0).orElseThrow();
        assertEquals(List.of(), content(publish(P1)));
    }

    @Test
    void newOwnerStartsFromTheCommitsOfTheCurrentTick() {
        node.insertAll(P1, List.of(entity(1, 1, 1)));
        node.commit(P1);
        node.claim(P1, 0).orElseThrow();
        node.insertAll(P1, List.of(entity(2, 2, 2)));
        node.commit(P1);
        node.setTick(1);
        // Both commits are of tick 0: the last one has the state confirmed by the first.
        Acquisition acquisition = node.acquire(P1, 0, defaultTimeout());
        assertEquals(List.of(entity(1, 1, 1), entity(2, 2, 2)), content(acquisition));
    }

    @Test
    void discardAfterRandomWritesGivesTheCommittedStateAndTheIndexStaysConsistent() {
        for (IndexConfig config : CONFIGS) {
            setUp();
            node.createPartition(P2, config);
            Random random = new Random(42);
            Map<Long, Entity2> model = new LinkedHashMap<>();
            long nextId = 1;
            for (int round = 0; round < 3; round++) {
                nextId = writeRandomly(random, model, nextId, 200);
                node.commit(P2);
                // Writes that the claim discards; the next round uses new IDs after them.
                nextId = writeRandomly(random, new LinkedHashMap<>(model), nextId, 100);
                node.claim(P2, node.ownership(P2).generation()).orElseThrow();
                Acquisition acquisition = publish(P2);
                String where = config + ", round " + round;
                assertEquals(sorted(model.values()), content(acquisition), where);
                assertEquals(sorted(model.values()), sorted(node.findInRegion(acquisition, EVERYWHERE)), where);
            }
        }
    }

    @Test
    void negativeExpectedGenerationIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> node.claim(P1, -1));
        assertEquals(new Ownership(0, false), node.ownership(P1));
    }

    @Test
    void claimAfterTheRemovalIsRejectedAndReleaseDoesNothing() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.removePartition(P1);
        assertThrows(IllegalStateException.class, () -> node.claim(P1, token.generation()));
        node.release(token);
        assertEquals(new Ownership(1, true), node.ownership(P1));
        node.setTick(1);
        assertThrows(IllegalStateException.class, () -> node.claim(P1, token.generation()));
        assertEquals(new Ownership(1, true), node.ownership(P1));
    }

    @Test
    void destroyedPartitionIsLikeAnUnknownOne() {
        node.setTick(1);
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.removePartition(P1);
        node.setTick(2);
        node.setTick(3);
        assertThrows(IllegalArgumentException.class, () -> node.ownership(P1));
        assertThrows(IllegalArgumentException.class, () -> node.claim(P1, 1));
        assertDoesNotThrow(() -> node.release(token));
        assertThrows(IllegalArgumentException.class, () -> node.ownership(P2));
        assertThrows(IllegalArgumentException.class, () -> node.claim(P2, 0));
        assertDoesNotThrow(() -> node.release(new WriteToken(P2, 1)));
    }

    /**
     * Makes count random writes on P2 and applies them to the model too: insertions with new
     * IDs from nextId, moves and removals. Returns the next free ID.
     */
    private long writeRandomly(Random random, Map<Long, Entity2> model, long nextId, int count) {
        for (int i = 0; i < count; i++) {
            int choice = model.isEmpty() ? 0 : random.nextInt(3);
            if (choice == 0) {
                Entity2 entity = new Entity2(nextId++, randomPoint(random));
                node.insertAll(P2, List.of(entity));
                model.put(entity.id(), entity);
            } else {
                long entityId = new ArrayList<>(model.keySet()).get(random.nextInt(model.size()));
                if (choice == 1) {
                    Entity2 entity = new Entity2(entityId, randomPoint(random));
                    node.updateAll(P2, List.of(entity));
                    model.put(entityId, entity);
                } else {
                    node.removeAll(P2, new long[] {entityId});
                    model.remove(entityId);
                }
            }
        }
        return nextId;
    }

    private static Point2 randomPoint(Random random) {
        return new Point2(random.nextDouble(-100, 100), random.nextDouble(-100, 100));
    }

    /** Returns the entities of the snapshot, sorted by ID. */
    private List<Entity2> content(Acquisition acquisition) {
        long[] entityIds = new long[MAX_ENTITY_ID];
        for (int i = 0; i < entityIds.length; i++) {
            entityIds[i] = i + 1;
        }
        return node.getAll(acquisition, entityIds);
    }

    private static List<Entity2> sorted(Iterable<Entity2> entities) {
        List<Entity2> result = new ArrayList<>();
        entities.forEach(result::add);
        result.sort(Comparator.comparingLong(Entity2::id));
        return result;
    }

    /**
     * Commits the writes of a partition, moves the tick of the node by one and acquires the
     * snapshot just published.
     */
    private Acquisition publish(PartitionId partitionId) {
        node.commit(partitionId);
        node.setTick(node.tick() + 1);
        return node.acquireLatest(partitionId, defaultTimeout());
    }
}
