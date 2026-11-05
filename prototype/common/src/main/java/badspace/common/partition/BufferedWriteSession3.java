package badspace.common.partition;

import java.util.List;

/**
 * The write session of the owner of a 3D partition in the {@linkplain WriteMode#BUFFERED buffered}
 * mode. The writes make no calls to the node: they stay here, composed by entity (see
 * {@link EntityWrite3}), and {@link #commit()} sends them to the node in one call;
 * {@link #flush()} sends them before, without the commit. The
 * positions are checked against the limits of the partition at once; the node checks the
 * token and the state of the partition at the commit. A rejected commit keeps the writes, and
 * a failed one discards them, like in the direct mode. It is not thread-safe.
 */
final class BufferedWriteSession3 implements WriteSession3 {

    private final PartitionNode3 node;
    private final WriteToken token;
    private final CoordinateLimits limits;
    private final WriteBuffer3 buffer = new WriteBuffer3();

    BufferedWriteSession3(PartitionNode3 node, WriteToken token, CoordinateLimits limits) {
        this.node = node;
        this.token = token;
        this.limits = limits;
    }

    @Override
    public WriteToken token() {
        return token;
    }

    @Override
    public void setAll(List<Entity3> entities) {
        for (Entity3 entity : entities) {
            if (!limits.contains(entity.position())) {
                throw new IllegalArgumentException("Position of entity " + entity.id() + " is outside the limits "
                        + limits.min() + ", " + limits.max() + ": " + entity.position());
            }
        }
        for (Entity3 entity : entities) {
            buffer.set(entity);
        }
    }

    @Override
    public void removeAll(long[] entityIds) {
        for (long entityId : entityIds) {
            buffer.remove(entityId);
        }
    }

    @Override
    public void updateMetadataAll(List<MetadataWrite> writes) {
        for (MetadataWrite write : writes) {
            buffer.write(write);
        }
    }

    @Override
    public void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        try {
            node.write(token, buffer.changes());
        } catch (PartitionFailureException | Error failure) {
            buffer.clear();
            throw failure;
        }
        buffer.clear();
    }

    @Override
    public void commit() {
        try {
            node.commit(token, buffer.changes());
        } catch (PartitionFailureException | Error failure) {
            buffer.clear();
            throw failure;
        }
        buffer.clear();
    }

    @Override
    public void rollback() {
        buffer.clear();
        node.rollback(token);
    }

    @Override
    public void release() {
        buffer.clear();
        node.release(token);
    }

    @Override
    public void discard() {
        buffer.clear();
    }
}
