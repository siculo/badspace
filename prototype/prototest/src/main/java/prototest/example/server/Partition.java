package prototest.example.server;

class Partition {
    private final String name;
    // Concurrent node calls read and update this state under the partition monitor.
    private final StringBuilder currentContent = new StringBuilder();

    private String snapshot = "";

    Partition(String name) {
        this.name = name;
    }

    synchronized void write(String content) {
        currentContent.append(content);
    }

    synchronized void commit() {
        snapshot = currentContent.toString();
        System.out.printf("                                    > %s: %s%n", name, snapshot);
        currentContent.setLength(0);
    }

    synchronized String snapshot() {
        return snapshot;
    }
}
