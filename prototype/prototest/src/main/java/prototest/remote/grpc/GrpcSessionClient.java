package prototest.remote.grpc;

import com.google.protobuf.Empty;
import io.grpc.Channel;
import prototest.remote.api.SessionNetworkInterface;
import prototest.remote.grpc.wire.SessionToken;
import prototest.remote.grpc.wire.SessionsGrpc;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Sends session calls over gRPC. */
public final class GrpcSessionClient implements SessionNetworkInterface {
    private final SessionsGrpc.SessionsBlockingStub sessions;
    private final long attemptTimeoutNanos;

    /**
     * Creates a session client with a deadline for each RPC.
     *
     * @param channel the gRPC channel, owned by the caller
     * @param attemptTimeout positive time allowed for each RPC
     * @throws IllegalArgumentException if the timeout is zero or negative
     */
    public GrpcSessionClient(Channel channel, Duration attemptTimeout) {
        attemptTimeoutNanos = attemptTimeout.toNanos();
        if (attemptTimeoutNanos <= 0) {
            throw new IllegalArgumentException("RPC attempt timeout must be positive");
        }
        sessions = SessionsGrpc.newBlockingStub(channel);
    }

    @Override
    public UUID connect() {
        var response = GrpcCalls.DEFAULT.call(() -> sessions().connect(Empty.getDefaultInstance()));
        return UUID.fromString(response.getValue());
    }

    @Override
    public UUID recover(UUID previousToken) {
        var response = GrpcCalls.DEFAULT.call(() -> sessions().recover(token(previousToken)));
        return UUID.fromString(response.getValue());
    }

    @Override
    public void disconnect(UUID token) {
        GrpcCalls.DEFAULT.call(() -> sessions().disconnect(token(token)));
    }

    private SessionsGrpc.SessionsBlockingStub sessions() {
        return sessions.withDeadlineAfter(attemptTimeoutNanos, TimeUnit.NANOSECONDS);
    }

    private static SessionToken token(UUID value) {
        return SessionToken.newBuilder().setValue(value.toString()).build();
    }
}
