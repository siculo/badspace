package badspace.service.partition.slots;

import static org.junit.jupiter.api.Assertions.assertEquals;

import badspace.common.geometry.Point3;
import badspace.common.partition.Entity3;
import badspace.service.index.SlotView3;
import badspace.service.transaction.TransactionalComponentContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Checks the 3D slots as a transactional component. The content is the list of the entities
 * in slot order: the restore must give back the numbering of the snapshot, because the index
 * goes back to its snapshot of the same commit.
 */
class MutableSlots3Test extends TransactionalComponentContract<MutableSlots3, SlotSnapshot3> {

    // About 200 entities after many writes: more than one page of slots.
    private static final long MAX_ID = 300;
    private static final int MAX_COORDINATE = 20;

    @Override
    protected MutableSlots3 newComponent() {
        return new MutableSlots3(new CoordinateBufferPool());
    }

    @Override
    protected SlotSnapshot3 emptySnapshot() {
        return SlotSnapshot3.empty();
    }

    @Override
    protected void write(MutableSlots3 slots, Random random, long commit) {
        long entityId = random.nextLong(MAX_ID);
        Point3 position = new Point3(random.nextInt(MAX_COORDINATE), random.nextInt(MAX_COORDINATE),
                random.nextInt(MAX_COORDINATE));
        int slot = slots.slotOf(entityId);
        if (slot == SlotSnapshot3.NONE) {
            slots.insert(entityId, position, commit);
        } else if (random.nextBoolean()) {
            slots.write(slot, position, commit);
        } else {
            slots.remove(entityId, commit);
        }
    }

    @Override
    protected Object workingContent(MutableSlots3 slots) {
        return entitiesOf(slots);
    }

    @Override
    protected Object snapshotContent(SlotSnapshot3 snapshot) {
        for (int slot = 0; slot < snapshot.size(); slot++) {
            assertEquals(slot, snapshot.slotOf(snapshot.idAt(slot)), "slot of ID " + snapshot.idAt(slot));
        }
        return entitiesOf(snapshot);
    }

    @Override
    protected void checkStructure(MutableSlots3 slots) {
        for (int slot = 0; slot < slots.size(); slot++) {
            assertEquals(slot, slots.slotOf(slots.idAt(slot)), "slot of ID " + slots.idAt(slot));
        }
        for (long entityId = 0; entityId < MAX_ID; entityId++) {
            int slot = slots.slotOf(entityId);
            if (slot != SlotSnapshot3.NONE) {
                assertEquals(entityId, slots.idAt(slot), "ID in slot " + slot);
            }
        }
    }

    private static List<Entity3> entitiesOf(SlotView3 slots) {
        List<Entity3> entities = new ArrayList<>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            entities.add(new Entity3(slots.idAt(slot), slots.positionAt(slot)));
        }
        return entities;
    }
}
