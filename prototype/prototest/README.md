# prototest

Progetto sperimentale sulle invocazioni remote con servizio semplificato e rete
fittizia. Il progetto non implementa gRPC.

Test dalla radice del repository:

```sh
mvn -f prototype/pom.xml -pl prototest test
```

Appunti sulla sperimentazione:

- [Richieste da più proxy](notes/multiple-proxies.md)
- [Rete concorrente e stato di errore del proxy](notes/concurrent-network-and-recovery.md)
