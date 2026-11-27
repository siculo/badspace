package prototest.example;

import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import prototest.example.api.Reader;
import prototest.example.api.Service;
import prototest.example.api.Writer;
import prototest.example.grpc.GrpcNodeClient;
import prototest.example.grpc.GrpcPartitionService;
import prototest.example.proxy.NodeProxy;
import prototest.example.proxy.ServiceClient;
import prototest.example.server.NodeServer;
import prototest.example.server.ServerNodeNetworkInterface;
import badspace.remote.client.RemoteCallExecutor;
import badspace.remote.grpc.GrpcSessionClient;
import badspace.remote.grpc.GrpcSessionService;
import badspace.remote.server.ServerSessionEndpoint;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

public class App {

    private static Service service;

    public static void main(String[] args) throws IOException, InterruptedException {
        var address = new InetSocketAddress("127.0.0.1", 0);
        try (var sessions = new ServerSessionEndpoint(2000)) {
            var node = new ServerNodeNetworkInterface(new NodeServer(), sessions);
            var server = NettyServerBuilder.forAddress(address)
                    .addService(new GrpcSessionService(sessions))
                    .addService(new GrpcPartitionService(node))
                    .build();
            try {
                server.start();
                var channel = NettyChannelBuilder.forAddress(address.getHostString(), server.getPort())
                        .usePlaintext()
                        .disableRetry()
                        .build();
                try {
                    var attemptTimeout = Duration.ofSeconds(2);
                    var nodeClient = new GrpcNodeClient(channel, attemptTimeout);
                    var sessionClient = new GrpcSessionClient(channel, attemptTimeout);
                    var proxy = new NodeProxy(nodeClient, new RemoteCallExecutor(sessionClient, 2000));
                    proxy.connect();
                    try {
                        runDemo(proxy);
                    } finally {
                        proxy.disconnect();
                    }
                } finally {
                    channel.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
                }
            } finally {
                server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void runDemo(NodeProxy proxy) throws InterruptedException {
        service = new ServiceClient(proxy);
        service.create("ONE");
        service.create("TWO");

        WriterThread wr1 = new WriterThread("ONE", 5);
        WriterThread wr2 = new WriterThread("TWO", 5);
        ReaderThread rd1 = new ReaderThread("ONE", 20);
        ReaderThread rd2 = new ReaderThread("TWO", 20);
        wr1.start();
        wr2.start();
        rd1.start();
        rd2.start();
        wr1.join();
        wr2.join();
        rd1.join();
        rd2.join();
    }

    static class WriterThread extends Thread {

        private final Writer writer;
        private final int repetitions;

        public WriterThread(String name, int repetitions) {
            this.writer = service.writer(name);
            this.repetitions = repetitions;
        }

        @Override
        public void run() {
            for (int i = 1; i <= repetitions; i++) {
                writer.write("\"");
                writer.write(Thread.currentThread().getName());
                writer.write(": " + i);
                writer.write("\"");
                writer.commit();
            }
        }
    }

    static class ReaderThread extends Thread {

        private final String name;
        private final Reader reader;
        private final int repetitions;

        ReaderThread(String name, int repetitions) {
            this.name = name;
            this.reader = service.reader(name);
            this.repetitions = repetitions;
        }

        @Override
        public void run() {
            for (int i = 1; i <= repetitions; i++) {
                System.out.printf("< %s: %s%n", name, reader.content());
            }
        }
    }
}
