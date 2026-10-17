package badspace.samples.laggedreader;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Point2;
import badspace.common.partition.Entity2;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Sample client, like a game client that draws more frames than the server has ticks: the writer
 * moves the entities at constant speed, a few ticks per second, and the reader draws many frames
 * per second. The reader draws the space a little in the past, LAG_TICKS ticks behind: for each
 * frame it acquires the snapshots of the two ticks around the time of the frame and draws the
 * entities between the two positions. The retention window keeps those snapshots acquirable.
 */
public final class LaggedReader {

    private static final int TICKS_PER_SECOND = 5;
    private static final int FRAMES_PER_SECOND = 20;
    private static final int WRITER_SECONDS = 3;
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / TICKS_PER_SECOND;
    private static final long FRAME_NANOS = TimeUnit.SECONDS.toNanos(1) / FRAMES_PER_SECOND;
    // The snapshot of tick n is published at about n ticks from the start. Two ticks of lag
    // leave one tick of margin for the second snapshot of a frame, and two more retained ticks
    // keep the first one in the window if the reader is late.
    private static final double LAG_TICKS = 2;
    private static final int RETAINED_TICKS = 4;
    private static final List<Point2> VELOCITIES = List.of(new Point2(10, 0), new Point2(0, 5));

    public static void main(String[] args) throws InterruptedException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(),
                PartitionConfig.defaults().withRetainedTicks(RETAINED_TICKS));

        // The main thread inserts the entities; the writer thread commits them at its first tick.
        PartitionWriter2 partitionWriter = partition.writer(partition.claim(0).orElseThrow());
        List<Entity2> entities = new ArrayList<>();
        for (int i = 0; i < VELOCITIES.size(); i++) {
            Point2 position = new Point2(0, 0);
            entities.add(new Entity2(partitionWriter.insert(position), position));
        }
        long[] entityIds = entities.stream().mapToLong(Entity2::id).toArray();

        long start = System.nanoTime();
        Thread writer = Thread.ofPlatform().name("writer").start(() -> write(space, partitionWriter, entities, start));
        for (long frame = 0; writer.isAlive(); frame++) {
            TimeUnit.NANOSECONDS.sleep(start + frame * FRAME_NANOS - System.nanoTime());
            double renderTick = (double) (System.nanoTime() - start) / TICK_NANOS - LAG_TICKS;
            if (renderTick >= 0) {
                draw(partition, entityIds, frame, renderTick);
            }
        }
        writer.join();
    }

    /**
     * The writer: one commit per tick, at constant speed, for WRITER_SECONDS seconds. It moves the
     * entities after the commit, so the snapshot of tick 0 has the first positions.
     */
    private static void write(Space2 space, PartitionWriter2 partitionWriter, List<Entity2> entities, long start) {
        long ticks = (long) WRITER_SECONDS * TICKS_PER_SECOND;
        try (partitionWriter) {
            for (long tick = 1; tick <= ticks; tick++) {
                partitionWriter.commit();
                space.setTick(tick);
                TimeUnit.NANOSECONDS.sleep(start + tick * TICK_NANOS - System.nanoTime());
                for (int i = 0; i < entities.size(); i++) {
                    Point2 velocity = VELOCITIES.get(i);
                    entities.set(i, entities.get(i).moveBy(velocity.x(), velocity.y()));
                }
                partitionWriter.updateAll(entities);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Draws the entities between the snapshots of the ticks before and after renderTick. */
    private static void draw(Partition2 partition, long[] entityIds, long frame, double renderTick) {
        long before = (long) Math.floor(renderTick);
        double alpha = renderTick - before;
        // The tick only grows: if it is after before + 1, the snapshot of before + 1 is published.
        if (partition.tick() <= before + 1) {
            System.out.printf(Locale.ROOT, "frame %3d, tick %5.2f: the snapshot of tick %d is not published yet%n",
                    frame, renderTick, before + 1);
            return;
        }
        try (AcquiredSnapshot2 from = partition.acquire(before);
                AcquiredSnapshot2 to = partition.acquire(before + 1)) {
            // No entity is removed, so the two lists have the same entities in the same order.
            List<Entity2> fromEntities = from.getAll(entityIds);
            List<Entity2> toEntities = to.getAll(entityIds);
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < entityIds.length; i++) {
                Point2 a = fromEntities.get(i).position();
                Point2 b = toEntities.get(i).position();
                line.append(String.format(Locale.ROOT, " (%6.2f, %6.2f)", a.x() + (b.x() - a.x()) * alpha,
                        a.y() + (b.y() - a.y()) * alpha));
            }
            System.out.printf(Locale.ROOT, "frame %3d, tick %5.2f:%s%n", frame, renderTick, line);
        } catch (SnapshotNotAvailableException e) {
            // The reader was so late that the snapshot left the window: it skips the frame.
            System.out.printf(Locale.ROOT, "frame %3d, tick %5.2f: skipped, %s%n", frame, renderTick, e.reason());
        }
    }
}
