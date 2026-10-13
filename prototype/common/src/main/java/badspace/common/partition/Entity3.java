package badspace.common.partition;

import badspace.common.geometry.Point3;

/** An entity of a 3D space: its ID and its position. */
public record Entity3(long id, Point3 position) {

    /** Returns an entity with the same ID and the given position. */
    public Entity3 withPosition(Point3 position) {
        return new Entity3(id, position);
    }

    /**
     * Returns an entity with the same ID, moved by dx on the x axis, by dy on the y axis
     * and by dz on the z axis. Fails like {@link Point3#add(double, double, double)}.
     */
    public Entity3 moveBy(double dx, double dy, double dz) {
        return withPosition(position.add(dx, dy, dz));
    }
}
