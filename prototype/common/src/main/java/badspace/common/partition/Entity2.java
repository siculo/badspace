package badspace.common.partition;

import badspace.common.geometry.Point2;

/** An entity of a 2D space: its ID and its position. */
public record Entity2(long id, Point2 position) {
}
