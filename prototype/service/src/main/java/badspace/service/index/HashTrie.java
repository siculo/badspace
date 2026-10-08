package badspace.service.index;

import java.util.Arrays;
import java.util.function.Consumer;

/**
 * Hash map with versions: a hash array mapped trie (HAMT). The writer changes
 * the map, and at each commit it gets a {@link View} that never changes, even
 * when the writer goes on. Versions share the nodes that did not change.
 * <p>
 * Each node of the trie keeps the last commit at the time the node was made.
 * The writer changes in place only the nodes made after the last commit; it
 * copies the other nodes, with the path from the root, the first time it
 * changes them. So a node is copied at most once in each commit.
 * <p>
 * A node has up to 32 children, chosen by 5 bits of the hash of the key; a
 * bitmap says which children exist, so the array of a node has no empty
 * places. Keys with the same 32-bit hash go in a list at the bottom.
 * <p>
 * The map does not copy the keys and the values: keys must be immutable, and
 * the owner of a value must copy it before changing it, if an older version
 * can see it. It has a single writer and is not thread-safe; a view can be
 * read by many threads.
 */
final class HashTrie<K, V> {

    private static final int BITS = 5;
    private static final int MASK = (1 << BITS) - 1;

    private Node root = new BitmapNode(0, 0, new Object[0]);
    private int size;
    private long lastCommit;
    /** Set by put and remove: the change of the size. */
    private int sizeChange;

    /** Returns the value of the key, or null if the key is not in the map. */
    V get(K key) {
        return get(root, key);
    }

    /** Sets the value of the key. The value must not be null. */
    void put(K key, V value) {
        sizeChange = 0;
        root = put(root, new Entry<>(hash(key), key, value), 0);
        size += sizeChange;
    }

    /** Removes the key, if it is in the map. */
    void remove(K key) {
        sizeChange = 0;
        Object result = remove(root, hash(key), key, 0);
        root = switch (result) {
            case null -> new BitmapNode(lastCommit, 0, new Object[0]);
            case Node node -> node;
            // The root keeps its last entry: only the nodes below the root collapse.
            default -> throw new IllegalStateException("Root collapsed into an entry");
        };
        size += sizeChange;
    }

    int size() {
        return size;
    }

    /** Returns the map as it is now. The view is valid until the next write. */
    View<K, V> view() {
        return new View<>(root, size);
    }

    /**
     * Closes the current commit and returns its view, which never changes.
     * n must be greater than the last commit. After the commit, all the
     * nodes are copied before they change.
     */
    View<K, V> commit(long n) {
        View<K, V> view = view();
        lastCommit = n;
        return view;
    }

    /** A version of the map. It never changes, unless it comes from {@link #view()} and the writer goes on. */
    static final class View<K, V> {

        private final Node root;
        private final int size;

        private View(Node root, int size) {
            this.root = root;
            this.size = size;
        }

        V get(K key) {
            return HashTrie.get(root, key);
        }

        int size() {
            return size;
        }

        /** Calls the action on each value, in any order. */
        void forEachValue(Consumer<? super V> action) {
            HashTrie.forEachValue(root, action);
        }
    }

    /** Spreads the bits of the hash code, so that each group of 5 bits takes from all of them. */
    private static int hash(Object key) {
        int h = key.hashCode();
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    @SuppressWarnings("unchecked")
    private static <K, V> V get(Node root, K key) {
        int hash = hash(key);
        Node node = root;
        for (int shift = 0; ; shift += BITS) {
            Object child;
            if (node instanceof BitmapNode bitmapNode) {
                int bit = bitOf(hash, shift);
                if ((bitmapNode.bitmap & bit) == 0) {
                    return null;
                }
                child = bitmapNode.children[bitmapNode.indexOf(bit)];
            } else {
                for (Entry<?, ?> e : ((CollisionNode) node).entries) {
                    if (e.key.equals(key)) {
                        return (V) e.value;
                    }
                }
                return null;
            }
            if (child instanceof Entry<?, ?> e) {
                return e.hash == hash && e.key.equals(key) ? (V) e.value : null;
            }
            node = (Node) child;
        }
    }

    @SuppressWarnings("unchecked")
    private static <V> void forEachValue(Node node, Consumer<? super V> action) {
        Object[] children = node instanceof BitmapNode b ? b.children : ((CollisionNode) node).entries;
        for (Object child : children) {
            if (child instanceof Entry<?, ?> e) {
                action.accept((V) e.value);
            } else {
                forEachValue((Node) child, action);
            }
        }
    }

    /** Puts the entry in the subtree of the node, and returns the node to use in its place. */
    private Node put(Node node, Entry<K, V> entry, int shift) {
        if (node instanceof CollisionNode collision) {
            return putInCollision(collision, entry);
        }
        BitmapNode bitmapNode = (BitmapNode) node;
        int bit = bitOf(entry.hash, shift);
        int index = bitmapNode.indexOf(bit);
        if ((bitmapNode.bitmap & bit) == 0) {
            sizeChange = 1;
            return withChildren(bitmapNode, bitmapNode.bitmap | bit, inserted(bitmapNode.children, index, entry));
        }
        Object child = bitmapNode.children[index];
        Object newChild;
        if (child instanceof Entry<?, ?> e) {
            if (e.hash == entry.hash && e.key.equals(entry.key)) {
                newChild = entry;
            } else {
                sizeChange = 1;
                newChild = merge(e, entry, shift + BITS);
            }
        } else {
            newChild = put((Node) child, entry, shift + BITS);
            if (newChild == child) {
                return node;
            }
        }
        return withChild(bitmapNode, index, newChild);
    }

    private Node putInCollision(CollisionNode node, Entry<K, V> entry) {
        Entry<?, ?>[] entries = node.entries;
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].key.equals(entry.key)) {
                Entry<?, ?>[] copy = entries.clone();
                copy[i] = entry;
                return new CollisionNode(copy);
            }
        }
        sizeChange = 1;
        Entry<?, ?>[] copy = Arrays.copyOf(entries, entries.length + 1);
        copy[entries.length] = entry;
        return new CollisionNode(copy);
    }

    /** Returns a new node with two entries whose keys differ, starting at the given shift. */
    private Node merge(Entry<?, ?> a, Entry<?, ?> b, int shift) {
        if (shift >= Integer.SIZE) {
            return new CollisionNode(new Entry<?, ?>[] {a, b});
        }
        int bitA = bitOf(a.hash, shift);
        int bitB = bitOf(b.hash, shift);
        if (bitA == bitB) {
            return new BitmapNode(lastCommit, bitA, new Object[] {merge(a, b, shift + BITS)});
        }
        Object[] children = Integer.compareUnsigned(bitA, bitB) < 0 ? new Object[] {a, b} : new Object[] {b, a};
        return new BitmapNode(lastCommit, bitA | bitB, children);
    }

    /**
     * Removes the key from the subtree of the node. Returns the node to use in
     * its place, null if the subtree is now empty, or an entry if only one entry
     * is left below the root, so that the parent keeps it in place of the node.
     */
    private Object remove(Node node, int hash, K key, int shift) {
        if (node instanceof CollisionNode collision) {
            return removeFromCollision(collision, key);
        }
        BitmapNode bitmapNode = (BitmapNode) node;
        int bit = bitOf(hash, shift);
        if ((bitmapNode.bitmap & bit) == 0) {
            return node;
        }
        int index = bitmapNode.indexOf(bit);
        Object child = bitmapNode.children[index];
        Object newChild;
        if (child instanceof Entry<?, ?> e) {
            if (e.hash != hash || !e.key.equals(key)) {
                return node;
            }
            sizeChange = -1;
            newChild = null;
        } else {
            newChild = remove((Node) child, hash, key, shift + BITS);
            if (newChild == child) {
                return node;
            }
        }
        if (newChild == null) {
            int bitmap = bitmapNode.bitmap & ~bit;
            Object[] children = removed(bitmapNode.children, index);
            if (bitmap == 0) {
                return null;
            }
            if (shift > 0 && children.length == 1 && children[0] instanceof Entry) {
                return children[0];
            }
            return withChildren(bitmapNode, bitmap, children);
        }
        if (shift > 0 && bitmapNode.children.length == 1 && newChild instanceof Entry) {
            return newChild;
        }
        return withChild(bitmapNode, index, newChild);
    }

    private Object removeFromCollision(CollisionNode node, K key) {
        Entry<?, ?>[] entries = node.entries;
        for (int i = 0; i < entries.length; i++) {
            if (entries[i].key.equals(key)) {
                sizeChange = -1;
                if (entries.length == 2) {
                    return entries[1 - i];
                }
                Entry<?, ?>[] copy = new Entry<?, ?>[entries.length - 1];
                System.arraycopy(entries, 0, copy, 0, i);
                System.arraycopy(entries, i + 1, copy, i, entries.length - i - 1);
                return new CollisionNode(copy);
            }
        }
        return node;
    }

    /** Sets one child of the node: in place if the node was made after the last commit, else on a copy. */
    private BitmapNode withChild(BitmapNode node, int index, Object child) {
        if (node.commit == lastCommit) {
            node.children[index] = child;
            return node;
        }
        Object[] children = node.children.clone();
        children[index] = child;
        return new BitmapNode(lastCommit, node.bitmap, children);
    }

    /** Sets the bitmap and the children of the node: in place if the node was made after the last commit, else on a copy. */
    private BitmapNode withChildren(BitmapNode node, int bitmap, Object[] children) {
        if (node.commit == lastCommit) {
            node.bitmap = bitmap;
            node.children = children;
            return node;
        }
        return new BitmapNode(lastCommit, bitmap, children);
    }

    private static Object[] inserted(Object[] array, int index, Object value) {
        Object[] copy = new Object[array.length + 1];
        System.arraycopy(array, 0, copy, 0, index);
        copy[index] = value;
        System.arraycopy(array, index, copy, index + 1, array.length - index);
        return copy;
    }

    private static Object[] removed(Object[] array, int index) {
        Object[] copy = new Object[array.length - 1];
        System.arraycopy(array, 0, copy, 0, index);
        System.arraycopy(array, index + 1, copy, index, array.length - index - 1);
        return copy;
    }

    private static int bitOf(int hash, int shift) {
        return 1 << ((hash >>> shift) & MASK);
    }

    private record Entry<K, V>(int hash, K key, V value) {
    }

    private sealed interface Node permits BitmapNode, CollisionNode {
    }

    /**
     * A node with up to 32 children: entries or nodes. The array has a place
     * for each bit of the bitmap, in the order of the bits. The fields change
     * only while the node belongs to the current commit.
     */
    private static final class BitmapNode implements Node {

        final long commit;
        int bitmap;
        Object[] children;

        BitmapNode(long commit, int bitmap, Object[] children) {
            this.commit = commit;
            this.bitmap = bitmap;
            this.children = children;
        }

        /** Returns the place in the array of the child for the bit. */
        int indexOf(int bit) {
            return Integer.bitCount(bitmap & (bit - 1));
        }
    }

    /** The entries whose keys have the same 32-bit hash. It never changes: a write makes a new node. */
    private static final class CollisionNode implements Node {

        final Entry<?, ?>[] entries;

        CollisionNode(Entry<?, ?>[] entries) {
            this.entries = entries;
        }
    }
}
