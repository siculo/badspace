package badspace.common.partition;

import java.util.ArrayList;
import java.util.List;

/**
 * The write session of the owner of a 3D partition in the {@linkplain WriteMode#DIRECT direct} mode:
 * each call goes to the node at once, as a batch of changes (see
 * {@link PartitionNode3#write(WriteToken, List)}), and the commit confirms them. The writer
 * keeps nothing: the node keeps the writes not committed, checks the positions and the token,
 * and discards the writes when it goes back to the last commit. It is not thread-safe.
 */
final class DirectWriteSession3 implements WriteSession3 {

    private final PartitionNode3 node;
    private final WriteToken token;

    DirectWriteSession3(PartitionNode3 node, WriteToken token) {
        this.node = node;
        this.token = token;
    }

    @Override
    public WriteToken token() {
        return token;
    }

    @Override
    public void setAll(List<Entity3> entities) {
        List<EntityWrite3> changes = new ArrayList<>(entities.size());
        for (Entity3 entity : entities) {
            changes.add(EntityWrite3.set(entity));
        }
        send(changes);
    }

    @Override
    public void removeAll(long[] entityIds) {
        List<EntityWrite3> changes = new ArrayList<>(entityIds.length);
        for (long entityId : entityIds) {
            changes.add(EntityWrite3.removal(entityId));
        }
        send(changes);
    }

    @Override
    public void updateMetadataAll(List<MetadataWrite> writes) {
        // One change for each run of writes of the same entity: the node applies them in order,
        // each on the state of the moment, so a write on an entity that is not there does nothing.
        List<EntityWrite3> changes = new ArrayList<>();
        int start = 0;
        for (int i = 1; i <= writes.size(); i++) {
            if (i == writes.size() || writes.get(i).entityId() != writes.get(start).entityId()) {
                changes.add(EntityWrite3.metadataOf(writes.get(start).entityId(), writes.subList(start, i)));
                start = i;
            }
        }
        send(changes);
    }

    @Override
    public void commit() {
        node.commit(token, List.of());
    }

    @Override
    public void rollback() {
        node.rollback(token);
    }

    @Override
    public void release() {
        node.release(token);
    }

    @Override
    public void discard() {
        // The writer keeps no writes: the node discards its own.
    }

    /** Sends the changes to the node; without changes there is no call, like with the buffer. */
    private void send(List<EntityWrite3> changes) {
        if (!changes.isEmpty()) {
            node.write(token, changes);
        }
    }
}
