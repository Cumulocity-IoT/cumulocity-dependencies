package org.cometd.server;

public enum SessionState {
    /**
     * Before handshake
     */
    UNINITILIZED,
    /**
     * After handshake but no connect message yet
     */
    INITIALIZED,
    /**
     * No active long poll or no active connection
     */
    INACTIVE,
    /**
     * active long poll or active connection
     */
    ACTIVE,
    /**
     * disconnected by client
     */
    DISCONNECTED,
    /**
     * timeouted by server
     */
    TIMEOUTED

}