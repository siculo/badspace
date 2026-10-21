/**
 * The persistence of the content of a partition snapshot.
 * <p>
 * An autonomous copy holds the IDs, the positions and the metadata of the entities of a
 * snapshot, and does not depend on the life of the snapshot it came from. The node makes it as
 * an internal reader; it does not pass through the API.
 * <p>
 * A save writes a copy to a binary file in the save directory of the node, through a temporary
 * file renamed when it is complete, and reads it back. {@link badspace.service.persistence.SaveFormat}
 * describes the format.
 */
package badspace.service.persistence;
