package prototest.example.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import prototest.example.api.errors.service.PartitionNotFoundException;
import prototest.remote.api.errors.NodeRecoveryRequiredException;
import prototest.remote.api.errors.RetryableCommunicationException;
import prototest.remote.api.errors.SessionUnavailableException;

import java.util.function.Supplier;

/** Maps the application and session errors to gRPC status codes for this example. */
final class GrpcCalls {
    private GrpcCalls() {}

    static <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (StatusRuntimeException error) {
            var status = error.getStatus();
            var message = status.getDescription() == null ? status.getCode().name() : status.getDescription();
            RuntimeException failure = switch (status.getCode()) {
                case NOT_FOUND -> new PartitionNotFoundException(message);
                case INVALID_ARGUMENT -> new IllegalArgumentException(message);
                case FAILED_PRECONDITION -> new SessionUnavailableException(message);
                case CANCELLED, DEADLINE_EXCEEDED, UNAVAILABLE -> new RetryableCommunicationException(message);
                default -> new NodeRecoveryRequiredException(message);
            };
            failure.initCause(error);
            throw failure;
        }
    }

    static void respond(StreamObserver<Empty> observer, Runnable operation) {
        respond(observer, () -> {
            operation.run();
            return Empty.getDefaultInstance();
        });
    }

    static <T> void respond(StreamObserver<T> observer, Supplier<T> operation) {
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException error) {
            var status = switch (error) {
                case PartitionNotFoundException failure -> Status.NOT_FOUND.withDescription(failure.getMessage());
                case SessionUnavailableException failure -> Status.FAILED_PRECONDITION.withDescription(failure.getMessage());
                case NodeRecoveryRequiredException failure -> Status.ABORTED.withDescription(failure.getMessage());
                case IllegalArgumentException failure -> Status.INVALID_ARGUMENT.withDescription(failure.getMessage());
                default -> Status.INTERNAL.withDescription("The server could not complete the call");
            };
            observer.onError(status.asRuntimeException());
            return;
        }
        observer.onNext(result);
        observer.onCompleted();
    }
}
