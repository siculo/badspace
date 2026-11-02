package badspace.service.partition.metadata;

import badspace.common.partition.ApplicationMetadata;
import badspace.common.partition.MetadataWrite;
import badspace.service.transaction.TransactionalComponentContract;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongFunction;

/** Checks the application metadata as a transactional component. */
class MutableMetadataTest extends TransactionalComponentContract<MutableMetadata, MetadataSnapshot> {

    private static final long MAX_ID = 30;
    private static final List<String> KEYS = List.of("a", "b", "c");
    private static final long MAX_VALUE = 5;

    @Override
    protected MutableMetadata newComponent() {
        return new MutableMetadata();
    }

    @Override
    protected MetadataSnapshot emptySnapshot() {
        return MetadataSnapshot.empty();
    }

    @Override
    protected void write(MutableMetadata metadata, Random random, long commit) {
        long entityId = random.nextLong(MAX_ID);
        String key = KEYS.get(random.nextInt(KEYS.size()));
        switch (random.nextInt(3)) {
            case 0 -> metadata.write(new MetadataWrite.Delete(entityId, key));
            case 1 -> metadata.removeEntity(entityId);
            default -> metadata.write(new MetadataWrite.Put(entityId, key, random.nextLong(MAX_VALUE)));
        }
    }

    @Override
    protected Object workingContent(MutableMetadata metadata) {
        return contentOf(metadata::metadataOf);
    }

    @Override
    protected Object snapshotContent(MetadataSnapshot snapshot) {
        return contentOf(snapshot::metadataOf);
    }

    private static List<ApplicationMetadata> contentOf(LongFunction<ApplicationMetadata> metadataOf) {
        List<ApplicationMetadata> content = new ArrayList<>();
        for (long entityId = 0; entityId < MAX_ID; entityId++) {
            content.add(metadataOf.apply(entityId));
        }
        return content;
    }
}
