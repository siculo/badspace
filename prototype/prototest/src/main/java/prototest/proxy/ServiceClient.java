package prototest.proxy;

import prototest.api.*;

public class ServiceClient implements Service {

    private final Node node;

    public ServiceClient(Node node) {
        this.node = node;
    }

    @Override
    public void create(String name) {
        node.createPartition(name);
    }

    @Override
    public void destroy(String name) {
        node.destroyPartition(name);
    }

    @Override
    public boolean exist(String name) {
        return node.partitionExists(name);
    }

    @Override
    public Writer writer(String name) {
        return new ClientWriter(node, name);
    }

    @Override
    public Reader reader(String name) {
        return new ClientReader(node, name);
    }
}
