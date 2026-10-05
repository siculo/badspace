package badspace.service.index;

import badspace.common.geometry.Box3;
import badspace.common.geometry.Point3;
import badspace.common.geometry.Region3;
import badspace.common.geometry.Sphere3;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Index that divides the space in cubic cells, like the uniform grid, and
 * keeps the entities of each cell in an octree: a leaf with too many
 * entities is split in 8 equal octants, so the index adapts to the density.
 * Only the cells with entities are in memory, so the space has no limits.
 * <p>
 * The cell size is a power of 2, so the borders of the cells and of the
 * octants are exact: an entity is in a node if and only if its position is
 * in the box of the node. A leaf is split when it has more than
 * {@code leafCapacity} entities, and a node becomes a leaf again when it has
 * {@code leafCapacity / 2} entities or fewer: the gap keeps an entity that
 * moves on a border from splitting and merging a node at each move. A leaf is
 * not split when all its entities are in the same position, because no split
 * can divide them: many entities in the same position stay in one leaf and do
 * not make a long chain of nodes. A leaf is also not split below
 * {@link #MAX_DEPTH} levels, or when the halves of its box would not be exact.
 * <p>
 * A range query reads the cells that cover the region, and in each cell only
 * the nodes that touch the region; a node inside the region is taken whole. A
 * k-nearest query reads the nodes from the nearest to the farthest, and adds
 * the cells in shells around the point when they can be near enough.
 * <p>
 * The storage keeps the entities in the limits of the index, at most
 * {@code IndexConfig.CELLS_PER_SIDE} cells from the origin on each axis, so
 * each entity is in a cell with int coordinates. The regions and the points
 * of the queries can be outside the limits.
 */
final class GridOctreeIndex3 implements SpatialIndex3 {

    /** Levels of octants below a cell, at most. */
    static final int MAX_DEPTH = 24;

    private static final int CHILDREN = 8;

    private final SlotView3 storage;
    private final double cellSize;
    private final int leafCapacity;
    private final int mergeLimit;
    /** The root node of each cell with entities. */
    private final Map<CellKey, Node> cells = new HashMap<>();
    /** For each slot, its leaf. */
    private Node[] leafOf = new Node[16];
    /** For each slot, its place in the list of its leaf. */
    private int[] placeOf = new int[16];

    /** The cell size must be a power of 2 and the leaf capacity positive, as in {@code IndexConfig.GridQuadtree}. */
    GridOctreeIndex3(SlotView3 storage, double cellSize, int leafCapacity) {
        this.storage = storage;
        this.cellSize = cellSize;
        this.leafCapacity = leafCapacity;
        this.mergeLimit = leafCapacity / 2;
    }

    @Override
    public void inserted(int slot, Point3 position) {
        if (slot >= placeOf.length) {
            int length = Math.max(slot + 1, placeOf.length * 2);
            leafOf = Arrays.copyOf(leafOf, length);
            placeOf = Arrays.copyOf(placeOf, length);
        }
        add(slot, position);
    }

    @Override
    public void moved(int slot, Point3 from, Point3 to) {
        Node leaf = leafOf[slot];
        if (leaf.contains(to)) {
            if (leaf.stackedAt != null && !samePosition(leaf.stackedAt, to)) {
                leaf.stackedAt = null;
                splitIfFull(leaf);
            }
            return;
        }
        // Only the nodes below the first node that contains both positions
        // change their counts. A move to another cell goes through the map
        // of the cells.
        Node common = leaf.parent;
        while (common != null && !common.contains(to)) {
            common = common.parent;
        }
        if (common == null) {
            remove(slot);
            add(slot, to);
            return;
        }
        detach(slot, common);
        // The leaf does not contain the new position, so the common node is
        // above it and has children; a merge changes only the nodes below
        // the child of the old position, so the common node keeps them.
        descend(common.children[common.childIndex(to)], slot, to);
    }

    @Override
    public void removed(int slot, Point3 position) {
        remove(slot);
    }

    @Override
    public void relocated(int from, int to, Point3 position) {
        Node leaf = leafOf[from];
        int place = placeOf[from];
        leaf.slots.set(place, to);
        leafOf[to] = leaf;
        placeOf[to] = place;
        leafOf[from] = null;
    }

    @Override
    public int[] findInRegion(Region3 region) {
        SlotList found = new SlotList();
        CellRange range = switch (region) {
            case Box3 box -> new CellRange(
                    cellOf(box.min().x()), cellOf(box.max().x()),
                    cellOf(box.min().y()), cellOf(box.max().y()),
                    cellOf(box.min().z()), cellOf(box.max().z()));
            // When the square of the radius overflows, the sphere contains each
            // point whose distance overflows too: all the cells must be read.
            case Sphere3 sphere when sphere.radius() * sphere.radius() == Double.POSITIVE_INFINITY -> CellRange.ALL;
            // A point on the border of the sphere can be just outside the
            // box around it, because of rounding: one more cell on each side
            // keeps it in the range. The nodes then check the exact distance.
            case Sphere3 sphere -> new CellRange(
                    cellOf(sphere.center().x() - sphere.radius()) - 1,
                    cellOf(sphere.center().x() + sphere.radius()) + 1,
                    cellOf(sphere.center().y() - sphere.radius()) - 1,
                    cellOf(sphere.center().y() + sphere.radius()) + 1,
                    cellOf(sphere.center().z() - sphere.radius()) - 1,
                    cellOf(sphere.center().z() + sphere.radius()) + 1);
        };
        if (range.cellCount() <= cells.size()) {
            for (long cx = range.minX; cx <= range.maxX; cx++) {
                for (long cy = range.minY; cy <= range.maxY; cy++) {
                    for (long cz = range.minZ; cz <= range.maxZ; cz++) {
                        Node root = cells.get(new CellKey((int) cx, (int) cy, (int) cz));
                        if (root != null) {
                            collect(root, region, found);
                        }
                    }
                }
            }
        } else {
            for (Map.Entry<CellKey, Node> cell : cells.entrySet()) {
                if (range.contains(cell.getKey())) {
                    collect(cell.getValue(), region, found);
                }
            }
        }
        return found.toArray();
    }

    @Override
    public int[] findNearest(Point3 point, int count) {
        NearestSlots nearest = new NearestSlots(count);
        PriorityQueue<NodeDistance> queue =
                new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::distanceSquared));
        long x = cellOf(point.x());
        long y = cellOf(point.y());
        long z = cellOf(point.z());
        // A point far outside the limits has no shells: all the cells go in the queue.
        boolean allCellsQueued = !isInt(x) || !isInt(y) || !isInt(z);
        if (allCellsQueued) {
            for (Node root : cells.values()) {
                queue.add(new NodeDistance(root, minDistanceSquared(root, point)));
            }
        }
        long shell = 0;
        while (true) {
            double nodeLimit = queue.isEmpty() ? Double.POSITIVE_INFINITY : queue.peek().distanceSquared();
            double shellLimit = allCellsQueued ? Double.POSITIVE_INFINITY : shellDistanceSquared(shell);
            if (nearest.isComplete(Math.min(nodeLimit, shellLimit))) {
                break;
            }
            if (!allCellsQueued && shellLimit <= nodeLimit) {
                // Shells get larger and larger: when a shell and the shells inside
                // it have more cells than the grid, it is faster to queue the
                // cells of the grid that are not queued yet.
                if (Math.pow(2 * shell + 1.0, 3) > cells.size()) {
                    for (Map.Entry<CellKey, Node> cell : cells.entrySet()) {
                        if (shellOf(cell.getKey(), x, y, z) >= shell) {
                            queue.add(new NodeDistance(cell.getValue(), minDistanceSquared(cell.getValue(), point)));
                        }
                    }
                    allCellsQueued = true;
                } else {
                    queueShell(queue, point, x, y, z, shell);
                    shell++;
                }
                continue;
            }
            if (queue.isEmpty()) {
                break;
            }
            Node node = queue.poll().node();
            if (node.isLeaf()) {
                for (int i = 0; i < node.slots.size(); i++) {
                    offer(nearest, point, node.slots.get(i));
                }
            } else {
                for (Node child : node.children) {
                    if (child.count > 0) {
                        queue.add(new NodeDistance(child, minDistanceSquared(child, point)));
                    }
                }
            }
        }
        return nearest.slots();
    }

    /**
     * Returns the cell coordinate of a space coordinate. A coordinate of a
     * query that is too large for a cell gives a value outside the int range.
     */
    private long cellOf(double coordinate) {
        double cell = Math.floor(coordinate / cellSize);
        if (!(cell >= Integer.MIN_VALUE && cell <= Integer.MAX_VALUE)) {
            return cell < 0 ? Integer.MIN_VALUE - 1L : Integer.MAX_VALUE + 1L;
        }
        // The division by a power of 2 is exact, unless the result is too
        // small for a double: then a small negative coordinate gives -0.0.
        return cell == 0 && coordinate < 0 ? -1 : (long) cell;
    }

    private void add(int slot, Point3 position) {
        long x = cellOf(position.x());
        long y = cellOf(position.y());
        long z = cellOf(position.z());
        // The limits of the index keep the cell coordinates in the int range.
        Node node = cells.computeIfAbsent(new CellKey((int) x, (int) y, (int) z),
                key -> new Node(null, key.x * cellSize, key.y * cellSize, key.z * cellSize, cellSize, 0));
        descend(node, slot, position);
    }

    /** Adds the slot to the leaf of the position below the node, and counts it in each node on the way. */
    private void descend(Node node, int slot, Point3 position) {
        while (!node.isLeaf()) {
            node.count++;
            node = node.children[node.childIndex(position)];
        }
        addToLeaf(node, slot, position);
        splitIfFull(node);
    }

    private void addToLeaf(Node leaf, int slot, Point3 position) {
        if (leaf.stackedAt != null && !samePosition(leaf.stackedAt, position)) {
            leaf.stackedAt = null;
        }
        leaf.count++;
        leafOf[slot] = leaf;
        placeOf[slot] = leaf.slots.add(slot);
    }

    /** Removes the slot from its leaf, then merges the nodes that have few entities and drops an empty cell. */
    private void remove(int slot) {
        Node root = detach(slot, null);
        if (root.count == 0) {
            cells.remove(new CellKey(
                    (int) (root.minX / cellSize), (int) (root.minY / cellSize), (int) (root.minZ / cellSize)));
        }
    }

    /**
     * Removes the slot from its leaf and from the counts of the nodes on the
     * way up, until the node {@code stop}, which keeps its count; with stop
     * null, until the root of the cell. Then merges the nodes that have few
     * entities. Returns the highest node whose count changed.
     */
    private Node detach(int slot, Node stop) {
        Node leaf = leafOf[slot];
        SlotList list = leaf.slots;
        int place = placeOf[slot];
        int last = list.removeLast();
        if (last != slot) {
            list.set(place, last);
            placeOf[last] = place;
        }
        leafOf[slot] = null;
        // The counts grow from the leaf to the root, so the nodes to merge
        // are at the start of the path; the highest one takes them all.
        Node toMerge = null;
        Node highest = leaf;
        for (Node node = leaf; node != stop; node = node.parent) {
            node.count--;
            if (!node.isLeaf() && node.count <= mergeLimit) {
                toMerge = node;
            }
            highest = node;
        }
        if (toMerge != null) {
            merge(toMerge);
        }
        return highest;
    }

    private void splitIfFull(Node leaf) {
        if (leaf.slots.size() <= leafCapacity || leaf.depth >= MAX_DEPTH
                || !halvesAreExact(leaf.minX, leaf.side) || !halvesAreExact(leaf.minY, leaf.side)
                || !halvesAreExact(leaf.minZ, leaf.side)) {
            return;
        }
        // The check reads all the entities, but only once: a stacked leaf
        // stops here until an entity in another position comes in.
        if (leaf.stackedAt != null || isStacked(leaf)) {
            return;
        }
        SlotList slots = leaf.slots;
        double half = leaf.side / 2;
        leaf.slots = null;
        leaf.children = new Node[CHILDREN];
        for (int i = 0; i < CHILDREN; i++) {
            leaf.children[i] = new Node(leaf, leaf.minX + (i & 1) * half, leaf.minY + (i >> 1 & 1) * half,
                    leaf.minZ + (i >> 2) * half, half, leaf.depth + 1);
        }
        for (int i = 0; i < slots.size(); i++) {
            int slot = slots.get(i);
            Node child = leaf.children[leaf.childIndex(storage.positionAt(slot))];
            child.count++;
            leafOf[slot] = child;
            placeOf[slot] = child.slots.add(slot);
        }
        for (Node child : leaf.children) {
            splitIfFull(child);
        }
    }

    /**
     * Returns true, and marks the leaf, if all its entities are in the same
     * position.
     */
    private boolean isStacked(Node leaf) {
        Point3 first = storage.positionAt(leaf.slots.get(0));
        for (int i = 1; i < leaf.slots.size(); i++) {
            if (!samePosition(first, storage.positionAt(leaf.slots.get(i)))) {
                return false;
            }
        }
        leaf.stackedAt = first;
        return true;
    }

    /**
     * Returns true if the two positions are the same for the index. It
     * compares the coordinates with ==, so 0.0 and -0.0 are the same: they
     * always go in the same child, so a split cannot divide them.
     */
    private static boolean samePosition(Point3 a, Point3 b) {
        return a.x() == b.x() && a.y() == b.y() && a.z() == b.z();
    }

    /**
     * Fails with IllegalStateException if the structure is not consistent:
     * the counts of the nodes, the leaf and place of each slot, the nodes
     * that should be merged, and the stacked leaves. Only for the tests.
     */
    void checkStructure() {
        int total = 0;
        for (Node root : cells.values()) {
            if (root.count == 0) {
                throw new IllegalStateException("Empty cell");
            }
            total += checkNode(root);
        }
        int slots = 0;
        for (int slot = 0; slot < leafOf.length; slot++) {
            if (leafOf[slot] != null) {
                slots++;
                if (leafOf[slot].slots.get(placeOf[slot]) != slot) {
                    throw new IllegalStateException("Wrong place of slot " + slot);
                }
            }
        }
        if (total != slots) {
            throw new IllegalStateException("Entities in the leaves: " + total + ", slots with a leaf: " + slots);
        }
    }

    /** Checks the subtree and returns its number of entities. */
    private int checkNode(Node node) {
        int count;
        if (node.isLeaf()) {
            count = node.slots.size();
            for (int i = 0; i < count; i++) {
                int slot = node.slots.get(i);
                Point3 position = storage.positionAt(slot);
                if (leafOf[slot] != node || !node.contains(position)) {
                    throw new IllegalStateException("Slot " + slot + " is in the wrong leaf");
                }
                if (node.stackedAt != null && !samePosition(node.stackedAt, position)) {
                    throw new IllegalStateException("Stacked leaf with another position: " + position);
                }
            }
        } else {
            if (node.count <= mergeLimit) {
                throw new IllegalStateException("Node to merge with " + node.count + " entities");
            }
            count = 0;
            for (Node child : node.children) {
                count += checkNode(child);
            }
        }
        if (count != node.count) {
            throw new IllegalStateException("Count " + node.count + " instead of " + count);
        }
        return count;
    }

    /** Returns the depth of the deepest leaf, or -1 if the index is empty. Only for the tests. */
    int maxDepth() {
        int max = -1;
        for (Node root : cells.values()) {
            max = Math.max(max, maxDepth(root));
        }
        return max;
    }

    private static int maxDepth(Node node) {
        if (node.isLeaf()) {
            return node.depth;
        }
        int max = node.depth;
        for (Node child : node.children) {
            max = Math.max(max, maxDepth(child));
        }
        return max;
    }

    /** Makes the node a leaf with all the entities of its subtree. */
    private void merge(Node node) {
        SlotList slots = new SlotList();
        collectAll(node, slots);
        node.children = null;
        node.slots = slots;
        for (int i = 0; i < slots.size(); i++) {
            leafOf[slots.get(i)] = node;
            placeOf[slots.get(i)] = i;
        }
    }

    /**
     * Returns true if the borders of the two halves of [min, min + side) are
     * exact doubles. The side is a power of 2 and min is a multiple of it, so
     * the middle is exact when the doubles near the box are not farther apart
     * than half the side.
     */
    private static boolean halvesAreExact(double min, double side) {
        return Math.ulp(Math.max(Math.abs(min), Math.abs(min + side))) <= side / 2;
    }

    /** Adds the slots of the node inside the region to the list. */
    private void collect(Node node, Region3 region, SlotList found) {
        if (node.count == 0 || !touches(node, region)) {
            return;
        }
        if (isInside(node, region)) {
            collectAll(node, found);
        } else if (node.isLeaf()) {
            for (int i = 0; i < node.slots.size(); i++) {
                int slot = node.slots.get(i);
                if (region.contains(storage.positionAt(slot))) {
                    found.add(slot);
                }
            }
        } else {
            for (Node child : node.children) {
                collect(child, region, found);
            }
        }
    }

    /** Adds all the slots of the subtree to the list. */
    private static void collectAll(Node node, SlotList found) {
        if (node.isLeaf()) {
            for (int i = 0; i < node.slots.size(); i++) {
                found.add(node.slots.get(i));
            }
        } else {
            for (Node child : node.children) {
                collectAll(child, found);
            }
        }
    }

    /**
     * Returns false if no point of the box of the node can be in the region.
     * The tests on the sphere are safe with rounding: rounding never makes a
     * distance smaller when the exact distance is larger, so a point of the
     * node is never nearer to the center than the computed limit.
     */
    private static boolean touches(Node node, Region3 region) {
        return switch (region) {
            case Box3 box -> node.minX <= box.max().x() && node.maxX() > box.min().x()
                    && node.minY <= box.max().y() && node.maxY() > box.min().y()
                    && node.minZ <= box.max().z() && node.maxZ() > box.min().z();
            case Sphere3 sphere -> !(minDistanceSquared(node, sphere.center()) > sphere.radius() * sphere.radius());
        };
    }

    /** Returns true if all the points of the box of the node are in the region. */
    private static boolean isInside(Node node, Region3 region) {
        return switch (region) {
            case Box3 box -> node.minX >= box.min().x() && node.maxX() <= box.max().x()
                    && node.minY >= box.min().y() && node.maxY() <= box.max().y()
                    && node.minZ >= box.min().z() && node.maxZ() <= box.max().z();
            case Sphere3 sphere -> {
                Point3 c = sphere.center();
                double dx = Math.max(c.x() - node.minX, node.maxX() - c.x());
                double dy = Math.max(c.y() - node.minY, node.maxY() - c.y());
                double dz = Math.max(c.z() - node.minZ, node.maxZ() - c.z());
                yield dx * dx + dy * dy + dz * dz <= sphere.radius() * sphere.radius();
            }
        };
    }

    /**
     * Returns a squared distance that the entities of the node cannot be
     * nearer than. It uses the same operations as {@link Point3#distanceSquared},
     * so it is never larger than the computed distance of an entity.
     */
    private static double minDistanceSquared(Node node, Point3 point) {
        double dx = gap(point.x(), node.minX, node.maxX());
        double dy = gap(point.y(), node.minY, node.maxY());
        double dz = gap(point.z(), node.minZ, node.maxZ());
        return dx * dx + dy * dy + dz * dz;
    }

    /** Returns the distance from the coordinate to [min, max), or 0 if it is inside. */
    private static double gap(double coordinate, double min, double max) {
        if (coordinate < min) {
            return min - coordinate;
        }
        return coordinate >= max ? coordinate - max : 0;
    }

    /**
     * Returns a squared distance that the entities in the shell, or in a shell
     * outside it, cannot be nearer than. The point can be anywhere in its own
     * cell, so the gap is one cell less than the shell. The cell borders are
     * exact, so no margin is needed.
     */
    private double shellDistanceSquared(long shell) {
        double gap = Math.max(0, shell - 1) * cellSize;
        return gap * gap;
    }

    /** Adds to the queue the cells of the shell around the cell (x, y, z). */
    private void queueShell(PriorityQueue<NodeDistance> queue, Point3 point, long x, long y, long z, long shell) {
        for (long cx = x - shell; cx <= x + shell; cx++) {
            for (long cy = y - shell; cy <= y + shell; cy++) {
                boolean side = Math.abs(cx - x) == shell || Math.abs(cy - y) == shell;
                for (long cz = z - shell; cz <= z + shell; cz += side ? 1 : 2 * shell) {
                    if (isInt(cx) && isInt(cy) && isInt(cz)) {
                        Node root = cells.get(new CellKey((int) cx, (int) cy, (int) cz));
                        if (root != null) {
                            queue.add(new NodeDistance(root, minDistanceSquared(root, point)));
                        }
                    }
                }
            }
        }
    }

    private void offer(NearestSlots nearest, Point3 point, int slot) {
        nearest.offer(storage.positionAt(slot).distanceSquared(point), storage.idAt(slot), slot);
    }


    /**
     * Returns the shell of the cell around the cell (x, y, z): the largest
     * distance between them on one axis, in cells.
     */
    private static long shellOf(CellKey key, long x, long y, long z) {
        return Math.max(Math.abs(key.x - x), Math.max(Math.abs(key.y - y), Math.abs(key.z - z)));
    }

    private static boolean isInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    /** The coordinates of a cell. */
    private record CellKey(int x, int y, int z) {
    }

    /** A node and its distance from the point of a k-nearest query. */
    private record NodeDistance(Node node, double distanceSquared) {
    }

    /**
     * A node of the octree of a cell, with the box [minX, minX + side) x
     * [minY, minY + side) x [minZ, minZ + side). A leaf has slots and no
     * children; the other nodes have 8 children and no slots. Child i has the
     * high half of x if bit 0 of i is set, of y if bit 1 is set and of z if
     * bit 2 is set.
     */
    private static final class Node {

        final Node parent;
        final double minX;
        final double minY;
        final double minZ;
        final double side;
        final int depth;
        Node[] children;
        SlotList slots = new SlotList();
        /**
         * For a leaf, the position of all its entities when they are in the
         * same position and too many to stay in a leaf: then the leaf is not
         * split. Null in all the other cases.
         */
        Point3 stackedAt;
        /** Number of entities in the subtree. */
        int count;

        Node(Node parent, double minX, double minY, double minZ, double side, int depth) {
            this.parent = parent;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.side = side;
            this.depth = depth;
        }

        boolean isLeaf() {
            return children == null;
        }

        double maxX() {
            return minX + side;
        }

        double maxY() {
            return minY + side;
        }

        double maxZ() {
            return minZ + side;
        }

        boolean contains(Point3 p) {
            return p.x() >= minX && p.x() < maxX() && p.y() >= minY && p.y() < maxY()
                    && p.z() >= minZ && p.z() < maxZ();
        }

        int childIndex(Point3 p) {
            double half = side / 2;
            return (p.x() < minX + half ? 0 : 1) + (p.y() < minY + half ? 0 : 2) + (p.z() < minZ + half ? 0 : 4);
        }
    }

    /**
     * The cells from min to max on each axis, limits included. Both limits are
     * moved into the int range, so min is never larger than max.
     */
    private record CellRange(long minX, long maxX, long minY, long maxY, long minZ, long maxZ) {

        /** All the cells of the int range. */
        static final CellRange ALL = new CellRange(
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE);

        CellRange {
            minX = toInt(minX);
            maxX = toInt(maxX);
            minY = toInt(minY);
            maxY = toInt(maxY);
            minZ = toInt(minZ);
            maxZ = toInt(maxZ);
        }

        private static long toInt(long value) {
            return Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        /** Returns the number of cells, as a double so that it cannot overflow. */
        double cellCount() {
            return (maxX - minX + 1.0) * (maxY - minY + 1.0) * (maxZ - minZ + 1.0);
        }

        boolean contains(CellKey key) {
            return key.x >= minX && key.x <= maxX && key.y >= minY && key.y <= maxY
                    && key.z >= minZ && key.z <= maxZ;
        }
    }
}
