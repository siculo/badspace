package badspace.service.partition.slots;

import static org.junit.jupiter.api.Assertions.assertEquals;

import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.service.index.SlotView2;
import badspace.service.transaction.TransactionalComponentContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Checks the 2D slots as a transactional component. The content is the list of the entities
 * in slot order: the restore must give back the numbering of the snapshot, because the index
 * goes back to its snapshot of the same commit.
 */
class MutableSlots2Test extends TransactionalComponentContract<MutableSlots2, SlotSnapshot2> {

    // About 200 entities after many writes: more than one page of slots.
    private static final long MAX_ID = 300;
    private static final int MAX_COORDINATE = 20;

    @Override
    protected MutableSlots2 newComponent() {
        return new MutableSlots2(new CoordinateBufferPool());
    }

    @Override
    protected SlotSnapshot2 emptySnapshot() {
        return SlotSnapshot2.empty();
    }

    @Override
    protected void write(MutableSlots2 slots, Random random, long commit) {
        long entityId = random.nextLong(MAX_ID);
        Point2 position = new Point2(random.nextInt(MAX_COORDINATE), random.nextInt(MAX_COORDINATE));
        int slot = slots.slotOf(entityId);
        if (slot == SlotSnapshot2.NONE) {
            slots.insert(entityId, position, commit);
        } else if (random.nextBoolean()) {
            slots.write(slot, position, commit);
        } else {
            slots.remove(entityId, commit);
        }
    }

    @Override
    protected Object workingContent(MutableSlots2 slots) {
        return entitiesOf(slots);
    }

    @Override
    protected Object snapshotContent(SlotSnapshot2 snapshot) {
        for (int slot = 0; slot < snapshot.size(); slot++) {
            assertEquals(slot, snapshot.slotOf(snapshot.idAt(slot)), "slot of ID " + snapshot.idAt(slot));
        }
        return entitiesOf(snapshot);
    }

    @Override
    protected void checkStructure(MutableSlots2 slots) {
        for (int slot = 0; slot < slots.size(); slot++) {
            assertEquals(slot, slots.slotOf(slots.idAt(slot)), "slot of ID " + slots.idAt(slot));
        }
        for (long entityId = 0; entityId < MAX_ID; entityId++) {
            int slot = slots.slotOf(entityId);
            if (slot != SlotSnapshot2.NONE) {
                assertEquals(entityId, slots.idAt(slot), "ID in slot " + slot);
            }
        }
    }

    private static List<Entity2> entitiesOf(SlotView2 slots) {
        List<Entity2> entities = new ArrayList<>(slots.size());
        for (int slot = 0; slot < slots.size(); slot++) {
            entities.add(new Entity2(slots.idAt(slot), slots.positionAt(slot)));
        }
        return entities;
    }
}
