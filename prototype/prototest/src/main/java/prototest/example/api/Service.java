package prototest.example.api;

public interface Service {
    void create(String name);

    void destroy(String name);

    boolean exist(String name);

    Writer writer(String name);

    Reader reader(String name);
}
