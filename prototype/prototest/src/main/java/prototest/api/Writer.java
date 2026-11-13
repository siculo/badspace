package prototest.api;

public interface Writer {
    void write(String content);

    void commit();
}
