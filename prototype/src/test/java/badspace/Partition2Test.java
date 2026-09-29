package badspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Partition2Test {

    @Test
    void insertAddsEntity() {
        Partition2 p = new Space2().createPartition();
        p.insert(1.0, 2.0);
        assertEquals(1, p.size());
    }

    @Test
    void idsAreUniqueAcrossPartitionsOfSameSpace() {
        Space2 space = new Space2();
        Partition2 p1 = space.createPartition();
        Partition2 p2 = space.createPartition();
        assertNotEquals(p1.insert(0, 0), p2.insert(0, 0));
    }

    @Test
    void growsBeyondInitialCapacity() {
        Partition2 p = new Space2().createPartition();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(p.insert(i, -i));
        }
        assertEquals(1000, p.size());
        assertEquals(1000, ids.size());
    }
}
