package badspace.service.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32;

/**
 * Reads the numbers of a save from a file channel, little-endian, through a buffer, and keeps
 * the CRC32 of the bytes read. {@link #finish} checks the CRC32 at the end of the file. A
 * file that ends too early fails with an {@link IOException}. Not thread-safe.
 */
final class SaveInput {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileChannel channel;
    private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32 checksum = new CRC32();
    // The bytes of the buffer before this position are already in the CRC32.
    private int checkedPosition;

    SaveInput(FileChannel channel) {
        this.channel = channel;
        buffer.flip();
    }

    /** Returns the size of the file in bytes. */
    long fileSize() throws IOException {
        return channel.size();
    }

    int readInt() throws IOException {
        fill(Integer.BYTES);
        return buffer.getInt();
    }

    long readLong() throws IOException {
        fill(Long.BYTES);
        return buffer.getLong();
    }

    double readDouble() throws IOException {
        fill(Double.BYTES);
        return buffer.getDouble();
    }

    byte[] readBytes(int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            fill(1);
            int count = Math.min(buffer.remaining(), length - offset);
            buffer.get(bytes, offset, count);
            offset += count;
        }
        return bytes;
    }

    /**
     * Reads the CRC32 at the end of the save and checks it against the bytes read before it;
     * then checks that nothing follows it.
     */
    void finish() throws IOException {
        addToChecksum();
        int expected = (int) checksum.getValue();
        int saved = readInt();
        if (saved != expected) {
            throw new IOException("Corrupted save: CRC32 " + Integer.toHexString(saved)
                    + ", expected " + Integer.toHexString(expected));
        }
        if (buffer.hasRemaining() || channel.read(ByteBuffer.allocate(1)) >= 0) {
            throw new IOException("Corrupted save: bytes after the CRC32");
        }
    }

    /** Makes the given number of bytes available in the buffer, reading from the channel. */
    private void fill(int bytes) throws IOException {
        if (buffer.remaining() >= bytes) {
            return;
        }
        addToChecksum();
        buffer.compact();
        checkedPosition = 0;
        while (buffer.position() < bytes) {
            if (channel.read(buffer) < 0) {
                throw new IOException("Corrupted save: the file ends too early");
            }
        }
        buffer.flip();
    }

    /** Adds to the CRC32 the bytes read from the buffer and not yet in it. */
    private void addToChecksum() {
        ByteBuffer read = buffer.duplicate();
        read.position(checkedPosition).limit(buffer.position());
        checksum.update(read);
        checkedPosition = buffer.position();
    }
}
