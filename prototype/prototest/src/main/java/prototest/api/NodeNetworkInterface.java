package prototest.api;

import java.util.UUID;

public interface NodeNetworkInterface {
    /**
     * Opens a session on the server.
     * @return the new session token
     */
    UUID connect();

    /**
     * Revokes the previous token and opens a session, even if that token is already absent.
     * @param previousToken the token to revoke
     * @return the new session token
     */
    UUID recover(UUID previousToken);

    /**
     * Revokes a session. A token that is already absent is treated as disconnected.
     * @param token the token to revoke
     */
    void disconnect(UUID token);

    void createPartition(RequestId requestId, String name);

    void destroyPartition(RequestId requestId, String name);

    boolean partitionExists(RequestId requestId, String name);

    void writeToPartition(RequestId requestId, String name, String data);

    void commitPartition(RequestId requestId, String name);

    String readPartitionSnapshot(RequestId requestId, String name);
}
