# prototest

Progetto sperimentale sulle invocazioni remote con servizio semplificato e rete
fittizia. Il progetto non implementa gRPC.

Il codice riusabile è in `prototest.remote`. I package `api` e `api.errors`
definiscono il protocollo di sessione, gli identificatori delle richieste e
gli errori di rete. `server` gestisce le sessioni, le scadenze e il riuso dei
risultati, `client` gestisce il ciclo di vita della sessione e i retry, e
`network` contiene il simulatore di consegna.

L'API delle partizioni, i suoi adattatori e il programma dimostrativo sono
in `prototest.example`. Per aggiungere un'altra API, si definisce
un'interfaccia remota con un `RequestId` per operazione. L'adattatore server
passa ogni richiesta a `ServerSessionEndpoint` e il proxy client invoca
l'interfaccia tramite `RemoteCallExecutor`. I proxy che usano la stessa
sessione condividono l'executor, che assegna un'unica sequenza di richieste.
Il [programma dimostrativo](src/main/java/prototest/example/App.java) mostra
come collegare i contratti di sessione e del nodo.

Test dalla radice del repository:

```sh
mvn -f prototype/pom.xml -pl prototest test
```

Appunti sulla sperimentazione:

- [Richieste da più proxy](notes/multiple-proxies.md)
- [Rete concorrente e stato di errore del proxy](notes/concurrent-network-and-recovery.md)
- [Test del proxy su rete inaffidabile](notes/unreliable-network-tests.md)
