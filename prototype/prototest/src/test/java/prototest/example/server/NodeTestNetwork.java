package prototest.example.server;

import prototest.example.api.NodeNetworkInterface;
import prototest.remote.api.SessionNetworkInterface;

/** Connects both contracts to the same simulated network. */
public interface NodeTestNetwork extends NodeNetworkInterface, SessionNetworkInterface {}
