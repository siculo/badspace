package badspace;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class Partition3Test {

    @Test
    void insertAddsEntity() {
        Partition3 p = new Space3().createPartition();
        p.insert(1.0, 2.0, 3.0);
        assertEquals(1, p.size());
    }

    @Test
    void idsAreUniqueAcrossPartitionsOfSameSpace() {
        Space3 space = new Space3();
        Partition3 p1 = space.createPartition();
        Partition3 p2 = space.createPartition();
        assertNotEquals(p1.insert(0, 0, 0), p2.insert(0, 0, 0));
    }

    @Test
    void growsBeyondInitialCapacity() {
        Partition3 p = new Space3().createPartition();
        Set<Long> ids = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            ids.add(p.insert(i, -i, 2 * i));
        }
        assertEquals(1000, p.size());
        assertEquals(1000, ids.size());
    }
}
