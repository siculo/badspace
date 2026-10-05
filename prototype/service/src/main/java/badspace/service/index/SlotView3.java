package badspace.service.index;

import badspace.common.geometry.Point3;

/**
 * The entities of a 3D partition as the index sees them: slots from 0 to
 * {@code size() - 1}, each with the ID and the position of an entity. The
 * storage owns the slots and informs the index of each change (see
 * {@link SpatialIndex3}); the index reads them during the queries.
 */
public interface SlotView3 {

    /** Returns the number of entities. Slots go from 0 to size() - 1. */
    int size();

    /** Returns the ID of the entity in the slot. */
    long idAt(int slot);

    /** Returns the position of the entity in the slot. */
    Point3 positionAt(int slot);
}
