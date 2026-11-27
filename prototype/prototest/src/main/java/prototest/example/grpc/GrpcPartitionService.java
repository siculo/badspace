package prototest.example.grpc;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import prototest.example.api.NodeNetworkInterface;
import prototest.example.grpc.wire.ExistsResponse;
import prototest.example.grpc.wire.PartitionRequest;
import prototest.example.grpc.wire.PartitionsGrpc;
import prototest.example.grpc.wire.SnapshotResponse;
import prototest.example.grpc.wire.WriteRequest;
import prototest.remote.api.RequestId;

import java.util.Objects;
import java.util.UUID;

import static prototest.example.grpc.GrpcPartitionErrors.CALLS;

/** Exposes the example node's partition operations as a gRPC service. */
public final class GrpcPartitionService extends PartitionsGrpc.PartitionsImplBase {
    private final NodeNetworkInterface node;

    /**
     * Binds the RPCs to the node's network interface.
     *
     * @param node the operations backed by the session endpoint
     */
    public GrpcPartitionService(NodeNetworkInterface node) {
        this.node = Objects.requireNonNull(node, "node");
    }

    @Override
    public void createPartition(PartitionRequest request, StreamObserver<Empty> observer) {
        CALLS.respond(observer, () -> node.createPartition(requestId(request), name(request)));
    }

    @Override
    public void destroyPartition(PartitionRequest request, StreamObserver<Empty> observer) {
        CALLS.respond(observer, () -> node.destroyPartition(requestId(request), name(request)));
    }

    @Override
    public void partitionExists(PartitionRequest request, StreamObserver<ExistsResponse> observer) {
        CALLS.respond(observer, () -> ExistsResponse.newBuilder()
                .setExists(node.partitionExists(requestId(request), name(request))).build());
    }

    @Override
    public void writeToPartition(WriteRequest request, StreamObserver<Empty> observer) {
        var partition = request.getPartition();
        CALLS.respond(observer, () -> node.writeToPartition(
                requestId(partition), name(partition), request.hasData() ? request.getData() : null));
    }

    @Override
    public void commitPartition(PartitionRequest request, StreamObserver<Empty> observer) {
        CALLS.respond(observer, () -> node.commitPartition(requestId(request), name(request)));
    }

    @Override
    public void readPartitionSnapshot(PartitionRequest request, StreamObserver<SnapshotResponse> observer) {
        CALLS.respond(observer, () -> SnapshotResponse.newBuilder()
                .setSnapshot(node.readPartitionSnapshot(requestId(request), name(request))).build());
    }

    private static RequestId requestId(PartitionRequest request) {
        var id = request.getRequestId();
        return new RequestId(UUID.fromString(id.getSessionToken().getValue()), id.getSequence());
    }

    private static String name(PartitionRequest request) {
        return request.hasName() ? request.getName() : null;
    }
}
