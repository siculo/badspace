package badspace.samples.laggedreader;

import static badspace.common.geometry.Point2.point;
import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionConfig;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.geometry.Point2;
import badspace.common.partition.SnapshotNotAvailableException;
import badspace.service.partition.LocalPartitionNode2;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Sample client, like a game client that draws more frames than the server has ticks. The writer
 * thread moves an entity by 10 at each tick, 5 ticks per second; the main thread draws 20 frames
 * per second. Each frame shows the space LAG_TICKS ticks in the past: the reader acquires the
 * snapshots of the two ticks around the time of the frame, and draws the entity between its two
 * positions. The retention window keeps both snapshots acquirable.
 */
public final class LaggedReader {

    private static final int TICKS = 8;
    private static final double STEP = 10;
    private static final long TICK_NANOS = TimeUnit.SECONDS.toNanos(1) / 5;
    private static final long FRAME_NANOS = TimeUnit.SECONDS.toNanos(1) / 20;
    // The snapshot of tick n is published at about n ticks from the start, so with two ticks of
    // lag the later snapshot of a frame is published one tick before the frame. Two more retained
    // ticks keep the earlier one in the window if the reader is a little late.
    private static final double LAG_TICKS = 2;
    private static final int RETAINED_TICKS = 4;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"),
                PartitionConfig.defaults().withRetainedTicks(RETAINED_TICKS));
        // The main thread inserts the entity; then the handle goes to the writer thread.
        PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow());
        long ship = writer.insert(0, 0);

        long start = System.nanoTime();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            // In the snapshot of tick n the ship is at x = n * STEP.
            Future<?> writing = executor.submit(() -> {
                try (writer) {
                    for (long tick = 0; tick < TICKS; tick++) {
                        writer.update(ship, point(tick * STEP, 0));
                        writer.commit();
                        space.setTick(tick + 1);
                        TimeUnit.NANOSECONDS.sleep(start + (tick + 1) * TICK_NANOS - System.nanoTime());
                    }
                }
                return null;
            });

            for (long frame = 0; !writing.isDone(); frame++) {
                TimeUnit.NANOSECONDS.sleep(start + frame * FRAME_NANOS - System.nanoTime());
                double renderTick = (double) (System.nanoTime() - start) / TICK_NANOS - LAG_TICKS;
                if (renderTick >= 0) {
                    draw(partition, ship, frame, renderTick);
                }
            }
            // Throws if the writer failed.
            writing.get();
        }
    }

    /** Draws the ship between its positions in the snapshots of the ticks around renderTick. */
    private static void draw(Partition2 partition, long ship, long frame, double renderTick) {
        long before = (long) Math.floor(renderTick);
        double fraction = renderTick - before;
        try (AcquiredSnapshot2 from = partition.acquire(before);
                AcquiredSnapshot2 to = partition.acquire(before + 1)) {
            Point2 a = from.get(ship).orElseThrow();
            Point2 b = to.get(ship).orElseThrow();
            double x = a.x() + (b.x() - a.x()) * fraction;
            System.out.printf(Locale.ROOT, "frame %2d, tick %5.2f: between ticks %d and %d, x = %6.2f%n", frame,
                    renderTick, before, before + 1, x);
        } catch (SnapshotNotAvailableException e) {
            // A snapshot is not published yet or has left the window: the reader skips the frame.
            System.out.printf(Locale.ROOT, "frame %2d, tick %5.2f: skipped, %s%n", frame, renderTick, e.reason());
        }
    }
}
