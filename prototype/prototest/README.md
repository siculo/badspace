# prototest

Progetto sperimentale sulle invocazioni remote. Il servizio d'esempio usa
gRPC su una connessione locale. I test con rete fittizia controllano la
consegna di richieste e risposte in modo deterministico.

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
Il [programma dimostrativo](src/main/java/prototest/example/App.java) esegue
client e server nello stesso processo, con una connessione gRPC in chiaro
su `127.0.0.1` e una porta assegnata dal sistema.

## Trasporto gRPC

[Il servizio di sessione](src/main/proto/session.proto) apre, rinnova e
revoca i token. [Il servizio delle partizioni](src/main/proto/node.proto)
riceve il token e la sequenza in ogni richiesta. Entrambi passano dallo
stesso `ServerSessionEndpoint`, che conserva i risultati delle richieste
riuscite. Il client riusa lo stesso ID quando ripete un'operazione sul nodo.
Ogni RPC ha una propria scadenza. `RemoteCallExecutor` decide se ritentare
una chiamata al nodo e gestisce lo stato di recupero del proxy.

| Stato gRPC | Esito nel client |
|---|---|
| `NOT_FOUND` | La partizione manca. Il proxy resta utilizzabile. |
| `INVALID_ARGUMENT` | La richiesta contiene un argomento non valido. |
| `FAILED_PRECONDITION` | Il server ha rifiutato la sessione. |
| `ABORTED` | Il server ha segnalato un errore finale, per esempio un ID di richiesta scaduto. |
| `UNAVAILABLE`, `DEADLINE_EXCEEDED`, `CANCELLED` | La chiamata al nodo può essere ritentata con lo stesso ID. |
| Altri codici di errore | Il proxy richiede il recupero dopo una chiamata al nodo. |

Per eseguire test e demo dalla radice del repository:

```sh
mvn -f prototype/pom.xml -pl prototest test
mvn -f prototype/prototest/pom.xml compile exec:java
```

Maven usa il trasporto TCP Java NIO di Netty per test e demo. Per usare
la stessa configurazione da un IDE, aggiungere l'opzione JVM
`-Dio.grpc.netty.shaded.io.netty.transport.noNative=true`.

Appunti sulla sperimentazione:

- [Richieste da più proxy](notes/multiple-proxies.md)
- [Rete concorrente e stato di errore del proxy](notes/concurrent-network-and-recovery.md)
- [Test del proxy su rete inaffidabile](notes/unreliable-network-tests.md)
