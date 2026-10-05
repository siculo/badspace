package badspace.service.index;

import java.util.Comparator;
import java.util.PriorityQueue;

/**
 * Keeps the {@code count} nearest entities offered so far, by distance and
 * then by ID. The indices use it to answer the k-nearest queries.
 */
final class NearestSlots {

    private static final Comparator<Candidate> NEAREST_FIRST =
            Comparator.comparingDouble(Candidate::distanceSquared).thenComparingLong(Candidate::id);

    private final int count;
    // The head of the queue is the farthest of the nearest entities found so far.
    private final PriorityQueue<Candidate> nearest = new PriorityQueue<>(NEAREST_FIRST.reversed());

    /** The count must be positive. */
    NearestSlots(int count) {
        this.count = count;
    }

    /** Offers an entity: it is kept if it is one of the nearest so far. */
    void offer(double distanceSquared, long id, int slot) {
        Candidate c = new Candidate(distanceSquared, id, slot);
        if (nearest.size() < count) {
            nearest.add(c);
        } else if (NEAREST_FIRST.compare(c, nearest.peek()) < 0) {
            nearest.poll();
            nearest.add(c);
        }
    }

    /**
     * Returns true if the entities found so far are the nearest ones, when
     * all the others are at a squared distance of at least {@code minDistanceSquared}.
     */
    boolean isComplete(double minDistanceSquared) {
        return nearest.size() == count
                && Double.compare(minDistanceSquared, nearest.peek().distanceSquared()) > 0;
    }

    /** Returns the slots of the nearest entities, ordered by distance and then by ID. */
    int[] slots() {
        return nearest.stream().sorted(NEAREST_FIRST).mapToInt(Candidate::slot).toArray();
    }

    /** An offered entity, with its squared distance from the point. */
    private record Candidate(double distanceSquared, long id, int slot) {
    }
}
