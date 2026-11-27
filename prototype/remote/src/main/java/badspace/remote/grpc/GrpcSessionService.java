package badspace.remote.grpc;

import com.google.protobuf.Empty;
import io.grpc.stub.StreamObserver;
import badspace.remote.api.SessionNetworkInterface;
import badspace.remote.grpc.wire.SessionToken;
import badspace.remote.grpc.wire.SessionsGrpc;

import java.util.Objects;
import java.util.UUID;

/** Exposes the session endpoint as a gRPC service. */
public final class GrpcSessionService extends SessionsGrpc.SessionsImplBase {
    private final SessionNetworkInterface sessions;

    /**
     * Binds the RPCs to a session endpoint.
     *
     * @param sessions the endpoint that owns the sessions
     */
    public GrpcSessionService(SessionNetworkInterface sessions) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
    }

    @Override
    public void connect(Empty request, StreamObserver<SessionToken> observer) {
        GrpcCalls.DEFAULT.respond(observer, () -> token(sessions.connect()));
    }

    @Override
    public void recover(SessionToken request, StreamObserver<SessionToken> observer) {
        GrpcCalls.DEFAULT.respond(observer, () -> token(sessions.recover(UUID.fromString(request.getValue()))));
    }

    @Override
    public void disconnect(SessionToken request, StreamObserver<Empty> observer) {
        GrpcCalls.DEFAULT.respond(observer, () -> sessions.disconnect(UUID.fromString(request.getValue())));
    }

    private static SessionToken token(UUID token) {
        return SessionToken.newBuilder().setValue(token.toString()).build();
    }
}
