package prototest.example.grpc;

import com.google.protobuf.Empty;
import io.grpc.Channel;
import prototest.example.api.NodeNetworkInterface;
import prototest.example.grpc.wire.PartitionRequest;
import prototest.example.grpc.wire.PartitionsGrpc;
import prototest.example.grpc.wire.WriteRequest;
import prototest.remote.api.RequestId;
import prototest.remote.api.SessionNetworkInterface;
import prototest.remote.grpc.wire.RequestIdentity;
import prototest.remote.grpc.wire.SessionToken;
import prototest.remote.grpc.wire.SessionsGrpc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Sends the example node and session calls over a shared gRPC channel. */
public final class GrpcNodeClient implements NodeNetworkInterface, SessionNetworkInterface {
    private final PartitionsGrpc.PartitionsBlockingStub partitions;
    private final SessionsGrpc.SessionsBlockingStub sessions;
    private final long attemptTimeoutNanos;

    /**
     * Creates a client with a deadline for each RPC. The call executor controls
     * the time allowed for retries of node calls.
     *
     * @param channel the gRPC channel, owned by the caller
     * @param attemptTimeout positive time allowed for each RPC
     */
    public GrpcNodeClient(Channel channel, Duration attemptTimeout) {
        attemptTimeoutNanos = attemptTimeout.toNanos();
        if (attemptTimeoutNanos <= 0) {
            throw new IllegalArgumentException("RPC attempt timeout must be positive");
        }
        partitions = PartitionsGrpc.newBlockingStub(channel);
        sessions = SessionsGrpc.newBlockingStub(channel);
    }

    @Override
    public UUID connect() {
        var response = GrpcCalls.call(() -> sessions().connect(Empty.getDefaultInstance()));
        return UUID.fromString(response.getValue());
    }

    @Override
    public UUID recover(UUID previousToken) {
        var response = GrpcCalls.call(() -> sessions().recover(token(previousToken)));
        return UUID.fromString(response.getValue());
    }

    @Override
    public void disconnect(UUID token) {
        GrpcCalls.call(() -> sessions().disconnect(token(token)));
    }

    @Override
    public void createPartition(RequestId requestId, String name) {
        GrpcCalls.call(() -> partitions().createPartition(partition(requestId, name)));
    }

    @Override
    public void destroyPartition(RequestId requestId, String name) {
        GrpcCalls.call(() -> partitions().destroyPartition(partition(requestId, name)));
    }

    @Override
    public boolean partitionExists(RequestId requestId, String name) {
        return GrpcCalls.call(() -> partitions().partitionExists(partition(requestId, name))).getExists();
    }

    @Override
    public void writeToPartition(RequestId requestId, String name, String data) {
        var request = WriteRequest.newBuilder().setPartition(partition(requestId, name));
        if (data != null) {
            request.setData(data);
        }
        GrpcCalls.call(() -> partitions().writeToPartition(request.build()));
    }

    @Override
    public void commitPartition(RequestId requestId, String name) {
        GrpcCalls.call(() -> partitions().commitPartition(partition(requestId, name)));
    }

    @Override
    public String readPartitionSnapshot(RequestId requestId, String name) {
        return GrpcCalls.call(() -> partitions().readPartitionSnapshot(partition(requestId, name))).getSnapshot();
    }

    private SessionsGrpc.SessionsBlockingStub sessions() {
        return sessions.withDeadlineAfter(attemptTimeoutNanos, TimeUnit.NANOSECONDS);
    }

    private PartitionsGrpc.PartitionsBlockingStub partitions() {
        return partitions.withDeadlineAfter(attemptTimeoutNanos, TimeUnit.NANOSECONDS);
    }

    private static SessionToken token(UUID value) {
        return SessionToken.newBuilder().setValue(value.toString()).build();
    }

    private static PartitionRequest partition(RequestId requestId, String name) {
        var request = PartitionRequest.newBuilder().setRequestId(RequestIdentity.newBuilder()
                .setSessionToken(token(requestId.sessionToken()))
                .setSequence(requestId.sequence()));
        if (name != null) {
            request.setName(name);
        }
        return request.build();
    }
}
