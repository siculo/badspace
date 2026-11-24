package prototest.example;

import prototest.example.api.NodeNetworkInterface;
import prototest.example.api.Reader;
import prototest.example.api.Service;
import prototest.example.api.Writer;
import prototest.example.proxy.NodeProxy;
import prototest.example.proxy.ServiceClient;
import prototest.example.server.NodeServer;
import prototest.example.server.ServerNodeNetworkInterface;
import prototest.remote.api.SessionNetworkInterface;
import prototest.remote.client.RemoteCallExecutor;
import prototest.remote.network.FakeNetworkInterface;
import prototest.remote.server.ServerSessionEndpoint;

public class App {

    private static Service service;

    static void main(String[] args) throws InterruptedException {
        try (var sessions = new ServerSessionEndpoint(2000)) {
            var node = new ServerNodeNetworkInterface(new NodeServer(), sessions);
            var sessionNetwork = FakeNetworkInterface.wrap(SessionNetworkInterface.class, sessions).build();
            var nodeNetwork = FakeNetworkInterface.wrap(NodeNetworkInterface.class, node).build();
            var proxy = new NodeProxy(nodeNetwork, new RemoteCallExecutor(sessionNetwork, 2000));
            proxy.connect();
            try {
                runDemo(proxy);
            } finally {
                proxy.disconnect();
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
