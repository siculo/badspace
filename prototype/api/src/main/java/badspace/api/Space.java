package badspace.api;

import badspace.common.partition.PartitionName;
import badspace.common.partition.SavedPartition;
import java.util.List;

/**
 * Operations shared by 2D and 3D spaces.
 * <p>
 * The operations that choose a node receive the handle of a node created by this space with
 * {@link #createNode}. They fail with IllegalArgumentException if the handle belongs to another
 * space, before they generate IDs, read or write files or call the node.
 *
 * @param <H> the type of handle of a node created by this space
 * @param <P> the type of partition of this space
 * @param <F> the type of factory that builds the nodes of this space
 */
public interface Space<H, P extends Partition, F> {

    /**
     * Builds a node with the factory and adds it to this space, at the tick of the space. From
     * then on, each {@link #setTick(long)} of the space reaches the node, also when it has no
     * partitions. The node stays with the space for the whole life of the space. The factory
     * runs outside the lock of the tick; the space then moves the node to its tick and adds it
     * while holding the lock, so the node misses no move of the tick. Any thread can call it.
     *
     * @param factory the factory of the node; each call must return a new node, empty and at
     *     tick 0, that no other space uses
     * @return the handle of the new node, which belongs to this space
     * @throws NullPointerException if factory is null
     * @throws IllegalArgumentException if the factory returns null or a node that is already in
     *     this space; in this case the space adds no node
     * @throws RuntimeException any error of the factory, or of the node when it moves to the
     *     tick of the space; in this case the space adds no node
     */
    H createNode(F factory);

    /**
     * Creates a partition with the given name on the given node, with the
     * {@linkplain PartitionConfig#defaults() default} configuration, at the tick of the space,
     * like {@link #createPartition(Object, PartitionName, PartitionConfig)}.
     */
    default P createPartition(H node, PartitionName name) {
        return createPartition(node, name, PartitionConfig.defaults());
    }

    /**
     * Creates a partition with the given name on the given node, with the given configuration,
     * at the tick of the space. The name is the public identifier of the partition, chosen by the
     * software: the saves of the partition are identified by it, also after the end of the run.
     * The new partition has no owner: the writer takes it with {@link Partition#claim(long)} and
     * writes through the handle of the token. The writer removes the partition with
     * {@link PartitionWriter#removePartition()}.
     *
     * @param node the handle of the node, created by this space
     * @param name the name of the partition
     * @param config the index, the removal policy and the retention window of the partition
     * @return the new partition
     * @throws NullPointerException if node, name or config is null
     * @throws IllegalArgumentException if the handle belongs to another space, or the name is
     *     already in use on the node, also by a removed partition that is not destroyed yet; the
     *     names on different nodes are not checked, so the software keeps them unique
     */
    P createPartition(H node, PartitionName name, PartitionConfig config);

    /**
     * Creates a partition with the content of a save: the same entity IDs, positions and
     * application metadata. Like {@link #createPartition(Object, PartitionName, PartitionConfig)},
     * the partition has no owner. The moves of the tick do not wait for the load: the partition
     * is created at the tick of the node when the load ends, which can be later than the tick
     * of the call; {@link Partition#creationTick()} gives it. The load commits the content at
     * the creation tick, so the software does not commit it: readers acquire it after the next
     * move of the tick. To change the content before the readers see it, the writer claims the
     * partition, writes and commits in the same tick: its commit replaces the one of the load.
     * The metadata are loaded as they are: the software updates the ones that hold ticks.
     *
     * @param node the handle of the node, created by this space, that creates the partition and
     *     has the save in its save destination
     * @param config the index and the retention window of the partition; they do not come from
     *     the save
     * @param saved the save to load; the partition takes its name and a new ID
     * @return the loaded partition
     * @throws NullPointerException if node, config or saved is null
     * @throws IllegalArgumentException if the handle belongs to another space, the name of the
     *     save is already in use on the node, or a position of the save is outside the limits of
     *     the index
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if the save is not there or cannot be read, for
     *     example because it is corrupted
     */
    P loadPartition(H node, PartitionConfig config, SavedPartition saved);

    /**
     * Moves the tick of the space forward to the given tick, and then the tick of each node
     * created by the space with {@link #createNode}. Each node moves its partitions. The
     * partitions move one after the other, not all at the same moment. The same tick again does
     * nothing, and ticks can be skipped. Fails with IllegalArgumentException if the tick is less
     * than the tick of the space; in this case nothing changes.
     */
    void setTick(long tick);

    /** Returns the tick of the space: 0 for a new space. Any thread can call it. */
    long tick();

    /**
     * Returns the saves in the save destination of the node, sorted by name and then by tick.
     * The saves do not need a partition: they stay after the removal of their partition and
     * after the end of the run that wrote them, so the space gives them also for a node with no
     * partitions, for example a new node after a restart. The node has no rule on which saves to
     * keep: the software chooses which ones to delete with {@link #deleteSaves}. Any thread can
     * call it.
     *
     * @param node the handle of the node, created by this space
     * @return the saves of the node
     * @throws NullPointerException if node is null
     * @throws IllegalArgumentException if the handle belongs to another space
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if the destination cannot be read
     */
    List<SavedPartition> saves(H node);

    /**
     * Returns the saves of a partition name in the save destination of the node, sorted by
     * tick, like {@link #saves(Object)}; empty if the name has no saves.
     */
    List<SavedPartition> saves(H node, PartitionName name);

    /**
     * Deletes the given saves from the save destination of the node, in the order of the list.
     * A save that is not there is skipped, so deleting the same save twice does nothing. Any
     * thread can call it.
     *
     * @param node the handle of the node, created by this space
     * @param saves the saves to delete
     * @throws NullPointerException if node or saves is null
     * @throws IllegalArgumentException if the handle belongs to another space
     * @throws IllegalStateException if the node has no save destination
     * @throws java.io.UncheckedIOException if a save cannot be deleted: the saves before it in
     *     the list are already deleted
     */
    void deleteSaves(H node, List<SavedPartition> saves);
}
