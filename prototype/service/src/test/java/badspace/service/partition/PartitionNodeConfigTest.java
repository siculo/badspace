package badspace.service.partition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import badspace.common.partition.WriteMode;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PartitionNodeConfigTest {

    @Test
    void defaultConfigurationHasNoSaveDirectoryAndTheDirectMode() {
        PartitionNodeConfig config = PartitionNodeConfig.of();
        assertNull(config.saveDirectory());
        assertEquals(WriteMode.DIRECT, config.writeMode());
    }

    @Test
    void withMethodsChangeOnlyTheirComponent() {
        Path directory = Path.of("saves");

        // withSaveDirectory keeps the write mode, also when it is not the default.
        PartitionNodeConfig buffered = PartitionNodeConfig.of().withWriteMode(WriteMode.BUFFERED);
        PartitionNodeConfig withDirectory = buffered.withSaveDirectory(directory);
        assertEquals(directory, withDirectory.saveDirectory());
        assertEquals(WriteMode.BUFFERED, withDirectory.writeMode());

        // withWriteMode keeps the save directory, also when it is not none.
        PartitionNodeConfig saving = PartitionNodeConfig.of().withSaveDirectory(directory);
        PartitionNodeConfig withMode = saving.withWriteMode(WriteMode.BUFFERED);
        assertEquals(directory, withMode.saveDirectory());
        assertEquals(WriteMode.BUFFERED, withMode.writeMode());
    }

    @Test
    void nullWriteModeIsRejected() {
        assertThrows(NullPointerException.class, () -> PartitionNodeConfig.of().withWriteMode(null));
    }
}
