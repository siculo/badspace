/**
 * Autonomous copies of the content of a partition snapshot, for the persistence.
 * A copy holds the IDs, the positions and the metadata of the entities of a snapshot,
 * and does not depend on the life of the snapshot it came from. The node makes it as an
 * internal reader; it does not pass through the API.
 */
package badspace.service.persistence;
