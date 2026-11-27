package badspace.remote.api;

import java.util.UUID;

/** Remote operations for opening, renewing and closing a session. */
public interface SessionNetworkInterface {
    /**
     * Opens a session on the server.
     * @return the new session token
     */
    UUID connect();

    /**
     * Revokes the previous token and waits for its active operations before opening a
     * session. Opens a session directly if the previous token is already absent.
     * @param previousToken the token to revoke
     * @return the new session token
     */
    UUID recover(UUID previousToken);

    /**
     * Revokes a session and waits for its active operations. A token that is already
     * absent is treated as disconnected.
     * @param token the token to revoke
     */
    void disconnect(UUID token);
}
