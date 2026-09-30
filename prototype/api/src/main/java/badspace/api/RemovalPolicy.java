package badspace.api;

/** What happens to the entities of a partition when the partition is removed. It is chosen at creation. */
public enum RemovalPolicy {

    /** The partition can be removed only when it is empty: its entities must be removed or moved first. */
    REQUIRE_EMPTY,

    /** The partition is removed together with its entities. */
    DISCARD_ENTITIES
}
