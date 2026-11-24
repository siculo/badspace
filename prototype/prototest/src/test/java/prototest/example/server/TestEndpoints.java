package prototest.example.server;

import org.junit.rules.ExternalResource;

import java.util.ArrayList;
import java.util.List;

/** Closes the endpoints created by a test so their cleanup tasks stop. */
public final class TestEndpoints extends ExternalResource {
    private final List<NodeTestEndpoint> endpoints = new ArrayList<>();

    public synchronized NodeTestEndpoint track(NodeTestEndpoint endpoint) {
        endpoints.add(endpoint);
        return endpoint;
    }

    @Override
    protected synchronized void after() {
        endpoints.forEach(NodeTestEndpoint::close);
    }
}
