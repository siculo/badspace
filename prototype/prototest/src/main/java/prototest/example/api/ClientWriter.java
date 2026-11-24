package prototest.example.api;

public class ClientWriter implements Writer {
    private final Node node;
    private final String name;

    public ClientWriter(Node node, String name) {
        this.node = node;
        this.name = name;
    }

    @Override
    public void write(String content) {
        node.writeToPartition(name, content);
    }

    @Override
    public void commit() {
        node.commitPartition(name);
    }
}
