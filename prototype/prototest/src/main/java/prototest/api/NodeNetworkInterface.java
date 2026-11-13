package prototest.api;

public interface NodeNetworkInterface {
    void createPartition(long requestId, String name);

    void destroyPartition(long requestId, String name);

    boolean partitionExists(long requestId, String name);

    void writeToPartition(long requestId, String name, String data);

    void commitPartition(long requestId, String name);

    String readPartitionSnapshot(long requestId, String name);
}
