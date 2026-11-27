package badspace.remote.grpc;

import com.google.protobuf.Empty;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import badspace.remote.api.errors.NodeRecoveryRequiredException;
import badspace.remote.api.errors.RetryableCommunicationException;
import badspace.remote.api.errors.SessionUnavailableException;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Executes unary gRPC calls and translates errors at both ends. */
public final class GrpcCalls {
    static final GrpcCalls DEFAULT = new GrpcCalls(status -> null, error -> null);

    private final Function<Status, RuntimeException> clientError;
    private final Function<RuntimeException, Status> serverError;

    /**
     * Creates a mapping for service-specific errors.
     *
     * @param clientError maps a gRPC status to a Java exception, or returns null to use the default
     * @param serverError maps a Java exception to a gRPC status, or returns null to use the default
     */
    public GrpcCalls(Function<Status, RuntimeException> clientError,
                     Function<RuntimeException, Status> serverError) {
        this.clientError = Objects.requireNonNull(clientError, "clientError");
        this.serverError = Objects.requireNonNull(serverError, "serverError");
    }

    /**
     * Runs a blocking RPC and translates a gRPC failure to a Java exception.
     *
     * @param operation the RPC to run
     * @param <T> the response type
     * @return the RPC response
     */
    public <T> T call(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (StatusRuntimeException error) {
            var status = error.getStatus();
            var failure = clientError.apply(status);
            if (failure == null) {
                failure = defaultClientError(status);
            }
            failure.initCause(error);
            throw failure;
        }
    }

    /**
     * Runs a server operation and sends an empty response on success.
     *
     * @param observer the RPC response observer
     * @param operation the server operation to run
     */
    public void respond(StreamObserver<Empty> observer, Runnable operation) {
        respond(observer, () -> {
            operation.run();
            return Empty.getDefaultInstance();
        });
    }

    /**
     * Runs a server operation and sends its result or a gRPC error.
     *
     * @param observer the RPC response observer
     * @param operation the server operation to run
     * @param <T> the response type
     */
    public <T> void respond(StreamObserver<T> observer, Supplier<T> operation) {
        T result;
        try {
            result = operation.get();
        } catch (RuntimeException error) {
            var status = serverError.apply(error);
            if (status == null) {
                status = defaultServerError(error);
            }
            observer.onError(status.asRuntimeException());
            return;
        }
        observer.onNext(result);
        observer.onCompleted();
    }

    private static RuntimeException defaultClientError(Status status) {
        var message = status.getDescription() == null ? status.getCode().name() : status.getDescription();
        return switch (status.getCode()) {
            case INVALID_ARGUMENT -> new IllegalArgumentException(message);
            case FAILED_PRECONDITION -> new SessionUnavailableException(message);
            case CANCELLED, DEADLINE_EXCEEDED, UNAVAILABLE -> new RetryableCommunicationException(message);
            default -> new NodeRecoveryRequiredException(message);
        };
    }

    private static Status defaultServerError(RuntimeException error) {
        return switch (error) {
            case SessionUnavailableException failure -> Status.FAILED_PRECONDITION.withDescription(failure.getMessage());
            case NodeRecoveryRequiredException failure -> Status.ABORTED.withDescription(failure.getMessage());
            case IllegalArgumentException failure -> Status.INVALID_ARGUMENT.withDescription(failure.getMessage());
            default -> Status.INTERNAL.withDescription("The server could not complete the call");
        };
    }
}
