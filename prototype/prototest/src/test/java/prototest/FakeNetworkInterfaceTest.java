package prototest;

import org.junit.Test;
import prototest.api.RequestId;
import prototest.api.errors.network.RetryableCommunicationException;
import prototest.api.errors.service.PartitionNotFoundException;
import prototest.proxy.FakeNetworkInterface;
import prototest.server.NodeServer;
import prototest.server.ServerNodeNetworkInterface;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static prototest.proxy.FakeNetworkInterface.Delivery.*;
import static prototest.proxy.FakeNetworkInterface.Operation.*;

/** Checks plan selection, observation and explicit delivery decisions. */
public class FakeNetworkInterfaceTest {
    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final RequestId REQUEST_ID = new RequestId(new UUID(0, 1), 0);
    private final NodeServer server = new NodeServer();
    private final FakeNetworkInterface network =
            new FakeNetworkInterface(new ServerNodeNetworkInterface(server, 60000));

    @Test
    public void unmatchedOperationsRunWithoutConsumingOtherPlans() {
        network.expect(call -> call.operation() == DESTROY_PARTITION, DROP, DELIVER);

        network.createPartition(REQUEST_ID, "present");

        assertTrue(server.partitionExists("present"));
        assertThrows(IllegalStateException.class, network::verify);
        assertThrows(RetryableCommunicationException.class,
                () -> network.destroyPartition(new RequestId(REQUEST_ID.proxyId(), 1), "present"));
        assertTrue(server.partitionExists("present"));
        network.verify();
    }

    @Test
    public void eachMethodProvidesItsOperationIdAndPartitionToTheSelector() throws Exception {
        long sequence = 0;
        for (var operation : FakeNetworkInterface.Operation.values()) {
            var requestId = new RequestId(REQUEST_ID.proxyId(), sequence++);
            var plan = network.expect(call -> call.operation() == operation
                    && call.requestId().equals(requestId)
                    && call.partitionName().equals("target"), DROP, DELIVER);
            assertThrows(RetryableCommunicationException.class, () -> {
                switch (operation) {
                    case CREATE_PARTITION -> network.createPartition(requestId, "target");
                    case DESTROY_PARTITION -> network.destroyPartition(requestId, "target");
                    case PARTITION_EXISTS -> network.partitionExists(requestId, "target");
                    case WRITE_TO_PARTITION -> network.writeToPartition(requestId, "target", "value");
                    case COMMIT_PARTITION -> network.commitPartition(requestId, "target");
                    case READ_PARTITION_SNAPSHOT -> network.readPartitionSnapshot(requestId, "target");
                }
            });
            assertEquals(new FakeNetworkInterface.Invocation(operation, requestId, "target"),
                    plan.request().awaitArrival(Duration.ZERO));
        }
        assertFalse(server.partitionExists("target"));
        network.verify();
    }

    @Test
    public void aDroppedRequestDoesNotReachTheEndpointOrResponsePoint() throws Exception {
        var plan = network.expect(call -> true, DROP, HOLD);

        var error = assertThrows(RetryableCommunicationException.class,
                () -> network.createPartition(REQUEST_ID, "missing"));

        assertEquals("Request missing", error.getMessage());
        assertFalse(server.partitionExists("missing"));
        assertEquals(REQUEST_ID, plan.request().awaitArrival(Duration.ZERO).requestId());
        assertThrows(IllegalStateException.class, () -> plan.response().awaitArrival(Duration.ZERO));
        plan.awaitCompletion(Duration.ZERO);
        network.verify();
    }

    @Test
    public void aDroppedResponseKeepsTheEndpointEffects() {
        network.expect(call -> true, DELIVER, DROP);

        var error = assertThrows(RetryableCommunicationException.class,
                () -> network.createPartition(REQUEST_ID, "present"));

        assertEquals("Response missing", error.getMessage());
        assertTrue(server.partitionExists("present"));
        network.verify();
    }

    @Test
    public void endpointErrorsSkipTheResponsePointAndFinishThePlan() {
        var plan = network.expect(call -> true, DELIVER, HOLD);

        assertThrows(PartitionNotFoundException.class,
                () -> network.writeToPartition(REQUEST_ID, "missing", "value"));

        assertThrows(IllegalStateException.class, () -> plan.response().awaitArrival(Duration.ZERO));
        network.verify();
    }

    @Test
    public void aHeldRequestCanBeDroppedWithoutCallingTheEndpoint() throws Exception {
        var plan = network.expect(call -> true, HOLD, DELIVER);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> assertThrows(RetryableCommunicationException.class,
                    () -> network.createPartition(REQUEST_ID, "missing")));
            plan.request().awaitArrival(WAIT);
            assertFalse(server.partitionExists("missing"));
            assertFalse(result.isDone());
            assertThrows(IllegalStateException.class, network::verify);
            assertThrows(IllegalStateException.class, () -> plan.awaitCompletion(Duration.ZERO));

            plan.request().drop();

            assertEquals("Request missing", result.get(5, TimeUnit.SECONDS).getMessage());
            assertFalse(server.partitionExists("missing"));
            network.verify();
        } finally {
            release(plan.request());
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void aHeldResponseReturnsItsValueOnlyAfterDelivery() throws Exception {
        server.createPartition("present");
        var plan = network.expect(call -> true, DELIVER, HOLD);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> network.partitionExists(REQUEST_ID, "present"));
            plan.response().awaitArrival(WAIT);
            assertFalse(result.isDone());

            plan.response().deliver();

            assertTrue(result.get(5, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, plan.response()::deliver);
            assertThrows(IllegalStateException.class, plan.response()::drop);
            network.verify();
        } finally {
            release(plan.response());
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void heldMessagesIgnoreCancellationAndPreserveTheWorkerInterruptFlag() throws Exception {
        var plan = network.expect(call -> true, DELIVER, HOLD);
        var worker = Executors.newSingleThreadExecutor();
        try {
            var result = worker.submit(() -> {
                Thread.currentThread().interrupt();
                network.createPartition(REQUEST_ID, "present");
                return Thread.currentThread().isInterrupted();
            });
            plan.response().awaitArrival(WAIT);
            assertFalse(result.isDone());
            assertTrue(server.partitionExists("present"));

            plan.response().deliver();

            assertTrue(result.get(5, TimeUnit.SECONDS));
            network.verify();
        } finally {
            release(plan.response());
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    public void anObserverCanTimeOutWithoutAnArrival() {
        var plan = network.expect(call -> true, DELIVER, DELIVER);

        assertThrows(IllegalStateException.class,
                () -> plan.request().awaitArrival(Duration.ofMillis(1)));
        assertThrows(IllegalStateException.class,
                () -> plan.awaitCompletion(Duration.ofMillis(1)));
        assertThrows(IllegalStateException.class, network::verify);
    }

    @Test
    public void observationTimeoutsDoNotConsumeOrReleaseAPlan() throws Exception {
        var plan = network.expect(call -> true, DELIVER, DELIVER);

        assertThrows(IllegalStateException.class, () -> plan.request().awaitArrival(Duration.ZERO));
        assertThrows(IllegalStateException.class, () -> plan.awaitCompletion(Duration.ZERO));
        assertThrows(IllegalStateException.class, plan.request()::deliver);
        assertThrows(IllegalStateException.class, plan.request()::drop);
        assertThrows(IllegalArgumentException.class,
                () -> plan.request().awaitArrival(Duration.ofNanos(-1)));
        assertThrows(IllegalArgumentException.class,
                () -> plan.awaitCompletion(Duration.ofNanos(-1)));

        network.createPartition(REQUEST_ID, "present");

        assertEquals(REQUEST_ID, plan.response().awaitArrival(WAIT).requestId());
        plan.awaitCompletion(WAIT);
        network.verify();
    }

    private static void release(FakeNetworkInterface.ExchangePoint point) {
        try {
            point.deliver();
        } catch (IllegalStateException ignored) {
            // Cleanup also runs when a test fails before arrival or after release.
        }
    }
}
