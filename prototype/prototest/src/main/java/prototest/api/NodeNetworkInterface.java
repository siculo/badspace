package prototest.api;

public interface NodeNetworkInterface {
    void createPartition(RequestId requestId, String name);

    void destroyPartition(RequestId requestId, String name);

    boolean partitionExists(RequestId requestId, String name);

    void writeToPartition(RequestId requestId, String name, String data);

    void commitPartition(RequestId requestId, String name);

    String readPartitionSnapshot(RequestId requestId, String name);
}
