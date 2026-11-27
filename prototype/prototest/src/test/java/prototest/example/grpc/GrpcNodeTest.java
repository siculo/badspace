package prototest.example.grpc;

import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import prototest.example.api.errors.service.PartitionNotFoundException;
import prototest.example.grpc.wire.PartitionsGrpc;
import prototest.example.proxy.NodeProxy;
import prototest.example.server.NodeServer;
import prototest.example.server.ServerNodeNetworkInterface;
import prototest.remote.api.errors.SessionUnavailableException;
import prototest.remote.client.RemoteCallExecutor;
import prototest.remote.grpc.GrpcSessionClient;
import prototest.remote.grpc.GrpcSessionService;
import prototest.remote.server.ServerSessionEndpoint;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

/** Tests the example proxy and services over a local gRPC connection. */
public class GrpcNodeTest {
    private static final long SESSION_TIMEOUT = 1000;
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    // The test sets these values. The session endpoint and interceptor read them on server threads.
    private final AtomicLong now = new AtomicLong();
    private final AtomicBoolean loseWriteResponse = new AtomicBoolean();
    private final AtomicInteger writeAttempts = new AtomicInteger();
    private ServerSessionEndpoint sessions;
    private Server server;
    private ManagedChannel channel;
    private NodeProxy proxy;

    @Before
    public void startServer() throws Exception {
        InstantSource time = () -> Instant.ofEpochMilli(now.get());
        sessions = new ServerSessionEndpoint(60000, SESSION_TIMEOUT, time.withZone(ZoneOffset.UTC));
        var node = new ServerNodeNetworkInterface(new NodeServer(), sessions);
        var address = new InetSocketAddress("127.0.0.1", 0);
        server = NettyServerBuilder.forAddress(address)
                .addService(new GrpcSessionService(sessions))
                .addService(ServerInterceptors.intercept(new GrpcPartitionService(node), writeResponseInterceptor()))
                .build();
        server.start();
        channel = NettyChannelBuilder.forAddress(address.getHostString(), server.getPort())
                .usePlaintext()
                .disableRetry()
                .build();
        var nodeClient = new GrpcNodeClient(channel, ATTEMPT_TIMEOUT);
        var sessionClient = new GrpcSessionClient(channel, ATTEMPT_TIMEOUT);
        proxy = new NodeProxy(nodeClient, new RemoteCallExecutor(sessionClient, ATTEMPT_TIMEOUT.toMillis()));
    }

    @After
    public void stopServer() throws InterruptedException {
        try {
            if (channel != null) {
                channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        } finally {
            try {
                if (server != null) {
                    server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                if (sessions != null) {
                    sessions.close();
                }
            }
        }
    }

    @Test
    public void proxyRunsPartitionOperationsAndCanReconnect() {
        proxy.connect();
        assertFalse(proxy.partitionExists("p"));
        assertThrows(PartitionNotFoundException.class, () -> proxy.readPartitionSnapshot("p"));
        assertFalse(proxy.recoveryRequired());

        proxy.createPartition("p");
        proxy.writeToPartition("p", "caffè");
        proxy.commitPartition("p");
        assertEquals("caffè", proxy.readPartitionSnapshot("p"));
        proxy.disconnect();

        proxy.connect();
        assertTrue(proxy.partitionExists("p"));
        proxy.destroyPartition("p");
        assertFalse(proxy.partitionExists("p"));
    }

    @Test
    public void expiredSessionCanBeRecoveredOverGrpc() {
        proxy.connect();
        now.set(SESSION_TIMEOUT);

        assertThrows(SessionUnavailableException.class, () -> proxy.createPartition("p"));
        assertTrue(proxy.recoveryRequired());

        proxy.recover();
        assertFalse(proxy.recoveryRequired());
        assertFalse(proxy.partitionExists("p"));
        proxy.createPartition("p");
        assertTrue(proxy.partitionExists("p"));
    }

    @Test
    public void retryAfterALostWriteResponseAppliesTheWriteOnce() {
        proxy.connect();
        proxy.createPartition("p");
        loseWriteResponse.set(true);

        proxy.writeToPartition("p", "saved");
        proxy.commitPartition("p");

        assertEquals(2, writeAttempts.get());
        assertEquals("saved", proxy.readPartitionSnapshot("p"));
        assertFalse(proxy.recoveryRequired());
    }

    private ServerInterceptor writeResponseInterceptor() {
        return new ServerInterceptor() {
            @Override
            public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
                    ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> next) {
                if (call.getMethodDescriptor().getFullMethodName()
                        .equals(PartitionsGrpc.getWriteToPartitionMethod().getFullMethodName())) {
                    writeAttempts.incrementAndGet();
                    if (loseWriteResponse.getAndSet(false)) {
                        return next.startCall(new SimpleForwardingServerCall<>(call) {
                            @Override
                            public void sendMessage(RespT message) {
                                // Suppress the result after the server has executed the write.
                            }

                            @Override
                            public void close(Status status, Metadata trailers) {
                                super.close(status.isOk() ? Status.UNAVAILABLE : status, trailers);
                            }
                        }, headers);
                    }
                }
                return next.startCall(call, headers);
            }
        };
    }
}
