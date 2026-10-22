package badspace.service.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks the saves in progress of NodeSaves: a save of a snapshot that another thread is saving
 * waits for it and gets its result, without writing again. The writers of these tests write no
 * file: they only stop where the test needs it.
 */
class NodeSavesTest {

    private static final PartitionName NAME = new PartitionName("north");

    @TempDir
    private Path directory;

    private NodeSaves saves;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        saves = new NodeSaves(directory);
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void saveOfASnapshotInProgressWaitsAndReturnsTheSameReferenceWithoutWriting() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Future<SavedPartition> first = executor.submit(() -> saves.save(NAME, 5, () -> { }, saveDirectory -> {
            writing.countDown();
            await(finish);
        }));
        writing.await();
        CountDownLatch released = new CountDownLatch(1);
        AtomicInteger writes = new AtomicInteger();
        Future<SavedPartition> second = executor.submit(() -> saves.save(NAME, 5, released::countDown,
                saveDirectory -> writes.incrementAndGet()));
        // The second save releases its snapshot before it waits for the first.
        released.await();
        assertFalse(second.isDone());
        finish.countDown();
        assertEquals(new SavedPartition(NAME, 5), first.get());
        assertEquals(new SavedPartition(NAME, 5), second.get());
        assertEquals(0, writes.get());
    }

    @Test
    void saveThatWaitsFailsIfTheOtherSaveFails() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);
        Future<SavedPartition> first = executor.submit(() -> saves.save(NAME, 5, () -> { }, saveDirectory -> {
            writing.countDown();
            await(fail);
            throw new IOException("Disk is full");
        }));
        writing.await();
        CountDownLatch released = new CountDownLatch(1);
        Future<SavedPartition> second = executor.submit(() -> saves.save(NAME, 5, released::countDown,
                saveDirectory -> { }));
        released.await();
        fail.countDown();
        assertCause(UncheckedIOException.class, first);
        assertCause(UncheckedIOException.class, second);
    }

    @Test
    void saveOfAnotherTickDoesNotWait() throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        Future<SavedPartition> first = executor.submit(() -> saves.save(NAME, 5, () -> { }, saveDirectory -> {
            writing.countDown();
            await(finish);
        }));
        writing.await();
        AtomicInteger writes = new AtomicInteger();
        assertEquals(new SavedPartition(NAME, 6), saves.save(NAME, 6, () -> { }, saveDirectory -> writes.incrementAndGet()));
        assertEquals(new SavedPartition(new PartitionName("south"), 5),
                saves.save(new PartitionName("south"), 5, () -> { }, saveDirectory -> writes.incrementAndGet()));
        assertEquals(2, writes.get());
        assertFalse(first.isDone());
        finish.countDown();
        first.get();
    }

    @Test
    void saveAfterTheEndOfAnotherSaveOfTheSameSnapshotWritesAgain() {
        AtomicInteger writes = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();
        saves.save(NAME, 5, releases::incrementAndGet, saveDirectory -> writes.incrementAndGet());
        saves.save(NAME, 5, releases::incrementAndGet, saveDirectory -> writes.incrementAndGet());
        assertEquals(2, writes.get());
        assertEquals(0, releases.get());
    }

    @Test
    void saveAfterAFailedSaveOfTheSameSnapshotWritesAgain() {
        assertThrows(UncheckedIOException.class, () -> saves.save(NAME, 5, () -> { }, saveDirectory -> {
            throw new IOException("Disk is full");
        }));
        AtomicInteger writes = new AtomicInteger();
        saves.save(NAME, 5, () -> { }, saveDirectory -> writes.incrementAndGet());
        assertEquals(1, writes.get());
    }

    /** Waits for a latch inside a writer, which can only throw IOException. */
    private static void await(CountDownLatch latch) throws InterruptedIOException {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting");
        }
    }

    /** Checks that a task failed with the given exception. */
    private static void assertCause(Class<? extends Throwable> expected, Future<?> task) throws InterruptedException {
        ExecutionException failure = assertThrows(ExecutionException.class, task::get);
        assertTrue(expected.isInstance(failure.getCause()), () -> "Unexpected failure: " + failure.getCause());
    }
}
