package badspace.service.index;

import java.util.Arrays;

/** A list of slots in a growing int array, without boxing. */
final class SlotList {

    private int[] slots = new int[4];
    private int size;

    /** Adds the slot at the end and returns its place in the list. */
    int add(int slot) {
        if (size == slots.length) {
            slots = Arrays.copyOf(slots, size * 2);
        }
        slots[size] = slot;
        return size++;
    }

    int get(int place) {
        return slots[place];
    }

    void set(int place, int slot) {
        slots[place] = slot;
    }

    /** Removes the last slot and returns it. */
    int removeLast() {
        return slots[--size];
    }

    int size() {
        return size;
    }

    boolean isEmpty() {
        return size == 0;
    }

    int[] toArray() {
        return Arrays.copyOf(slots, size);
    }
}
