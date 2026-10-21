package badspace.service.persistence;

import badspace.common.geometry.Point3;
import badspace.common.partition.PartitionName;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The save of the copy of a 3D partition snapshot, in the {@link SaveFormat format} of the
 * saves. {@link #write} creates a temporary file in the directory of the partition, writes the
 * copy to it, forces it to the disk and renames it to the file of the tick, replacing a save
 * already there; then it forces the directory, so the rename stays. If it fails, it removes
 * the temporary file and the saves already there do not change. Two saves of the same tick,
 * also at the same time, write the same bytes, so the file is always a valid save.
 */
public final class PartitionSave3 {

    private static final int DIMENSIONS = 3;

    private final SaveDirectory saveDirectory;
    private final PartitionName name;
    private final PartitionCopy3 copy;

    public PartitionSave3(SaveDirectory saveDirectory, PartitionName name, PartitionCopy3 copy) {
        this.saveDirectory = saveDirectory;
        this.name = name;
        this.copy = copy;
    }

    /** Writes the save and returns its file. Call it once. */
    public Path write() throws IOException {
        Path directory = saveDirectory.createPartitionDirectory(name);
        Path temporary = Files.createTempFile(directory, copy.tick() + ".", SaveDirectory.TEMPORARY_EXTENSION);
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                writeContent(new SaveOutput(channel));
                channel.force(true);
            }
            Path file = saveDirectory.saveFile(name, copy.tick());
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            SaveDirectory.force(directory);
            return file;
        } catch (IOException | RuntimeException e) {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException suppressed) {
                e.addSuppressed(suppressed);
            }
            throw e;
        }
    }

    private void writeContent(SaveOutput output) throws IOException {
        int count = copy.size();
        SaveFormat.writeHeader(output, DIMENSIONS, name, copy.tick());
        output.writeInt(count);
        for (int i = 0; i < count; i++) {
            output.writeLong(copy.entityId(i));
        }
        for (int i = 0; i < count; i++) {
            Point3 position = copy.position(i);
            output.writeDouble(position.x());
            output.writeDouble(position.y());
            output.writeDouble(position.z());
        }
        SaveFormat.writeMetadata(output, count, copy::metadata);
        output.finish();
    }

    /**
     * Reads a 3D save of the partition with the given name, written by {@link #write}, and returns its
     * content as a copy.
     *
     * @throws IOException if the file cannot be read, is not a 3D save of the partition, or is
     *     corrupted
     */
    public static PartitionCopy3 read(Path file, PartitionName name) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            SaveInput input = new SaveInput(channel);
            long tick = SaveFormat.readHeader(input, DIMENSIONS, name);
            int count = SaveFormat.readCount(input, Long.BYTES + DIMENSIONS * Double.BYTES);
            long[] entityIds = new long[count];
            for (int i = 0; i < count; i++) {
                entityIds[i] = input.readLong();
            }
            double[] coords = new double[DIMENSIONS * count];
            for (int i = 0; i < coords.length; i++) {
                coords[i] = input.readDouble();
            }
            PartitionCopy3 copy = new PartitionCopy3(tick, entityIds, coords, SaveFormat.readMetadata(input, count));
            input.finish();
            return copy;
        }
    }
}
