package badspace.service.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.PartitionName;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks that the format of the saves keeps the keys of the metadata exactly: the write fails
 * on a key that UTF-8 cannot keep, and the read fails on keys that are not valid UTF-8 or that
 * appear twice, instead of changing or losing a value.
 */
class SaveFormatTest {

    private static final PartitionName NAME = new PartitionName("p1");

    @TempDir
    private Path directory;

    /**
     * Writes a 2D save of one entity, with the given table of the keys as bytes and the pairs
     * of the entity as indexes in the table; the value of each pair is its index.
     */
    private Path save(byte[][] keys, int... keyIndexes) throws IOException {
        Path file = directory.resolve("0.bsave");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            SaveOutput output = new SaveOutput(channel);
            SaveFormat.writeHeader(output, 2, NAME, 0);
            output.writeInt(1);
            output.writeLong(1);
            output.writeDouble(0);
            output.writeDouble(0);
            output.writeInt(keys.length);
            for (byte[] key : keys) {
                output.writeInt(key.length);
                output.writeBytes(key);
            }
            output.writeInt(keyIndexes.length);
            for (int keyIndex : keyIndexes) {
                output.writeInt(keyIndex);
                output.writeLong(keyIndex);
            }
            output.finish();
        }
        return file;
    }

    private static byte[] utf8(String key) {
        return key.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void readGivesThePairsOfTheTable() throws IOException {
        Path file = save(new byte[][] {utf8("a"), utf8("b")}, 0, 1);
        assertEquals(Map.of("a", 0L, "b", 1L), PartitionSave2.read(file, NAME).metadata(0));
    }

    @Test
    void readRejectsAKeyTwiceInTheTable() throws IOException {
        Path file = save(new byte[][] {utf8("a"), utf8("a")}, 0, 1);
        assertThrows(IOException.class, () -> PartitionSave2.read(file, NAME));
    }

    @Test
    void readRejectsAKeyTwiceForAnEntity() throws IOException {
        Path file = save(new byte[][] {utf8("a")}, 0, 0);
        assertThrows(IOException.class, () -> PartitionSave2.read(file, NAME));
    }

    @Test
    void readRejectsAKeyThatIsNotUtf8() throws IOException {
        // A lone lead byte, and an unpaired surrogate encoded as three bytes.
        for (byte[] key : new byte[][] {{(byte) 0xc3}, {(byte) 0xed, (byte) 0xa0, (byte) 0x80}}) {
            Path file = save(new byte[][] {key}, 0);
            assertThrows(CharacterCodingException.class, () -> PartitionSave2.read(file, NAME));
        }
    }

    @Test
    void writeRejectsAKeyThatUtf8CannotKeep() throws IOException {
        try (FileChannel channel = FileChannel.open(directory.resolve("bad.bsave"), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            SaveOutput output = new SaveOutput(channel);
            String unpaired = String.valueOf((char) 0xd800);
            assertThrows(CharacterCodingException.class,
                    () -> SaveFormat.writeMetadata(output, 1, i -> Map.of(unpaired, 1L)));
        }
    }
}
