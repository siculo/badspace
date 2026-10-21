package badspace.service.persistence;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.zip.CRC32;

/**
 * Writes the numbers of a save to a file channel, little-endian, through a buffer, and keeps
 * the CRC32 of the bytes written. {@link #finish} writes the CRC32 at the end. Not
 * thread-safe: one save uses it from one thread.
 */
final class SaveOutput {

    private static final int BUFFER_SIZE = 64 * 1024;

    private final FileChannel channel;
    private final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    private final CRC32 checksum = new CRC32();

    SaveOutput(FileChannel channel) {
        this.channel = channel;
    }

    void writeInt(int value) throws IOException {
        ensure(Integer.BYTES);
        buffer.putInt(value);
    }

    void writeLong(long value) throws IOException {
        ensure(Long.BYTES);
        buffer.putLong(value);
    }

    void writeDouble(double value) throws IOException {
        ensure(Double.BYTES);
        buffer.putDouble(value);
    }

    void writeBytes(byte[] bytes) throws IOException {
        int offset = 0;
        while (offset < bytes.length) {
            ensure(1);
            int length = Math.min(buffer.remaining(), bytes.length - offset);
            buffer.put(bytes, offset, length);
            offset += length;
        }
    }

    /** Writes the CRC32 of all the bytes before it, and everything still in the buffer. */
    void finish() throws IOException {
        flush();
        buffer.putInt((int) checksum.getValue());
        buffer.flip();
        writeBuffer();
    }

    /** Makes room in the buffer for the given number of bytes. */
    private void ensure(int bytes) throws IOException {
        if (buffer.remaining() < bytes) {
            flush();
        }
    }

    /** Writes the buffer to the channel and adds its bytes to the CRC32. */
    private void flush() throws IOException {
        buffer.flip();
        checksum.update(buffer.duplicate());
        writeBuffer();
    }

    /** Writes the flipped buffer to the channel and clears it. */
    private void writeBuffer() throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
        buffer.clear();
    }
}
