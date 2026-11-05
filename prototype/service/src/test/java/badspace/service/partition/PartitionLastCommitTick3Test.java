package badspace.service.partition;

import static badspace.service.partition.Owners.writes;
import static badspace.common.partition.Entity3.entity;
import static badspace.service.partition.Names.nameOf;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.OwnershipChangedException;
import badspace.common.partition.PartitionId;
import badspace.common.partition.WriteToken;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Checks the tick of the last commit of a 3D partition on the node: the creation counts as a
 * commit at the creation tick, only the commits of the owner change it, and only the owner
 * reads it, so a new owner knows whether the current tick already has a commit.
 */
class PartitionLastCommitTick3Test {

    private static final PartitionId P1 = new PartitionId(1);

    private LocalPartitionNode3 node;

    @BeforeEach
    void setUp() {
        node = new LocalPartitionNode3();
        node.setTick(5);
        node.createPartition(P1, nameOf(P1));
    }

    @Test
    void creationCountsAsACommitAtTheCreationTick() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        assertEquals(5, node.lastCommitTick(token));
        node.setTick(8);
        assertEquals(5, node.lastCommitTick(token));
    }

    @Test
    void commitGivesTheCurrentTickAndTheMoveOfTheTickKeepsIt() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.setTick(6);
        writes(node, token).setAll(List.of(entity(1, 1, 1, 1)));
        writes(node, token).commit();
        assertEquals(6, node.lastCommitTick(token));
        node.setTick(9);
        assertEquals(6, node.lastCommitTick(token));
        writes(node, token).commit();
        assertEquals(9, node.lastCommitTick(token));
    }

    @Test
    void writesAndRollbackDoNotChangeIt() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.setTick(6);
        writes(node, token).commit();
        node.setTick(7);
        writes(node, token).setAll(List.of(entity(1, 1, 1, 1)));
        assertEquals(6, node.lastCommitTick(token));
        writes(node, token).rollback();
        assertEquals(6, node.lastCommitTick(token));
    }

    /**
     * A writer stops after or before its commit of the tick; a coordinator gives the partition
     * to a new writer, which compares the value with the current tick.
     */
    @Test
    void newOwnerKnowsWhetherTheStoppedWriterCommittedInTheCurrentTick() {
        WriteToken stopped = node.claim(P1, 0).orElseThrow();
        node.setTick(6);
        writes(node, stopped).setAll(List.of(entity(1, 1, 1, 1)));
        writes(node, stopped).commit();
        WriteToken next = node.claim(P1, stopped.generation()).orElseThrow();
        assertEquals(node.tick(), node.lastCommitTick(next));

        node.setTick(7);
        writes(node, next).setAll(List.of(entity(2, 2, 2, 2)));
        WriteToken last = node.claim(P1, next.generation()).orElseThrow();
        assertEquals(6, node.lastCommitTick(last));
    }

    @Test
    void tokenThatIsNotCurrentIsRefused() {
        WriteToken first = node.claim(P1, 0).orElseThrow();
        WriteToken second = node.claim(P1, first.generation()).orElseThrow();
        assertThrows(OwnershipChangedException.class, () -> node.lastCommitTick(first));
        node.release(second);
        assertThrows(OwnershipChangedException.class, () -> node.lastCommitTick(second));
    }

    @Test
    void removedPartitionIsRefused() {
        WriteToken token = node.claim(P1, 0).orElseThrow();
        node.dropPartition(token);
        assertThrows(IllegalStateException.class, () -> node.lastCommitTick(token));
    }
}
