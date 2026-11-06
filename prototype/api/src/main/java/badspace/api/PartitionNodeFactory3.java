package badspace.api;

import badspace.common.partition.PartitionNode3;

/**
 * Builds the nodes of a 3D space: {@link Space3#createNode(PartitionNodeFactory3)} calls it
 * once for each node. The software gives it as a lambda that builds a node from its
 * configuration, for example {@code () -> new LocalPartitionNode3(PartitionNodeConfig.of())},
 * and configures there the resources of the node, for example its save directory.
 * <p>
 * Each call must return a new node, empty and at tick 0, that no other space uses. Two handles
 * on the same node do not keep two spaces apart: the moves of the tick of one space would move
 * the partitions of the other. The same factory can be used again only if each call builds an
 * independent node, with its own resources: for example, nodes used at the same time have
 * different save directories.
 */
@FunctionalInterface
public interface PartitionNodeFactory3 {

    /** Returns a new node, empty and at tick 0. */
    PartitionNode3 create();
}
