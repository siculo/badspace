package prototest.api;

public interface Node {
    void createPartition(String name);

    void destroyPartition(String name);

    boolean partitionExists(String name);

    void writeToPartition(String name, String data);

    void commitPartition(String name);

    String readPartitionSnapshot(String name);
}
