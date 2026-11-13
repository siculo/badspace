# prototest

Esperimenti sulle invocazioni remote con servizio semplificato e rete
fittizia. Il progetto non implementa gRPC.

Test dalla radice del repository:

```sh
mvn -f prototype/pom.xml -pl prototest test
```

Risultati e limiti degli esperimenti:

- [Richieste da più proxy](experiments/multiple-proxies.md)
- [Rete concorrente e stato di errore del proxy](experiments/concurrent-network-and-recovery.md)
