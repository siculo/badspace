# prototest

Progetto sperimentale sulle invocazioni remote. Il servizio d'esempio usa
gRPC su una connessione locale. I test con rete fittizia controllano la
consegna di richieste e risposte in modo deterministico.

Il codice riusabile è nel modulo [`remote`](../remote/pom.xml), nel package
`badspace.remote`. I package `api` e `api.errors`
definiscono il protocollo di sessione, gli identificatori delle richieste e
gli errori di rete. `server` gestisce le sessioni, le scadenze e il riuso dei
risultati, `client` gestisce il ciclo di vita della sessione e i retry, e
`network` contiene il simulatore di consegna. `grpc` contiene gli adattatori
di sessione e la traduzione degli errori comuni per il trasporto gRPC.

L'API delle partizioni, i suoi adattatori e il programma dimostrativo sono
in `prototest.example`. Per aggiungere un'altra API, si definisce
un'interfaccia remota con un `RequestId` per operazione. L'adattatore server
passa ogni richiesta a `ServerSessionEndpoint` e il proxy client invoca
l'interfaccia tramite `RemoteCallExecutor`. I proxy che usano la stessa
sessione condividono l'executor, che assegna un'unica sequenza di richieste.
Il [programma dimostrativo](src/main/java/prototest/example/App.java) esegue
client e server nello stesso processo, con una connessione gRPC in chiaro
su `127.0.0.1` e una porta assegnata dal sistema.

## Trasporto gRPC

[Il contratto di sessione](../remote/src/main/proto/remote/session.proto) definisce le
operazioni sui token e il messaggio condiviso con token e sequenza.
[Il contratto delle partizioni](src/main/proto/example/node.proto) importa
quel messaggio per identificare ciascuna richiesta. I due servizi usano lo
stesso `ServerSessionEndpoint`, che conserva i valori restituiti e le
eccezioni applicative per riproporre lo stesso esito in caso di retry.
Nella demo, `GrpcSessionClient` e `GrpcNodeClient` condividono il
canale. Ogni RPC ha una propria scadenza. `RemoteCallExecutor` gestisce lo
stato di recupero del proxy e riusa lo stesso ID quando ritenta una chiamata
al nodo.

| Stato gRPC | Esito nel client |
|---|---|
| `NOT_FOUND` nelle RPC delle partizioni | La partizione manca. Il proxy resta utilizzabile. |
| `INVALID_ARGUMENT` | La richiesta contiene un argomento non valido. |
| `FAILED_PRECONDITION` | Il server ha rifiutato la sessione. |
| `ABORTED` | Il server ha segnalato un errore finale, per esempio un ID di richiesta scaduto. |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `CANCELLED` | La chiamata al nodo può essere ritentata con lo stesso ID. |
| Altri codici di errore | Il proxy richiede il recupero dopo una chiamata al nodo. |

I comandi seguenti si eseguono dalla radice del repository.

```sh
mvn -f prototype/pom.xml -pl prototest -am test
mvn -f prototype/pom.xml -pl remote -am install
mvn -f prototype/prototest/pom.xml compile exec:java
```

Il primo comando esegue entrambe le suite. Il secondo installa il modulo
`remote` nel repository Maven locale per avviare la demo dal solo `prototest`.

Maven usa il trasporto TCP Java NIO di Netty per test e demo. Per usare
la stessa configurazione da un IDE, aggiungere l'opzione JVM
`-Dio.grpc.netty.shaded.io.netty.transport.noNative=true`.

Appunti sulla sperimentazione:

- [Classi gRPC](notes/grpc-classi.md) e [sequenze gRPC](notes/grpc-sequenze.md)
- [Richieste da più proxy](notes/multiple-proxies.md)
- [Rete concorrente e stato di errore del proxy](notes/concurrent-network-and-recovery.md)
- [Test del proxy su rete inaffidabile](notes/unreliable-network-tests.md)
