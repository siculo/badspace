package badspace.service.index;

import badspace.common.geometry.Box2;
import badspace.common.geometry.Circle2;
import badspace.common.geometry.Point2;
import badspace.common.geometry.Region2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Index that divides the space in square cells, all of the same size. Each
 * cell keeps the slots of its entities; only the cells with entities are in
 * memory, so the space has no limits. Updates are cheap: a move changes the
 * cell only when the entity leaves its cell.
 * <p>
 * A range query reads the cells that cover the region. A k-nearest query reads
 * the cells in rings around the point, from the nearest to the farthest, and
 * stops when the next ring cannot have nearer entities.
 * <p>
 * At each commit the index gives a version that shares the cells with the
 * writer. The map of the cells is a {@link HashTrie}, and a cell keeps the
 * last commit at the time it was made: the writer copies a cell, and the
 * path of the map to it, the first time it changes the cell after a commit.
 * The map from slot to place in the cell is used only by the writer, so it
 * is not in the versions; a copy keeps the slots in the same places.
 * <p>
 * The storage keeps the entities in the limits of the index, at most
 * {@code IndexConfig.CELLS_PER_SIDE} cells from the origin on each axis, so
 * the cell coordinates of the entities fit in an int. Very large coordinates
 * of the queries go to the cells at the edge of the int range.
 */
final class UniformGridIndex2 implements SpatialIndex2 {

    /**
     * Part of a cell that the k-nearest query adds to its safety margin, for
     * the rounding errors of the cell coordinates. The errors are smaller
     * than 2^-20 cells, even for the largest cell coordinates.
     */
    private static final double ROUNDING_MARGIN = 1e-5;

    private static final int INITIAL_CAPACITY = 16;

    private final SlotView2 storage;
    private final double cellSize;
    private final HashTrie<CellKey, Cell> cells = new HashTrie<>();
    /** For each slot, its place in the list of its cell. */
    private int[] placeInCell = new int[INITIAL_CAPACITY];
    private long lastCommit;

    UniformGridIndex2(SlotView2 storage, double cellSize) {
        this.storage = storage;
        this.cellSize = cellSize;
    }

    @Override
    public void inserted(int slot, Point2 position) {
        add(slot, keyOf(position));
    }

    @Override
    public void moved(int slot, Point2 from, Point2 to) {
        CellKey fromKey = keyOf(from);
        CellKey toKey = keyOf(to);
        if (!fromKey.equals(toKey)) {
            remove(slot, fromKey);
            add(slot, toKey);
        }
    }

    @Override
    public void removed(int slot, Point2 position) {
        remove(slot, keyOf(position));
    }

    @Override
    public void relocated(int from, int to, Point2 position) {
        int place = placeInCell[from];
        writable(cells.get(keyOf(position))).slots.set(place, to);
        placeInCell[to] = place;
    }

    @Override
    public IndexVersion2 commit(long n) {
        lastCommit = n;
        return new Version(n, cells.commit(n), cellSize);
    }

    @Override
    public int[] findInRegion(Region2 region) {
        return new Search(cells.view(), cellSize, storage).findInRegion(region);
    }

    @Override
    public int[] findNearest(Point2 point, int count) {
        return new Search(cells.view(), cellSize, storage).findNearest(point, count);
    }

    /**
     * Returns the cell coordinate of a space coordinate. Values of the queries
     * outside the int range go to the first or the last cell.
     */
    private static int cellOf(double coordinate, double cellSize) {
        return (int) Math.floor(coordinate / cellSize);
    }

    private CellKey keyOf(Point2 position) {
        return new CellKey(cellOf(position.x(), cellSize), cellOf(position.y(), cellSize));
    }

    private void add(int slot, CellKey key) {
        if (slot >= placeInCell.length) {
            placeInCell = Arrays.copyOf(placeInCell, Math.max(slot + 1, placeInCell.length * 2));
        }
        Cell cell = cells.get(key);
        if (cell == null) {
            cell = new Cell(key, lastCommit);
            cells.put(key, cell);
        } else {
            cell = writable(cell);
        }
        placeInCell[slot] = cell.slots.add(slot);
    }

    /** Removes the slot from its cell, moving the last slot of the cell into its place. */
    private void remove(int slot, CellKey key) {
        Cell cell = cells.get(key);
        if (cell.slots.size() == 1) {
            cells.remove(key);
            return;
        }
        cell = writable(cell);
        int place = placeInCell[slot];
        int last = cell.slots.removeLast();
        if (last != slot) {
            cell.slots.set(place, last);
            placeInCell[last] = place;
        }
    }

    /** Returns the cell, or a copy of it put in the map if an older version can see the cell. */
    private Cell writable(Cell cell) {
        if (cell.commit != lastCommit) {
            cell = cell.copy(lastCommit);
            cells.put(cell.key, cell);
        }
        return cell;
    }

    /**
     * Returns the ring of the cell around the cell (x, y): the largest
     * distance between them on one axis, in cells.
     */
    private static long ringOf(CellKey key, int x, int y) {
        return Math.max(Math.abs((long) key.x - x), Math.abs((long) key.y - y));
    }

    private static boolean isInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    /** The queries on the cells of a version, which read the positions in the slots of the same version. */
    private static final class Search {

        private final HashTrie.View<CellKey, Cell> cells;
        private final double cellSize;
        private final SlotView2 storage;

        Search(HashTrie.View<CellKey, Cell> cells, double cellSize, SlotView2 storage) {
            this.cells = cells;
            this.cellSize = cellSize;
            this.storage = storage;
        }

        int[] findInRegion(Region2 region) {
            CellRange range = switch (region) {
                case Box2 box -> new CellRange(
                        cellOf(box.min().x()), cellOf(box.max().x()),
                        cellOf(box.min().y()), cellOf(box.max().y()));
                // When the square of the radius overflows, the circle contains each
                // point whose distance overflows too: all the cells must be read.
                case Circle2 circle when circle.radius() * circle.radius() == Double.POSITIVE_INFINITY -> CellRange.ALL;
                // A point on the border of the circle can be just outside the
                // box around it, because of rounding: one more cell on each side
                // keeps it in the range.
                case Circle2 circle -> new CellRange(
                        cellOf(circle.center().x() - circle.radius()) - 1L,
                        cellOf(circle.center().x() + circle.radius()) + 1L,
                        cellOf(circle.center().y() - circle.radius()) - 1L,
                        cellOf(circle.center().y() + circle.radius()) + 1L);
            };
            SlotList found = new SlotList();
            for (Cell cell : cellsIn(range)) {
                for (int i = 0; i < cell.slots.size(); i++) {
                    int slot = cell.slots.get(i);
                    if (region.contains(storage.positionAt(slot))) {
                        found.add(slot);
                    }
                }
            }
            return found.toArray();
        }

        int[] findNearest(Point2 point, int count) {
            NearestSlots nearest = new NearestSlots(count);
            int x = cellOf(point.x());
            int y = cellOf(point.y());
            int seen = 0;
            long ring = 0;
            // Rings get larger and larger: when a ring and the rings inside it have
            // more cells than the grid, it is faster to read the cells of the grid.
            while ((2 * ring + 1.0) * (2 * ring + 1.0) <= cells.size()) {
                if (seen == storage.size() || nearest.isComplete(minDistanceSquared(ring))) {
                    return nearest.slots();
                }
                for (long cx = x - ring; cx <= x + ring; cx++) {
                    boolean side = Math.abs(cx - x) == ring;
                    for (long cy = y - ring; cy <= y + ring; cy += side ? 1 : 2 * ring) {
                        seen += offer(nearest, point, cx, cy);
                    }
                }
                ring++;
            }
            // The cells not read yet, from the nearest to the farthest.
            List<Cell> rest = new ArrayList<>();
            long done = ring;
            cells.forEachValue(cell -> {
                if (ringOf(cell.key, x, y) >= done) {
                    rest.add(cell);
                }
            });
            rest.sort(Comparator.comparingLong(cell -> ringOf(cell.key, x, y)));
            for (Cell cell : rest) {
                if (seen == storage.size() || nearest.isComplete(minDistanceSquared(ringOf(cell.key, x, y)))) {
                    break;
                }
                seen += offer(nearest, point, cell);
            }
            return nearest.slots();
        }

        private int cellOf(double coordinate) {
            return UniformGridIndex2.cellOf(coordinate, cellSize);
        }

        /** Returns the cells with entities in the range. */
        private List<Cell> cellsIn(CellRange range) {
            List<Cell> found = new ArrayList<>();
            if (range.cellCount() <= cells.size()) {
                for (long cx = range.minX; cx <= range.maxX; cx++) {
                    for (long cy = range.minY; cy <= range.maxY; cy++) {
                        Cell cell = cells.get(new CellKey((int) cx, (int) cy));
                        if (cell != null) {
                            found.add(cell);
                        }
                    }
                }
            } else {
                cells.forEachValue(cell -> {
                    if (range.contains(cell.key)) {
                        found.add(cell);
                    }
                });
            }
            return found;
        }

        /** Offers the entities of the cell, if the cell exists, and returns their number. */
        private int offer(NearestSlots nearest, Point2 point, long cx, long cy) {
            if (!isInt(cx) || !isInt(cy)) {
                return 0;
            }
            Cell cell = cells.get(new CellKey((int) cx, (int) cy));
            return cell == null ? 0 : offer(nearest, point, cell);
        }

        private int offer(NearestSlots nearest, Point2 point, Cell cell) {
            for (int i = 0; i < cell.slots.size(); i++) {
                int slot = cell.slots.get(i);
                nearest.offer(storage.positionAt(slot).distanceSquared(point), storage.idAt(slot), slot);
            }
            return cell.slots.size();
        }

        /**
         * Returns a squared distance that the entities in the ring, or in a ring
         * outside it, cannot be nearer than. The point can be anywhere in its own
         * cell, so the gap is one cell less than the ring.
         */
        private double minDistanceSquared(long ring) {
            double gap = Math.max(0, (ring - 1 - ROUNDING_MARGIN) * cellSize);
            return gap * gap;
        }
    }

    /** A version of the index at a commit. */
    private record Version(long commit, HashTrie.View<CellKey, Cell> cells, double cellSize) implements IndexVersion2 {

        @Override
        public int[] findInRegion(Region2 region, SlotView2 slots) {
            return new Search(cells, cellSize, slots).findInRegion(region);
        }

        @Override
        public int[] findNearest(Point2 point, int count, SlotView2 slots) {
            return new Search(cells, cellSize, slots).findNearest(point, count);
        }
    }

    /** The coordinates of a cell. */
    private record CellKey(int x, int y) {
    }

    /**
     * A cell with entities, and their slots in any order. The slots change
     * only while the cell belongs to the current commit.
     */
    private static final class Cell {

        final CellKey key;
        /** The last commit when the cell was made. */
        final long commit;
        final SlotList slots;

        Cell(CellKey key, long commit) {
            this(key, commit, new SlotList());
        }

        private Cell(CellKey key, long commit, SlotList slots) {
            this.key = key;
            this.commit = commit;
            this.slots = slots;
        }

        /** Returns a copy for the given commit, with the slots in the same places. */
        Cell copy(long commit) {
            return new Cell(key, commit, slots.copy());
        }
    }

    /** The cells from min to max on each axis, limits included and clamped to the int range. */
    private record CellRange(long minX, long maxX, long minY, long maxY) {

        /** All the cells of the int range. */
        static final CellRange ALL = new CellRange(
                Integer.MIN_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MAX_VALUE);

        CellRange {
            minX = Math.max(minX, Integer.MIN_VALUE);
            maxX = Math.min(maxX, Integer.MAX_VALUE);
            minY = Math.max(minY, Integer.MIN_VALUE);
            maxY = Math.min(maxY, Integer.MAX_VALUE);
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
