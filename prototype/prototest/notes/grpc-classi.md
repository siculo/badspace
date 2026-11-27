# Classi gRPC di `prototest`

Il package `badspace.remote.grpc` contiene gli adattatori di sessione e la gestione degli errori comuni. Il package `prototest.example.grpc` contiene gli adattatori delle partizioni e la traduzione degli errori specifici dell’esempio. I [diagrammi di sequenza](grpc-sequenze.md) mostrano i messaggi che attraversano il canale.

## Interfacce e adattatori

Ogni client implementa la propria interfaccia. Sul server, ciascun servizio riceve nel costruttore un oggetto che implementa la stessa interfaccia e gli delega le operazioni.

```text
                implementa                       delega
+----------------+       +----------------------+       +----------------------+
| GrpcNodeClient | ----> | NodeNetworkInterface | <---- | GrpcPartitionService |
+----------------+       +----------------------+       +----------------------+

                   implementa                          delega
+-------------------+       +-------------------------+       +--------------------+
| GrpcSessionClient | ----> | SessionNetworkInterface | <---- | GrpcSessionService |
+-------------------+       +-------------------------+       +--------------------+
```

`GrpcNodeClient` usa `PartitionsBlockingStub` e `GrpcSessionClient` usa `SessionsBlockingStub`. Nella demo condividono lo stesso `Channel`. Il proxy usa il primo per le operazioni sulle partizioni e passa dal suo `RemoteCallExecutor` per aprire, recuperare o chiudere la sessione con il secondo. [App.java](../src/main/java/prototest/example/App.java#L43)

In `GrpcNodeTest` i delegati sono `ServerNodeNetworkInterface` per `GrpcPartitionService` e `ServerSessionEndpoint` per `GrpcSessionService`. [GrpcPartitionService.java](../src/main/java/prototest/example/grpc/GrpcPartitionService.java#L19) · [GrpcSessionService.java](../../remote/src/main/java/badspace/remote/grpc/GrpcSessionService.java#L13) · [GrpcNodeTest.java](../src/test/java/prototest/example/grpc/GrpcNodeTest.java#L55)

## Classi base dei servizi

```text
+----------------------+                 +-----------------------------------+
| GrpcPartitionService | --- estende --> | PartitionsGrpc.PartitionsImplBase |
+----------------------+                 +-----------------------------------+

+--------------------+                 +-------------------------------+
| GrpcSessionService | --- estende --> | SessionsGrpc.SessionsImplBase |
+--------------------+                 +-------------------------------+
```

Le classi base permettono al server gRPC di inoltrare le RPC ai metodi ridefiniti nei servizi. Maven avvia la generazione con `protobuf-maven-plugin`. Il goal `compile` esegue `protoc` per generare i messaggi Java, come `SessionToken`. Il goal `compile-custom` usa `protoc-gen-grpc-java` per generare le classi base e gli stub client a partire dai `service` definiti nei `.proto`. [remote/pom.xml](../../remote/pom.xml#L50) · [prototest/pom.xml](../pom.xml#L67)

`remote/session.proto` definisce `Sessions`, `SessionToken` e `RequestIdentity`. `example/node.proto` definisce `Partitions` e importa il contratto di sessione per usare `RequestIdentity` nelle richieste. [session.proto](../../remote/src/main/proto/remote/session.proto#L10) · [node.proto](../src/main/proto/example/node.proto#L6)

## Dipendenze da `GrpcCalls`

```text
+----------------------+                 +-----------+
| GrpcSessionClient    | --------------> |           |
| GrpcSessionService   |                 |           |
+----------------------+                 |           |
                                         | GrpcCalls |
+----------------------+                 |           |
| GrpcNodeClient       | --------------> |           |
| GrpcPartitionService |                 |           |
+----------------------+                 +-----------+
```

`call` traduce gli errori gRPC in eccezioni Java. `respond` consegna a gRPC un risultato, un messaggio `Empty` per le operazioni Java `void` oppure uno stato di errore. Gli adattatori di sessione usano la mappatura comune. Gli adattatori delle partizioni usano l’istanza configurata in `GrpcPartitionErrors`, che aggiunge la traduzione tra `NOT_FOUND` e `PartitionNotFoundException`. [GrpcCalls.java](../../remote/src/main/java/badspace/remote/grpc/GrpcCalls.java#L16) · [GrpcPartitionErrors.java](../src/main/java/prototest/example/grpc/GrpcPartitionErrors.java#L8)
