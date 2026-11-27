# Classi gRPC di `prototest`

Le quattro classi del package `prototest.example.grpc` collegano le interfacce Java del prototipo al trasporto gRPC. Le frecce qui sotto indicano relazioni tra tipi Java. I messaggi che attraversano il canale sono mostrati nei [diagrammi di sequenza](grpc-sequenze.md).

## Interfacce e adattatori

`GrpcNodeClient` implementa entrambe le interfacce. Ogni servizio riceve nel costruttore un oggetto che implementa l’interfaccia corrispondente e gli delega le operazioni.

```text
                implementa                       delega
+----------------+       +----------------------+       +----------------------+
| GrpcNodeClient | ----> | NodeNetworkInterface | <---- | GrpcPartitionService |
+----------------+       +----------------------+       +----------------------+

                implementa                          delega
+----------------+       +-------------------------+       +--------------------+
| GrpcNodeClient | ----> | SessionNetworkInterface | <---- | GrpcSessionService |
+----------------+       +-------------------------+       +--------------------+
```

`GrpcNodeClient` incapsula due stub bloccanti generati da gRPC, `PartitionsBlockingStub` e `SessionsBlockingStub`, che condividono il `Channel` ricevuto dal chiamante. Gli stub inviano le richieste e attendono la risposta o un errore. [GrpcNodeClient.java](../src/main/java/prototest/example/grpc/GrpcNodeClient.java#L20)

In `GrpcNodeTest` i delegati sono `ServerNodeNetworkInterface` per `GrpcPartitionService` e `ServerSessionEndpoint` per `GrpcSessionService`. [GrpcPartitionService.java](../src/main/java/prototest/example/grpc/GrpcPartitionService.java#L17) · [GrpcSessionService.java](../src/main/java/prototest/example/grpc/GrpcSessionService.java#L13) · [GrpcNodeTest.java](../src/test/java/prototest/example/grpc/GrpcNodeTest.java#L52)

## Classi base dei servizi

```text
+----------------------+                 +-----------------------------------+
| GrpcPartitionService | --- estende --> | PartitionsGrpc.PartitionsImplBase |
+----------------------+                 +-----------------------------------+

+--------------------+                 +-------------------------------+
| GrpcSessionService | --- estende --> | SessionsGrpc.SessionsImplBase |
+--------------------+                 +-------------------------------+
```

Le classi base permettono al server gRPC di inoltrare le RPC ai metodi ridefiniti nei servizi. Maven avvia la generazione con `protobuf-maven-plugin`. Il goal `compile` esegue `protoc` per generare i messaggi Java, come `SessionToken`. Il goal `compile-custom` usa `protoc-gen-grpc-java` per generare le classi base e gli stub client a partire dai `service` definiti nei `.proto`. [pom.xml](../pom.xml#L60) · [session.proto](../src/main/proto/session.proto#L10) · [node.proto](../src/main/proto/node.proto#L11)

## Dipendenze da `GrpcCalls`

```text
+----------------------+                                  +-----------+
| GrpcNodeClient       | ---------- "call(...)" --------> |           |
+----------------------+                                  |           |
                                                          |           |
+----------------------+                                  |           |
| GrpcPartitionService | ------- "respond(...)" --------> | GrpcCalls |
+----------------------+                                  |           |
                                                          |           |
+----------------------+                                  |           |
| GrpcSessionService   | ------- "respond(...)" --------> |           |
+----------------------+                                  +-----------+
```

Tutte e tre le dipendenze sono chiamate a metodi statici. `call` traduce gli errori gRPC in eccezioni Java del prototipo. `respond` consegna a gRPC un risultato, un messaggio `Empty` per le operazioni Java `void` oppure uno stato di errore. [GrpcCalls.java](../src/main/java/prototest/example/grpc/GrpcCalls.java#L15)
