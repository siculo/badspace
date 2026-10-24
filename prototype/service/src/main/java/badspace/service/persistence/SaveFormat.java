package badspace.service.persistence;

import badspace.common.partition.PartitionName;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.IntFunction;

/**
 * The binary format of a save, the same for 2D and 3D. A save has, in this order:
 * <ol>
 *   <li>a header: {@link #MAGIC}, {@link #VERSION}, the number of dimensions, the name of
 *       the partition (its length in bytes and its UTF-8 bytes), the tick and the number of
 *       entities;</li>
 *   <li>the IDs of the entities, then their coordinates, the same number per entity as the
 *       dimensions;</li>
 *   <li>the application metadata: the table of the keys in string order, each key as its
 *       length in bytes and its UTF-8 bytes; then, for each entity, the number of its pairs
 *       and the pairs (index of the key in the table, value) in key order;</li>
 *   <li>a CRC32 of all the bytes before it.</li>
 * </ol>
 * The numbers are little-endian. The fixed orders make two saves of the same snapshot equal,
 * byte by byte. The strings are encoded and decoded in strict UTF-8: a string that UTF-8
 * cannot keep exactly, or bytes that are not valid UTF-8, make the write or the read fail
 * instead of changing a character. A key appears once in the table and once for each entity.
 */
final class SaveFormat {

    /** Identifies a file of saves: "BSPS" in ASCII, read as a little-endian int. */
    static final int MAGIC = 0x53505342;
    /** The version of the format; a reader rejects the others. */
    static final int VERSION = 1;

    private SaveFormat() {
    }

    /**
     * Writes the application metadata of count entities: the table of the keys and the pairs
     * of each entity, in the fixed orders of the format.
     */
    static void writeMetadata(SaveOutput output, int count, IntFunction<Map<String, Long>> metadata)
            throws IOException {
        TreeSet<String> keys = new TreeSet<>();
        for (int i = 0; i < count; i++) {
            keys.addAll(metadata.apply(i).keySet());
        }
        Map<String, Integer> keyIndexes = new HashMap<>();
        output.writeInt(keys.size());
        for (String key : keys) {
            keyIndexes.put(key, keyIndexes.size());
            byte[] bytes = encode(key);
            output.writeInt(bytes.length);
            output.writeBytes(bytes);
        }
        for (int i = 0; i < count; i++) {
            Map<String, Long> pairs = new TreeMap<>(metadata.apply(i));
            output.writeInt(pairs.size());
            for (Map.Entry<String, Long> pair : pairs.entrySet()) {
                output.writeInt(keyIndexes.get(pair.getKey()));
                output.writeLong(pair.getValue());
            }
        }
    }

    /**
     * Writes the header of a save, without the number of entities, which follows it.
     */
    static void writeHeader(SaveOutput output, int dimensions, PartitionName name, long tick) throws IOException {
        output.writeInt(MAGIC);
        output.writeInt(VERSION);
        output.writeInt(dimensions);
        byte[] nameBytes = encode(name.value());
        output.writeInt(nameBytes.length);
        output.writeBytes(nameBytes);
        output.writeLong(tick);
    }

    /**
     * Reads the header of a save, written by {@link #writeHeader}, and checks it: the format,
     * the version, the dimensions and the name of the partition must be the expected ones.
     * Returns the tick; the number of entities follows in the input.
     */
    static long readHeader(SaveInput input, int dimensions, PartitionName name) throws IOException {
        int magic = input.readInt();
        if (magic != MAGIC) {
            throw new IOException("Not a save: format " + Integer.toHexString(magic));
        }
        int version = input.readInt();
        if (version != VERSION) {
            throw new IOException("Unknown save version: " + version);
        }
        int savedDimensions = input.readInt();
        if (savedDimensions != dimensions) {
            throw new IOException("Save with " + savedDimensions + " dimensions, expected " + dimensions);
        }
        String savedName = decode(input.readBytes(readCount(input, 1)));
        if (!savedName.equals(name.value())) {
            throw new IOException("Save of partition \"" + savedName + "\", expected \"" + name.value() + "\"");
        }
        return input.readLong();
    }

    /**
     * Reads a count of the format, of items that take at least the given number of bytes each.
     * The count must not be negative and the items must fit in the file, so a corrupted count
     * fails before the reader allocates memory for it.
     */
    static int readCount(SaveInput input, int itemBytes) throws IOException {
        int count = input.readInt();
        if (count < 0 || (long) count * itemBytes > input.fileSize()) {
            throw new IOException("Corrupted save: count " + count + " does not fit in the file");
        }
        return count;
    }

    /**
     * Reads the application metadata of count entities, written by {@link #writeMetadata}.
     * An entity without metadata has an empty map; the maps are immutable.
     */
    static List<Map<String, Long>> readMetadata(SaveInput input, int count) throws IOException {
        int keyCount = readCount(input, Integer.BYTES);
        String[] keys = new String[keyCount];
        Set<String> distinctKeys = new HashSet<>();
        for (int k = 0; k < keyCount; k++) {
            keys[k] = decode(input.readBytes(readCount(input, 1)));
            if (!distinctKeys.add(keys[k])) {
                throw new IOException("Corrupted save: key twice in the table: " + keys[k]);
            }
        }
        List<Map<String, Long>> metadata = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int pairCount = readCount(input, Integer.BYTES + Long.BYTES);
            Map<String, Long> pairs = new HashMap<>();
            for (int p = 0; p < pairCount; p++) {
                int keyIndex = input.readInt();
                if (keyIndex < 0 || keyIndex >= keyCount) {
                    throw new IOException("Key index out of the table in save: " + keyIndex);
                }
                if (pairs.put(keys[keyIndex], input.readLong()) != null) {
                    throw new IOException("Corrupted save: key twice for an entity: " + keys[keyIndex]);
                }
            }
            metadata.add(Map.copyOf(pairs));
        }
        return metadata;
    }

    /**
     * Encodes a string in UTF-8. Fails with CharacterCodingException if the string has an
     * unpaired surrogate, which UTF-8 cannot keep.
     */
    private static byte[] encode(String value) throws CharacterCodingException {
        ByteBuffer buffer = StandardCharsets.UTF_8.newEncoder().encode(CharBuffer.wrap(value));
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytes;
    }

    /** Decodes UTF-8 bytes. Fails with CharacterCodingException if they are not valid UTF-8. */
    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
}
