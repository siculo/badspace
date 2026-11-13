package prototest.server;

import prototest.api.Node;
import prototest.api.errors.service.PartitionNotFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class NodeServer implements Node {
    private final Map<String, Partition> partitions = new HashMap<>();
    private final ReentrantLock partitionsLock = new ReentrantLock();

    @Override
    public void createPartition(String name) {
        partitionsLock.lock();
        try {
            partitions.computeIfAbsent(name, Partition::new);
        } finally {
            partitionsLock.unlock();
        }
    }

    @Override
    public void destroyPartition(String name) {
        partitionsLock.lock();
        try {
            partitions.remove(name);
        } finally {
            partitionsLock.unlock();
        }
    }

    @Override
    public boolean partitionExists(String name) {
        partitionsLock.lock();
        try {
            return partitions.containsKey(name);
        } finally {
            partitionsLock.unlock();
        }
    }

    @Override
    public void writeToPartition(String name, String data) {
        getPartition(name).write(data);
    }

    @Override
    public void commitPartition(String name) {
        getPartition(name).commit();
    }

    @Override
    public String readPartitionSnapshot(String name) {
        return getPartition(name).snapshot();
    }

    private Partition getPartition(String name) {
        partitionsLock.lock();
        try {
            if (partitions.containsKey(name)) {
                return partitions.get(name);
            }
        } finally {
            partitionsLock.unlock();
        }
        throw new PartitionNotFoundException("Partition \"%s\" does not exists".formatted(name));
    }
}
