package badspace.service;

import badspace.common.Box3;
import badspace.common.Point3;
import badspace.common.Region3;
import badspace.common.Sphere3;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Index that divides the space in cubic cells, all of the same size. Each
 * cell keeps the slots of its entities; only the cells with entities are in
 * memory, so the space has no limits. Updates are cheap: a move changes the
 * cell only when the entity leaves its cell.
 * <p>
 * A range query reads the cells that cover the region. A k-nearest query reads
 * the cells in shells around the point, from the nearest to the farthest, and
 * stops when the next shell cannot have nearer entities.
 * <p>
 * Very large coordinates go to the cells at the edge of the int range, so
 * those cells can be large; the results stay correct.
 */
final class UniformGridIndex3 implements SpatialIndex3 {

    /**
     * Part of a cell that the k-nearest query adds to its safety margin, for
     * the rounding errors of the cell coordinates. The errors are smaller
     * than 2^-20 cells, even for the largest cell coordinates.
     */
    private static final double ROUNDING_MARGIN = 1e-5;

    private final PartitionStorage3 storage;
    private final double cellSize;
    private final Map<CellKey, Cell> cells = new HashMap<>();
    /** For each slot, its place in the list of its cell. */
    private int[] placeInCell = new int[16];

    UniformGridIndex3(PartitionStorage3 storage, double cellSize) {
        this.storage = storage;
        this.cellSize = cellSize;
    }

    @Override
    public void inserted(int slot, Point3 position) {
        add(slot, keyOf(position));
    }

    @Override
    public void moved(int slot, Point3 from, Point3 to) {
        CellKey fromKey = keyOf(from);
        CellKey toKey = keyOf(to);
        if (!fromKey.equals(toKey)) {
            remove(slot, fromKey);
            add(slot, toKey);
        }
    }

    @Override
    public void removed(int slot, Point3 position) {
        remove(slot, keyOf(position));
    }

    @Override
    public void relocated(int from, int to, Point3 position) {
        int place = placeInCell[from];
        cells.get(keyOf(position)).slots.set(place, to);
        placeInCell[to] = place;
    }

    @Override
    public int[] findInRegion(Region3 region) {
        CellRange range = switch (region) {
            case Box3 box -> new CellRange(
                    cellOf(box.min().x()), cellOf(box.max().x()),
                    cellOf(box.min().y()), cellOf(box.max().y()),
                    cellOf(box.min().z()), cellOf(box.max().z()));
            // A point on the border of the sphere can be just outside the
            // box around it, because of rounding: one more cell on each side
            // keeps it in the range.
            case Sphere3 sphere -> new CellRange(
                    cellOf(sphere.center().x() - sphere.radius()) - 1L,
                    cellOf(sphere.center().x() + sphere.radius()) + 1L,
                    cellOf(sphere.center().y() - sphere.radius()) - 1L,
                    cellOf(sphere.center().y() + sphere.radius()) + 1L,
                    cellOf(sphere.center().z() - sphere.radius()) - 1L,
                    cellOf(sphere.center().z() + sphere.radius()) + 1L);
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

    @Override
    public int[] findNearest(Point3 point, int count) {
        NearestSlots nearest = new NearestSlots(count);
        int x = cellOf(point.x());
        int y = cellOf(point.y());
        int z = cellOf(point.z());
        int seen = 0;
        long shell = 0;
        // Shells get larger and larger: when a shell and the shells inside it have
        // more cells than the grid, it is faster to read the cells of the grid.
        while (Math.pow(2 * shell + 1.0, 3) <= cells.size()) {
            if (seen == storage.size() || nearest.isComplete(minDistanceSquared(shell))) {
                return nearest.slots();
            }
            for (long cx = x - shell; cx <= x + shell; cx++) {
                for (long cy = y - shell; cy <= y + shell; cy++) {
                    boolean side = Math.abs(cx - x) == shell || Math.abs(cy - y) == shell;
                    for (long cz = z - shell; cz <= z + shell; cz += side ? 1 : 2 * shell) {
                        seen += offer(nearest, point, cx, cy, cz);
                    }
                }
            }
            shell++;
        }
        // The cells not read yet, from the nearest to the farthest.
        List<Cell> rest = new ArrayList<>();
        for (Cell cell : cells.values()) {
            if (shellOf(cell.key, x, y, z) >= shell) {
                rest.add(cell);
            }
        }
        rest.sort(Comparator.comparingLong(cell -> shellOf(cell.key, x, y, z)));
        for (Cell cell : rest) {
            if (seen == storage.size() || nearest.isComplete(minDistanceSquared(shellOf(cell.key, x, y, z)))) {
                break;
            }
            seen += offer(nearest, point, cell);
        }
        return nearest.slots();
    }

    /**
     * Returns the cell coordinate of a space coordinate. Values outside the
     * int range go to the first or the last cell, and NaN goes to cell 0.
     */
    private int cellOf(double coordinate) {
        return (int) Math.floor(coordinate / cellSize);
    }

    private CellKey keyOf(Point3 position) {
        return new CellKey(cellOf(position.x()), cellOf(position.y()), cellOf(position.z()));
    }

    private void add(int slot, CellKey key) {
        if (slot >= placeInCell.length) {
            placeInCell = Arrays.copyOf(placeInCell, Math.max(slot + 1, placeInCell.length * 2));
        }
        placeInCell[slot] = cells.computeIfAbsent(key, Cell::new).slots.add(slot);
    }

    /** Removes the slot from its cell, moving the last slot of the cell into its place. */
    private void remove(int slot, CellKey key) {
        Cell cell = cells.get(key);
        int place = placeInCell[slot];
        int last = cell.slots.removeLast();
        if (last != slot) {
            cell.slots.set(place, last);
            placeInCell[last] = place;
        }
        if (cell.slots.isEmpty()) {
            cells.remove(key);
        }
    }

    /** Returns the cells with entities in the range. */
    private List<Cell> cellsIn(CellRange range) {
        List<Cell> found = new ArrayList<>();
        if (range.cellCount() <= cells.size()) {
            for (long cx = range.minX; cx <= range.maxX; cx++) {
                for (long cy = range.minY; cy <= range.maxY; cy++) {
                    for (long cz = range.minZ; cz <= range.maxZ; cz++) {
                        Cell cell = cells.get(new CellKey((int) cx, (int) cy, (int) cz));
                        if (cell != null) {
                            found.add(cell);
                        }
                    }
                }
            }
        } else {
            for (Cell cell : cells.values()) {
                if (range.contains(cell.key)) {
                    found.add(cell);
                }
            }
        }
        return found;
    }

    /** Offers the entities of the cell, if the cell exists, and returns their number. */
    private int offer(NearestSlots nearest, Point3 point, long cx, long cy, long cz) {
        if (!isInt(cx) || !isInt(cy) || !isInt(cz)) {
            return 0;
        }
        Cell cell = cells.get(new CellKey((int) cx, (int) cy, (int) cz));
        return cell == null ? 0 : offer(nearest, point, cell);
    }

    private int offer(NearestSlots nearest, Point3 point, Cell cell) {
        for (int i = 0; i < cell.slots.size(); i++) {
            int slot = cell.slots.get(i);
            nearest.offer(storage.positionAt(slot).distanceSquared(point), storage.idAt(slot), slot);
        }
        return cell.slots.size();
    }

    /**
     * Returns the shell of the cell around the cell (x, y, z): the largest
     * distance between them on one axis, in cells.
     */
    private static long shellOf(CellKey key, int x, int y, int z) {
        return Math.max(Math.abs((long) key.x - x), Math.max(Math.abs((long) key.y - y), Math.abs((long) key.z - z)));
    }

    private static boolean isInt(long value) {
        return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    /**
     * Returns a squared distance that the entities in the shell, or in a shell
     * outside it, cannot be nearer than. The point can be anywhere in its own
     * cell, so the gap is one cell less than the shell.
     */
    private double minDistanceSquared(long shell) {
        double gap = Math.max(0, (shell - 1 - ROUNDING_MARGIN) * cellSize);
        return gap * gap;
    }

    /** The coordinates of a cell. */
    private record CellKey(int x, int y, int z) {
    }

    /** A cell with entities, and their slots in any order. */
    private static final class Cell {

        final CellKey key;
        final SlotList slots = new SlotList();

        Cell(CellKey key) {
            this.key = key;
        }
    }

    /** The cells from min to max on each axis, limits included and clamped to the int range. */
    private record CellRange(long minX, long maxX, long minY, long maxY, long minZ, long maxZ) {

        CellRange {
            minX = Math.max(minX, Integer.MIN_VALUE);
            maxX = Math.min(maxX, Integer.MAX_VALUE);
            minY = Math.max(minY, Integer.MIN_VALUE);
            maxY = Math.min(maxY, Integer.MAX_VALUE);
            minZ = Math.max(minZ, Integer.MIN_VALUE);
            maxZ = Math.min(maxZ, Integer.MAX_VALUE);
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
