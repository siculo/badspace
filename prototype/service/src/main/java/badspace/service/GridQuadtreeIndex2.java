package badspace.service;

import badspace.common.Box2;
import badspace.common.Circle2;
import badspace.common.Point2;
import badspace.common.Region2;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Index that divides the space in square cells, like the uniform grid, and
 * keeps the entities of each cell in a quadtree: a leaf with too many
 * entities is split in 4 equal quadrants, so the index adapts to the density.
 * Only the cells with entities are in memory, so the space has no limits.
 * <p>
 * The cell size is a power of 2, so the borders of the cells and of the
 * quadrants are exact: an entity is in a node if and only if its position is
 * in the box of the node. A leaf is split when it has more than
 * {@code leafCapacity} entities, and a node becomes a leaf again when it has
 * {@code leafCapacity / 2} entities or fewer: the gap keeps an entity that
 * moves on a border from splitting and merging a node at each move. A leaf is
 * not split below {@link #MAX_DEPTH} levels, or when the halves of its box
 * would not be exact, so many entities in the same position stay in one leaf.
 * <p>
 * A range query reads the cells that cover the region, and in each cell only
 * the nodes that touch the region; a node inside the region is taken whole. A
 * k-nearest query reads the nodes from the nearest to the farthest, and adds
 * the cells in rings around the point when they can be near enough.
 * <p>
 * The storage keeps the entities in the limits of the index, at most
 * {@code IndexConfig.CELLS_PER_SIDE} cells from the origin on each axis, so
 * each entity is in a cell with int coordinates. The regions and the points
 * of the queries can be outside the limits.
 */
final class GridQuadtreeIndex2 implements SpatialIndex2 {

    /** Levels of quadrants below a cell, at most. */
    static final int MAX_DEPTH = 24;

    private static final int CHILDREN = 4;

    private final PartitionStorage2 storage;
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
    GridQuadtreeIndex2(PartitionStorage2 storage, double cellSize, int leafCapacity) {
        this.storage = storage;
        this.cellSize = cellSize;
        this.leafCapacity = leafCapacity;
        this.mergeLimit = leafCapacity / 2;
    }

    @Override
    public void inserted(int slot, Point2 position) {
        if (slot >= placeOf.length) {
            int length = Math.max(slot + 1, placeOf.length * 2);
            leafOf = Arrays.copyOf(leafOf, length);
            placeOf = Arrays.copyOf(placeOf, length);
        }
        add(slot, position);
    }

    @Override
    public void moved(int slot, Point2 from, Point2 to) {
        Node leaf = leafOf[slot];
        if (leaf.contains(to)) {
            return;
        }
        // Simple but not the fastest way: a move to a near leaf could go up
        // only to the first node that contains the new position.
        remove(slot);
        add(slot, to);
    }

    @Override
    public void removed(int slot, Point2 position) {
        remove(slot);
    }

    @Override
    public void relocated(int from, int to, Point2 position) {
        Node leaf = leafOf[from];
        int place = placeOf[from];
        leaf.slots.set(place, to);
        leafOf[to] = leaf;
        placeOf[to] = place;
        leafOf[from] = null;
    }

    @Override
    public int[] findInRegion(Region2 region) {
        SlotList found = new SlotList();
        CellRange range = switch (region) {
            case Box2 box -> new CellRange(
                    cellOf(box.min().x()), cellOf(box.max().x()),
                    cellOf(box.min().y()), cellOf(box.max().y()));
            // When the square of the radius overflows, the circle contains each
            // point whose distance overflows too: all the cells must be read.
            case Circle2 circle when circle.radius() * circle.radius() == Double.POSITIVE_INFINITY -> CellRange.ALL;
            // A point on the border of the circle can be just outside the
            // box around it, because of rounding: one more cell on each side
            // keeps it in the range. The nodes then check the exact distance.
            case Circle2 circle -> new CellRange(
                    cellOf(circle.center().x() - circle.radius()) - 1,
                    cellOf(circle.center().x() + circle.radius()) + 1,
                    cellOf(circle.center().y() - circle.radius()) - 1,
                    cellOf(circle.center().y() + circle.radius()) + 1);
        };
        if (range.cellCount() <= cells.size()) {
            for (long cx = range.minX; cx <= range.maxX; cx++) {
                for (long cy = range.minY; cy <= range.maxY; cy++) {
                    Node root = cells.get(new CellKey((int) cx, (int) cy));
                    if (root != null) {
                        collect(root, region, found);
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
    public int[] findNearest(Point2 point, int count) {
        NearestSlots nearest = new NearestSlots(count);
        PriorityQueue<NodeDistance> queue =
                new PriorityQueue<>(Comparator.comparingDouble(NodeDistance::distanceSquared));
        long x = cellOf(point.x());
        long y = cellOf(point.y());
        // A point far outside the limits has no rings: all the cells go in the queue.
        boolean allCellsQueued = !isInt(x) || !isInt(y);
        if (allCellsQueued) {
            for (Node root : cells.values()) {
                queue.add(new NodeDistance(root, minDistanceSquared(root, point)));
            }
        }
        long ring = 0;
        while (true) {
            double nodeLimit = queue.isEmpty() ? Double.POSITIVE_INFINITY : queue.peek().distanceSquared();
            double ringLimit = allCellsQueued ? Double.POSITIVE_INFINITY : ringDistanceSquared(ring);
            if (nearest.isComplete(Math.min(nodeLimit, ringLimit))) {
                break;
            }
            if (!allCellsQueued && ringLimit <= nodeLimit) {
                // Rings get larger and larger: when a ring and the rings inside
                // it have more cells than the grid, it is faster to queue the
                // cells of the grid that are not queued yet.
                if ((2 * ring + 1.0) * (2 * ring + 1.0) > cells.size()) {
                    for (Map.Entry<CellKey, Node> cell : cells.entrySet()) {
                        if (ringOf(cell.getKey(), x, y) >= ring) {
                            queue.add(new NodeDistance(cell.getValue(), minDistanceSquared(cell.getValue(), point)));
                        }
                    }
                    allCellsQueued = true;
                } else {
                    queueRing(queue, point, x, y, ring);
                    ring++;
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

    private void add(int slot, Point2 position) {
        long x = cellOf(position.x());
        long y = cellOf(position.y());
        // The limits of the index keep the cell coordinates in the int range.
        Node node = cells.computeIfAbsent(new CellKey((int) x, (int) y),
                key -> new Node(null, key.x * cellSize, key.y * cellSize, cellSize, 0));
        while (!node.isLeaf()) {
            node.count++;
            node = node.children[node.childIndex(position)];
        }
        addToLeaf(node, slot);
        splitIfFull(node);
    }

    private void addToLeaf(Node leaf, int slot) {
        leaf.count++;
        leafOf[slot] = leaf;
        placeOf[slot] = leaf.slots.add(slot);
    }

    /** Removes the slot from its leaf, then merges the nodes that have few entities and drops an empty cell. */
    private void remove(int slot) {
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
        Node root = leaf;
        for (Node node = leaf; node != null; node = node.parent) {
            node.count--;
            if (!node.isLeaf() && node.count <= mergeLimit) {
                toMerge = node;
            }
            root = node;
        }
        if (toMerge != null) {
            merge(toMerge);
        }
        if (root.count == 0) {
            cells.remove(new CellKey((int) (root.minX / cellSize), (int) (root.minY / cellSize)));
        }
    }

    private void splitIfFull(Node leaf) {
        if (leaf.slots.size() <= leafCapacity || leaf.depth >= MAX_DEPTH
                || !halvesAreExact(leaf.minX, leaf.side) || !halvesAreExact(leaf.minY, leaf.side)) {
            return;
        }
        SlotList slots = leaf.slots;
        double half = leaf.side / 2;
        leaf.slots = null;
        leaf.children = new Node[CHILDREN];
        for (int i = 0; i < CHILDREN; i++) {
            leaf.children[i] = new Node(leaf,
                    leaf.minX + (i & 1) * half, leaf.minY + (i >> 1) * half, half, leaf.depth + 1);
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
    private void collect(Node node, Region2 region, SlotList found) {
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
     * The tests on the circle are safe with rounding: rounding never makes a
     * distance smaller when the exact distance is larger, so a point of the
     * node is never nearer to the center than the computed limit.
     */
    private static boolean touches(Node node, Region2 region) {
        return switch (region) {
            case Box2 box -> node.minX <= box.max().x() && node.maxX() > box.min().x()
                    && node.minY <= box.max().y() && node.maxY() > box.min().y();
            case Circle2 circle -> !(minDistanceSquared(node, circle.center()) > circle.radius() * circle.radius());
        };
    }

    /** Returns true if all the points of the box of the node are in the region. */
    private static boolean isInside(Node node, Region2 region) {
        return switch (region) {
            case Box2 box -> node.minX >= box.min().x() && node.maxX() <= box.max().x()
                    && node.minY >= box.min().y() && node.maxY() <= box.max().y();
            case Circle2 circle -> {
                Point2 c = circle.center();
                double dx = Math.max(c.x() - node.minX, node.maxX() - c.x());
                double dy = Math.max(c.y() - node.minY, node.maxY() - c.y());
                yield dx * dx + dy * dy <= circle.radius() * circle.radius();
            }
        };
    }

    /**
     * Returns a squared distance that the entities of the node cannot be
     * nearer than. It uses the same operations as {@link Point2#distanceSquared},
     * so it is never larger than the computed distance of an entity.
     */
    private static double minDistanceSquared(Node node, Point2 point) {
        double dx = gap(point.x(), node.minX, node.maxX());
        double dy = gap(point.y(), node.minY, node.maxY());
        return dx * dx + dy * dy;
    }

    /** Returns the distance from the coordinate to [min, max), or 0 if it is inside. */
    private static double gap(double coordinate, double min, double max) {
        if (coordinate < min) {
            return min - coordinate;
        }
        return coordinate >= max ? coordinate - max : 0;
    }

    /**
     * Returns a squared distance that the entities in the ring, or in a ring
     * outside it, cannot be nearer than. The point can be anywhere in its own
     * cell, so the gap is one cell less than the ring. The cell borders are
     * exact, so no margin is needed.
     */
    private double ringDistanceSquared(long ring) {
        double gap = Math.max(0, ring - 1) * cellSize;
        return gap * gap;
    }

    /** Adds to the queue the cells of the ring around the cell (x, y). */
    private void queueRing(PriorityQueue<NodeDistance> queue, Point2 point, long x, long y, long ring) {
        for (long cx = x - ring; cx <= x + ring; cx++) {
            boolean side = Math.abs(cx - x) == ring;
            for (long cy = y - ring; cy <= y + ring; cy += side ? 1 : 2 * ring) {
                if (isInt(cx) && isInt(cy)) {
                    Node root = cells.get(new CellKey((int) cx, (int) cy));
                    if (root != null) {
                        queue.add(new NodeDistance(root, minDistanceSquared(root, point)));
                    }
                }
            }
        }
    }

    private void offer(NearestSlots nearest, Point2 point, int slot) {
        nearest.offer(storage.positionAt(slot).distanceSquared(point), storage.idAt(slot), slot);
    }


    /**
     * Returns the ring of the cell around the cell (x, y): the largest
     * distance between them on one axis, in cells.
     */
    private static long ringOf(CellKey key, long x, long y) {
        return Math.max(Math.abs(key.x - x), Math.abs(key.y - y));
    }

    private static boolean isInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    /** The coordinates of a cell. */
    private record CellKey(int x, int y) {
    }

    /** A node and its distance from the point of a k-nearest query. */
    private record NodeDistance(Node node, double distanceSquared) {
    }

    /**
     * A node of the quadtree of a cell, with the box [minX, minX + side) x
     * [minY, minY + side). A leaf has slots and no children; the other nodes
     * have 4 children and no slots. Child i has the high half of x if bit 0
     * of i is set, and the high half of y if bit 1 is set.
     */
    private static final class Node {

        final Node parent;
        final double minX;
        final double minY;
        final double side;
        final int depth;
        Node[] children;
        SlotList slots = new SlotList();
        /** Number of entities in the subtree. */
        int count;

        Node(Node parent, double minX, double minY, double side, int depth) {
            this.parent = parent;
            this.minX = minX;
            this.minY = minY;
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

        boolean contains(Point2 p) {
            return p.x() >= minX && p.x() < maxX() && p.y() >= minY && p.y() < maxY();
        }

        int childIndex(Point2 p) {
            double half = side / 2;
            return (p.x() < minX + half ? 0 : 1) + (p.y() < minY + half ? 0 : 2);
        }
    }

    /**
     * The cells from min to max on each axis, limits included. Both limits are
     * moved into the int range, so min is never larger than max.
     */
    private record CellRange(long minX, long maxX, long minY, long maxY) {

        /** All the cells of the int range. */
        static final CellRange ALL = new CellRange(
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE);

        CellRange {
            minX = toInt(minX);
            maxX = toInt(maxX);
            minY = toInt(minY);
            maxY = toInt(maxY);
        }

        private static long toInt(long value) {
            return Math.clamp(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }

        /** Returns the number of cells, as a double so that it cannot overflow. */
        double cellCount() {
            return (maxX - minX + 1.0) * (maxY - minY + 1.0);
        }

        boolean contains(CellKey key) {
            return key.x >= minX && key.x <= maxX && key.y >= minY && key.y <= maxY;
        }
    }
}
