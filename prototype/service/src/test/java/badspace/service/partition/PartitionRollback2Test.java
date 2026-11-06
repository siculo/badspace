package badspace.service.partition;

import static badspace.service.partition.Owners.assertRejected;
import static badspace.service.partition.Owners.writes;
import static badspace.common.partition.AcquireOptions.defaultTimeout;
import static badspace.common.partition.Entity2.entity;
import static badspace.service.partition.Names.nameOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Acquisition;
import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.MetadataWrite;
import badspace.common.partition.Ownership;
import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionHealth;
import badspace.common.partition.PartitionId;
import badspace.common.partition.WriteMode;
import badspace.common.partition.WriteToken;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.Parameter;
import org.junit.jupiter.params.ParameterizedClass;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Checks the rollback of the writer of a 2D partition on the node: it discards the writes not
 * committed, also of the ticks before, keeps the commits and the ownership, and gives the same
 * state as a release and a new claim.
 */
@ParameterizedClass
@EnumSource(WriteMode.class)
class PartitionRollback2Test {

    @Parameter
    WriteMode mode;

    private static final PartitionId P1 = new PartitionId(1);
    private static final List<IndexConfig> CONFIGS = List.of(
            IndexConfig.linearScan(), IndexConfig.uniformGrid(4), IndexConfig.gridQuadtree(4, 2));
    private static final Box2 EVERYWHERE = new Box2(new Point2(-1000, -1000), new Point2(1000, 1000));
    private static final long[] IDS = {1, 2, 3};
    /** More than the IDs that the random writes use. */
    private static final int MAX_ENTITY_ID = 400;

    private LocalPartitionNode2 node;
    private WriteToken token;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode2(PartitionNodeConfig.of().withWriteMode(mode));
        node.createPartition(P1, nameOf(P1));
        token = node.claim(P1, 0).orElseThrow();
    }

    @Test
    void rollbackDiscardsAlsoTheWritesOfTheTicksBeforeAndKeepsTheOwnership() {
        writes(node, token).setAll(List.of(entity(1, 1, 1)));
        writes(node, token).updateMetadataAll(List.of(MetadataWrite.put(1, "hp", 1)));
        writes(node, token).commit();
        node.setTick(1);
        writes(node, token).setAll(List.of(entity(2, 2, 2)));
        node.setTick(2);
        writes(node, token).setAll(List.of(entity(1, 9, 9)));
        writes(node, token).updateMetadataAll(List.of(MetadataWrite.put(1, "hp", 2)));
        writes(node, token).rollback();
        assertEquals(new Ownership(1, true), node.ownership(P1));
        assertSame(PartitionHealth.HEALTHY, node.health(P1));
        Acquisition acquisition = publish();
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(acquisition, IDS));
        assertEquals(List.of(new ApplicationMetadata(1, Map.of("hp", 1L))),
                node.getMetadataAll(acquisition, new long[] {1}));
    }

    @Test
    void rollbackKeepsTheCommitThatWaitsForTheMoveOfTheTick() {
        writes(node, token).setAll(List.of(entity(1, 1, 1)));
        writes(node, token).commit();
        writes(node, token).setAll(List.of(entity(2, 2, 2)));
        writes(node, token).rollback();
        node.setTick(1);
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(node.acquire(P1, 0, defaultTimeout()), IDS));
        writes(node, token).setAll(List.of(entity(3, 3, 3)));
        assertEquals(List.of(entity(1, 1, 1), entity(3, 3, 3)), node.getAll(publish(), IDS));
    }

    @Test
    void rollbackBeforeTheFirstCommitLeavesThePartitionEmpty() {
        writes(node, token).setAll(List.of(entity(1, 1, 1), entity(2, 2, 2)));
        writes(node, token).rollback();
        assertEquals(List.of(), node.getAll(publish(), IDS));
    }

    @Test
    void rollbackWithoutWritesChangesNothing() {
        writes(node, token).setAll(List.of(entity(1, 1, 1)));
        writes(node, token).commit();
        writes(node, token).rollback();
        writes(node, token).rollback();
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(publish(), IDS));
    }

    @Test
    void rollbackWithAnOldTokenIsRefusedAndChangesNothing() {
        WriteToken second = node.claim(P1, token.generation()).orElseThrow();
        writes(node, second).setAll(List.of(entity(1, 1, 1)));
        assertThrows(OwnershipChangedException.class, () -> writes(node, token).rollback());
        writes(node, second).commit();
        node.setTick(1);
        assertEquals(List.of(entity(1, 1, 1)), node.getAll(node.acquireLatest(P1, defaultTimeout()), IDS));
    }

    @Test
    void rollbackAfterTheRemovalIsRefused() {
        node.dropPartition(token);
        assertThrows(IllegalStateException.class, () -> writes(node, token).rollback());
    }

    /**
     * The rollback and the change of the ownership use the same restore: after the same random
     * writes, a rollback on one partition and a release with a new claim on another give the
     * same snapshot and the same results for the writes after them.
     */
    @Test
    void rollbackGivesTheSameStateAsAReleaseAndAClaim() {
        for (IndexConfig config : CONFIGS) {
            LocalPartitionNode2 rolledBack = new LocalPartitionNode2(PartitionNodeConfig.of().withWriteMode(mode));
            LocalPartitionNode2 released = new LocalPartitionNode2(PartitionNodeConfig.of().withWriteMode(mode));
            rolledBack.createPartition(P1, nameOf(P1), config);
            released.createPartition(P1, nameOf(P1), config);
            WriteToken rollbackToken = rolledBack.claim(P1, 0).orElseThrow();
            WriteToken releaseToken = released.claim(P1, 0).orElseThrow();
            for (int round = 0; round < 3; round++) {
                writeRandomly(rolledBack, rollbackToken, new Random(round), 200);
                writeRandomly(released, releaseToken, new Random(round), 200);
                writes(rolledBack, rollbackToken).commit();
                writes(released, releaseToken).commit();
                writeRandomly(rolledBack, rollbackToken, new Random(~round), 100);
                writeRandomly(released, releaseToken, new Random(~round), 100);
                writes(rolledBack, rollbackToken).rollback();
                released.release(releaseToken);
                releaseToken = released.claim(P1, releaseToken.generation() + 1).orElseThrow();
                writeRandomly(rolledBack, rollbackToken, new Random(round + 100), 50);
                writeRandomly(released, releaseToken, new Random(round + 100), 50);
                String where = config + ", round " + round;
                assertEquals(content(released, releaseToken), content(rolledBack, rollbackToken), where);
            }
        }
    }

    /**
     * Makes count random writes, chosen only from the random source and the state of the
     * writer that the node gives: insertions, moves and removals of IDs up to MAX_ENTITY_ID.
     * Two partitions with the same state get the same writes from two sources with the same seed.
     */
    private static void writeRandomly(LocalPartitionNode2 target, WriteToken writer, Random random, int count) {
        for (int i = 0; i < count; i++) {
            long entityId = 1 + random.nextInt(MAX_ENTITY_ID);
            Entity2 entity = new Entity2(entityId, new Point2(random.nextInt(-100, 100), random.nextInt(-100, 100)));
            boolean remove = random.nextBoolean();
            try {
                writes(target, writer).setAll(List.of(entity));
            } catch (IllegalArgumentException present) {
                if (remove) {
                    writes(target, writer).removeAll(new long[] {entityId});
                } else {
                    writes(target, writer).setAll(List.of(entity));
                }
            }
        }
    }

    /** Commits, moves the tick and returns the entities of the snapshot, sorted by ID, and the nearest ones. */
    private static List<List<Entity2>> content(LocalPartitionNode2 target, WriteToken writer) {
        writes(target, writer).commit();
        target.setTick(target.tick() + 1);
        Acquisition acquisition = target.acquireLatest(P1, defaultTimeout());
        List<Entity2> all = new ArrayList<>(target.findInRegion(acquisition, EVERYWHERE));
        all.sort(Comparator.comparingLong(Entity2::id));
        List<Entity2> nearest = target.findNearest(acquisition, new Point2(0, 0), 10);
        target.release(acquisition);
        return List.of(all, nearest);
    }

    /** Commits, moves the tick and acquires the snapshot just published. */
    private Acquisition publish() {
        writes(node, token).commit();
        node.setTick(node.tick() + 1);
        return node.acquireLatest(P1, defaultTimeout());
    }
}
