package badspace.common.partition;

import badspace.common.geometry.Point3;

/** An entity of a 3D space: its ID and its position. */
public record Entity3(long id, Point3 position) {

    /**
     * Returns the entity with the given ID at the point (x, y, z), shorter to use with an
     * import static, for example in tests and clients. Fails like the constructor of Point3.
     */
    public static Entity3 entity(long id, double x, double y, double z) {
        return new Entity3(id, new Point3(x, y, z));
    }

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
