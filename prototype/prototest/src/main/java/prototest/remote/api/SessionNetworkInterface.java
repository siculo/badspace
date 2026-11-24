package prototest.remote.api;

import java.util.UUID;

/** Remote operations for opening, renewing and closing a session. */
public interface SessionNetworkInterface {
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
}
