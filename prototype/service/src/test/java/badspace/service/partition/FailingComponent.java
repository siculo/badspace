package badspace.service.partition;

import badspace.service.transaction.TransactionalComponent;

/**
 * A component of the commits around a real structure of the partition, the slots or the
 * metadata, for the tests. The preparation throws the prepare failure, the abandon throws the
 * abandon failure and the restore throws the restore failure, before the real structure does
 * anything, if there is one; otherwise each method calls the real structure with the same
 * snapshot. The writes and the reads of the storage do not go through it.
 *
 * @param <S> the type of the snapshots of the structure
 */
final class FailingComponent<S> implements TransactionalComponent<S> {

    private final TransactionalComponent<S> component;
    // The failures that the methods throw, if not null: an exception or an Error. Set by the
    // test thread before the operation starts, read by the writer thread.
    volatile Throwable prepareFailure;
    volatile Throwable abandonFailure;
    volatile Throwable restoreFailure;

    FailingComponent(TransactionalComponent<S> component) {
        this.component = component;
    }

    @Override
    public S prepareCommit(long commitSequence) {
        throwIfSet(prepareFailure);
        return component.prepareCommit(commitSequence);
    }

    @Override
    public void acceptCommit(S prepared) {
        component.acceptCommit(prepared);
    }

    @Override
    public void abandonCommit(S prepared) {
        throwIfSet(abandonFailure);
        component.abandonCommit(prepared);
    }

    @Override
    public void restore(S confirmed) {
        throwIfSet(restoreFailure);
        component.restore(confirmed);
    }

    private static void throwIfSet(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }
}
