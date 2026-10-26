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
 * Sample client: a reader ends an acquisition with a release, and every acquisition also has a
 * timeout, so a reader that never releases cannot keep a snapshot in memory for ever. After the
 * release or the timeout the reads fail, and the reader acquires again.
 */
public final class AcquisitionTimeouts {

    private static final Duration SHORT_TIMEOUT = Duration.ofMillis(200);

    public static void main(String[] args) throws InterruptedException {
        Space2 space = new Space2(SpaceConfig.of(new SnowflakeIdGenerator(0), new SnowflakeIdGenerator(0)));
        Partition2 partition = space.createPartition(new LocalPartitionNode2(), partitionName("world"));
        try (PartitionWriter2 writer = partition.writer(partition.claim(0).orElseThrow())) {
            writer.insert(1, 1);
            writer.commit();
        }
        space.setTick(1);

        // close() releases the acquisition; try-with-resources usually calls it.
        AcquiredSnapshot2 released = partition.acquireLatest();
        System.out.printf("Before the release: %d entities%n", released.size());
        released.close();
        tryRead("After the release", released);

        // Without options the timeout is AcquireOptions.DEFAULT_TIMEOUT. A reader that forgets
        // the release loses the acquisition when the timeout ends.
        AcquiredSnapshot2 forgotten = partition.acquireLatest(AcquireOptions.timeout(SHORT_TIMEOUT));
        System.out.printf("Before the timeout of %s: %d entities%n", SHORT_TIMEOUT, forgotten.size());
        Thread.sleep(SHORT_TIMEOUT.multipliedBy(2));
        tryRead("After the timeout", forgotten);

        // An expired acquisition is not renewed: the reader acquires the snapshot again.
        try (AcquiredSnapshot2 again = partition.acquireLatest()) {
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
