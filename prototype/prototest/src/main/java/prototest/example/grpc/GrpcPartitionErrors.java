package prototest.example.grpc;

import io.grpc.Status;
import prototest.example.api.errors.service.PartitionNotFoundException;
import prototest.remote.grpc.GrpcCalls;

/** Maps partition errors between Java and gRPC. */
final class GrpcPartitionErrors {
    static final GrpcCalls CALLS = new GrpcCalls(
            GrpcPartitionErrors::toException, GrpcPartitionErrors::toStatus);

    private GrpcPartitionErrors() {}

    private static RuntimeException toException(Status status) {
        if (status.getCode() != Status.Code.NOT_FOUND) {
            return null;
        }
        var message = status.getDescription() == null ? status.getCode().name() : status.getDescription();
        return new PartitionNotFoundException(message);
    }

    private static Status toStatus(RuntimeException error) {
        return error instanceof PartitionNotFoundException
                ? Status.NOT_FOUND.withDescription(error.getMessage())
                : null;
    }
}
