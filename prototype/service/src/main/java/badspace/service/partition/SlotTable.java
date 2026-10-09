package badspace.service.partition;

import java.util.Arrays;

/**
 * Map from entity ID to slot, on primitive arrays, so that a copy is only
 * the copy of two arrays. It uses open addressing with linear probing: a key
 * goes in the first free place after the place chosen by its hash. A removal
 * moves back the keys that follow, so the table has no deleted places.
 * <p>
 * The table is at most half full. Slots are never negative: an empty place
 * has slot -1, so every long can be an ID. It is not thread-safe; a copy that
 * nobody changes can be read by many threads.
 */
final class SlotTable {

    /** The slot of an empty place, and the result for an ID that is not in the table. */
    static final int NONE = -1;
    private static final int INITIAL_CAPACITY = 16;

    private long[] ids;
    private int[] slots;
    private int size;

    SlotTable() {
        this(new long[INITIAL_CAPACITY], emptySlots(INITIAL_CAPACITY), 0);
    }

    private SlotTable(long[] ids, int[] slots, int size) {
        this.ids = ids;
        this.slots = slots;
        this.size = size;
    }

    /** Returns the slot of the ID, or {@link #NONE} if the ID is not in the table. */
    int get(long id) {
        int mask = slots.length - 1;
        for (int i = placeOf(id, mask); slots[i] != NONE; i = (i + 1) & mask) {
            if (ids[i] == id) {
                return slots[i];
            }
        }
        return NONE;
    }

    boolean contains(long id) {
        return get(id) != NONE;
    }

    /** Sets the slot of the ID. The slot must not be negative. */
    void put(long id, int slot) {
        if (slot < 0) {
            throw new IllegalArgumentException("Negative slot: " + slot);
        }
        if (2 * (size + 1) > slots.length) {
            grow();
        }
        int mask = slots.length - 1;
        int i = placeOf(id, mask);
        while (slots[i] != NONE && ids[i] != id) {
            i = (i + 1) & mask;
        }
        if (slots[i] == NONE) {
            size++;
        }
        ids[i] = id;
        slots[i] = slot;
    }

    /** Removes the ID and returns its slot, or {@link #NONE} if the ID was not in the table. */
    int remove(long id) {
        int mask = slots.length - 1;
        int i = placeOf(id, mask);
        while (slots[i] != NONE && ids[i] != id) {
            i = (i + 1) & mask;
        }
        int slot = slots[i];
        if (slot == NONE) {
            return NONE;
        }
        size--;
        // Moves back each following key that can be found from its own place
        // only by passing over the free place.
        int free = i;
        for (int j = (i + 1) & mask; slots[j] != NONE; j = (j + 1) & mask) {
            int home = placeOf(ids[j], mask);
            if (((j - home) & mask) >= ((j - free) & mask)) {
                ids[free] = ids[j];
                slots[free] = slots[j];
                free = j;
            }
        }
        slots[free] = NONE;
        return slot;
    }

    int size() {
        return size;
    }

    /** Returns a copy that does not change when this table changes. */
    SlotTable copy() {
        return new SlotTable(ids.clone(), slots.clone(), size);
    }

    private void grow() {
        long[] oldIds = ids;
        int[] oldSlots = slots;
        ids = new long[oldSlots.length * 2];
        slots = emptySlots(oldSlots.length * 2);
        size = 0;
        for (int i = 0; i < oldSlots.length; i++) {
            if (oldSlots[i] != NONE) {
                put(oldIds[i], oldSlots[i]);
            }
        }
    }

    /** Returns the place chosen by the hash of the ID. The bits of the ID are mixed, so near IDs go to far places. */
    private static int placeOf(long id, int mask) {
        long h = id;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    private static int[] emptySlots(int capacity) {
        int[] slots = new int[capacity];
        Arrays.fill(slots, NONE);
        return slots;
    }
}
