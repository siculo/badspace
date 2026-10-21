package badspace.samples.acquisitiontimeouts;

import static badspace.common.partition.PartitionName.partitionName;

import badspace.api.AcquiredSnapshot2;
import badspace.api.Partition2;
import badspace.api.PartitionWriter2;
import badspace.api.Space2;
import badspace.api.SpaceConfig;
import badspace.api.id.SnowflakeIdGenerator;
import badspace.common.partition.AcquireOptions;
import badspace.service.partition.LocalPartitionNode2;
import java.time.Duration;

/**
 * Sample client: every acquisition of a snapshot has a timeout, so a reader that dies without
 * releasing cannot keep a snapshot in memory for ever. After the release or the timeout a new
 * read fails; closing the view again does nothing.
 */
public final class AcquisitionTimeouts {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    public static void main(String[] args) throws InterruptedException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            writer.insert(1, 1);
            writer.commit();
            space.setTick(1);
        }

        // Without options an acquisition has the default timeout; the timeout cannot be greater
        // than the maximum.
        System.out.printf("Default timeout %s, maximum timeout %s%n",
                AcquireOptions.DEFAULT_TIMEOUT, AcquireOptions.MAX_TIMEOUT);
        try {
            AcquireOptions.timeout(Duration.ofHours(2));
        } catch (IllegalArgumentException e) {
            System.out.printf("Rejected timeout: %s%n", e.getMessage());
        }

        // After the release a new read fails, and a second close() does nothing.
        AcquiredSnapshot2 released = partition.acquireLatest();
        System.out.printf("Before the release: %d entities%n", released.size());
        released.close();
        tryRead("After the release", released);
        released.close();

        // A reader that never releases: its acquisition expires after the timeout, and the
        // view behaves like a released one. The clock is the monotonic clock of the node.
        AcquiredSnapshot2 forgotten = partition.acquireLatest(AcquireOptions.timeout(SHORT_TIMEOUT));
        System.out.printf("Before the timeout of %s: %d entities%n", SHORT_TIMEOUT, forgotten.size());
        Thread.sleep(SHORT_TIMEOUT.multipliedBy(2));
        tryRead("After the timeout", forgotten);
        forgotten.close();

        // An expired acquisition is never renewed: the reader acquires again.
        try (AcquiredSnapshot2 again = partition.acquireLatest(AcquireOptions.timeout(SHORT_TIMEOUT))) {
            System.out.printf("A new acquisition of tick %d: %d entities%n", again.tick(), again.size());
        }
    }

    private static void tryRead(String when, AcquiredSnapshot2 snapshot) {
        try {
            System.out.printf("%s: %d entities%n", when, snapshot.size());
        } catch (IllegalStateException e) {
            System.out.printf("%s the read fails: %s%n", when, e.getMessage());
        }
    }
}
