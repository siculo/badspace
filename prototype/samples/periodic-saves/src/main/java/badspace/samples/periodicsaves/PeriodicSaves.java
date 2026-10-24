package badspace.samples.periodicsaves;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Box2;
import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.IndexConfig;
import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * Sample client, like a simulation that saves its world while it runs. The main thread is the
 * loop of the simulation: at each tick the writer moves the entities of a partition at random,
 * commits and moves the tick. Every few ticks the loop asks a second thread, the saver, to save
 * the snapshot of the tick that just ended. The save is synchronous for the saver, but the loop
 * does not wait for it: the writer goes on with the next ticks while the node copies and writes
 * the snapshot. After each save the saver keeps only the latest saves and deletes the others:
 * the retention of the saves is a policy of the software, not of the node.
 * <p>
 * At the end the sample restarts: a new space and a new node on the same save directory. The
 * software finds the latest save of the partition by its name and loads it into a new partition,
 * with another index. The loaded partition has the same entities, with the same IDs, positions
 * and metadata, at the creation tick of the new run; a writer claims it and goes on.
 * <p>
 * Run it with a directory as argument to keep the saves there; without an argument it uses a new
 * temporary directory. It does not delete the files at the end, so you can look at them.
 */
public final class PeriodicSaves {

    private static final PartitionName ARENA = partitionName("arena");
    private static final int ENTITIES = 2000;
    private static final double SIDE = 1000;
    private static final double MAX_STEP = 5;
    private static final int TICKS = 100;
    private static final int TICKS_PER_SAVE = 10;
    private static final int KEPT_SAVES = 3;
    // Enough ticks for a saver that is a little late: a save fails if its tick left the window.
    private static final int RETAINED_TICKS = 8;
    private static final long MILLIS_PER_TICK = 10;
    // The request that stops the saver: ticks are never negative.
    private static final long NO_MORE_SAVES = -1;
    private static final Box2 ALL = new Box2(point(0, 0), point(SIDE, SIDE));

    public static void main(String[] args) throws IOException, InterruptedException {
        // The save directory must exist: the node does not create it.
        Path directory = args.length > 0
                ? Files.createDirectories(Paths.get(args[0]))
                : Files.createTempDirectory("badspace-saves");
        System.out.printf("Save directory: %s%n", directory);

        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        LocalPartitionNode2 node = new LocalPartitionNode2(directory);
        Partition2 arena = space.createPartition(node, ARENA,
                PartitionConfig.defaults().withIndex(IndexConfig.uniformGrid(50)).withRetainedTicks(RETAINED_TICKS));

        // The ticks to save, from the loop to the saver.
        BlockingQueue<Long> saveRequests = new LinkedBlockingQueue<>();
        Thread saver = Thread.ofPlatform().name("saver").start(() -> save(space, node, arena, saveRequests));
        try (PartitionWriter2 writer = arena.writer(arena.claim(0).orElseThrow())) {
            // The writer keeps the positions in its own state: it does not read its writes.
            List<Entity2> entities = insert(writer);
            writer.commit();
            space.setTick(1);
            for (long tick = 1; tick < TICKS; tick++) {
                entities = move(entities);
                writer.updateAll(entities);
                writer.commit();
                space.setTick(tick + 1);
                // The snapshot of tick is published now: the saver can save it.
                if ((tick + 1) % TICKS_PER_SAVE == 0) {
                    saveRequests.put(tick);
                }
                Thread.sleep(MILLIS_PER_TICK);
            }
        } finally {
            saveRequests.put(NO_MORE_SAVES);
            saver.join();
        }
        System.out.printf("The simulation ended at tick %d. Saves kept:%n", space.tick());
        showFiles(directory);

        // A restart: a new space and a new node on the same directory. The new run has its own
        // logical time, which starts at tick 0, and the node has no partitions.
        Space2 restarted = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(1), new SnowflakeIdGenerator(1)));
        LocalPartitionNode2 newNode = new LocalPartitionNode2(directory);
        SavedPartition latest = restarted.saves(newNode, ARENA).getLast();
        // The index and the window come from the software, not from the save.
        Partition2 loaded = restarted.loadPartition(newNode,
                PartitionConfig.defaults().withIndex(IndexConfig.gridQuadtree(64)), latest);
        System.out.println();
        System.out.printf("After a restart: %s loaded from the save of tick %d, created at tick %d.%n",
                loaded.name().value(), latest.tick(), loaded.creationTick());
        // The load committed the content: the next move of the tick publishes it.
        restarted.setTick(1);
        try (AcquiredSnapshot2 saved = arena.acquire(latest.tick());
                AcquiredSnapshot2 reloaded = loaded.acquireLatest()) {
            List<Entity2> entities = reloaded.findInRegion(ALL);
            System.out.printf("  %d entities, the same as the snapshot of tick %d of the first run: %b%n",
                    entities.size(), latest.tick(),
                    new HashSet<>(entities).equals(new HashSet<>(saved.findInRegion(ALL))));
            Entity2 first = entities.getFirst();
            // The metadata are loaded as they are: "born" is a tick of the first run, and the
            // software decides what it means in the new one.
            System.out.printf("  entity %d at %s with metadata %s%n", first.id(), first.position(),
                    reloaded.getMetadata(first.id()).orElseThrow());
        }

        // The loaded partition has no owner: a writer claims it and goes on from the saved state.
        try (PartitionWriter2 writer = loaded.writer(loaded.claim(0).orElseThrow())) {
            long newcomer = writer.insert(SIDE / 2, SIDE / 2);
            writer.putMetadata(newcomer, "born", restarted.tick());
            writer.commit();
        }
        restarted.setTick(2);
        try (AcquiredSnapshot2 snapshot = loaded.acquireLatest()) {
            System.out.printf("  tick 1 of the new run, after a new insert: %d entities%n", snapshot.size());
        }
    }

    /**
     * The saver: it saves the requested ticks of the partition, one after the other, and keeps
     * only the latest KEPT_SAVES saves of the partition. If it is so late that a tick left the
     * retention window, the save fails and the saver skips it.
     */
    private static void save(Space2 space, LocalPartitionNode2 node, Partition2 partition,
            BlockingQueue<Long> requests) {
        try {
            while (true) {
                long tick = requests.take();
                if (tick == NO_MORE_SAVES) {
                    return;
                }
                SavedPartition saved;
                try {
                    saved = partition.save(tick);
                } catch (SnapshotNotAvailableException e) {
                    System.out.printf("  [saver] tick %d skipped: %s%n", tick, e.reason());
                    continue;
                }
                List<SavedPartition> saves = partition.saves();
                List<SavedPartition> older = saves.subList(0, Math.max(0, saves.size() - KEPT_SAVES));
                List<Long> deleted = older.stream().map(SavedPartition::tick).toList();
                space.deleteSaves(node, older);
                System.out.printf("  [saver] saved tick %d while the space is at tick %d; deleted ticks %s%n",
                        saved.tick(), space.tick(), deleted);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Inserts the entities at random positions, each with the tick at which it was born. */
    private static List<Entity2> insert(PartitionWriter2 writer) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Point2> positions = new ArrayList<>();
        for (int i = 0; i < ENTITIES; i++) {
            positions.add(point(random.nextDouble(SIDE), random.nextDouble(SIDE)));
        }
        long[] entityIds = writer.insertAll(positions);
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < ENTITIES; i++) {
            writer.putMetadata(entityIds[i], "born", 0);
            entities.add(new Entity2(entityIds[i], positions.get(i)));
        }
        return entities;
    }

    /** Returns the entities moved by a random step each, inside the side of the partition. */
    private static List<Entity2> move(List<Entity2> entities) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<Entity2> moved = new ArrayList<>(entities.size());
        for (Entity2 entity : entities) {
            Point2 p = entity.position();
            moved.add(new Entity2(entity.id(), point(
                    clamp(p.x() + random.nextDouble(-MAX_STEP, MAX_STEP)),
                    clamp(p.y() + random.nextDouble(-MAX_STEP, MAX_STEP)))));
        }
        return moved;
    }

    private static double clamp(double coordinate) {
        return Math.max(0, Math.min(SIDE, coordinate));
    }

    /** Prints the files under the save directory, with their size. */
    private static void showFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                System.out.printf("  %s (%d bytes)%n", directory.relativize(file), Files.size(file));
            }
        }
    }
}
