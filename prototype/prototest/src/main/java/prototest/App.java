package prototest;

import prototest.api.Reader;
import prototest.api.Service;
import prototest.api.Writer;
import prototest.proxy.FakeNetworkInterface;
import prototest.proxy.NodeProxy;
import prototest.proxy.ServiceClient;
import prototest.server.ServerNodeNetworkInterface;

public class App {

    private static Service service;

    static void main(String[] args) throws InterruptedException {
        try (var endpoint = new ServerNodeNetworkInterface(2000)) {
            var network = FakeNetworkInterface.wrap(endpoint).build();
            var proxy = new NodeProxy(network, 2000);
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
