package prototest.example.api;

public class ClientReader implements Reader {
    private final Node node;
    private final String name;

    public ClientReader(Node node, String name) {
        this.node = node;
        this.name = name;
    }

    @Override
    public String content() {
        return node.readPartitionSnapshot(name);
    }
}
