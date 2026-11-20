package prototest.server;

import org.junit.rules.ExternalResource;

import java.util.ArrayList;
import java.util.List;

/** Closes the endpoints created by a test so their cleanup tasks stop. */
public final class TestEndpoints extends ExternalResource {
    private final List<ServerNodeNetworkInterface> endpoints = new ArrayList<>();

    public synchronized ServerNodeNetworkInterface track(ServerNodeNetworkInterface endpoint) {
        endpoints.add(endpoint);
        return endpoint;
    }

    @Override
    protected synchronized void after() {
        endpoints.forEach(ServerNodeNetworkInterface::close);
    }
}
