package prototest.server;

class Partition {
    private final String name;
    private final StringBuilder currentContent = new StringBuilder();

    private String snapshot = "";

    Partition(String name) {
        this.name = name;
    }

    void write(String content) {
        currentContent.append(content);
    }

    void commit() {
        snapshot = currentContent.toString();
        System.out.printf("                                    > %s: %s%n", name, snapshot);
        currentContent.setLength(0);
    }

    String snapshot() {
        return snapshot;
    }
}
