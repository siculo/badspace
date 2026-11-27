# Sequenze gRPC di `prototest`

Il tempo scorre dall’alto verso il basso. La colonna `gRPC` rappresenta lo stub client, il canale e l’inoltro al servizio registrato sul server. Nei riquadri, `Client` indica `GrpcNodeClient`, `Sessioni` indica `GrpcSessionService` e `Partizioni` indica `GrpcPartitionService`. Il [diagramma delle classi](grpc-classi.md) mostra le relazioni Java tra questi oggetti.

## Apertura di una sessione

`NodeProxy.connect()` delega a `RemoteCallExecutor`, che chiama `GrpcNodeClient.connect()` tramite `SessionNetworkInterface`. Il client usa `GrpcCalls.call` per inviare la RPC `Connect` con un messaggio `Empty`. Sul server, `GrpcSessionService` usa `GrpcCalls.respond` per invocare `ServerSessionEndpoint.connect()` e consegnare la risposta a gRPC.

```text
+--------+       +------+        +----------+        +-----------------------+
| Client |       | gRPC |        | Sessioni |        | ServerSessionEndpoint |
+---+----+       +--+---+        +----+-----+        +-----------+-----------+
    :               :                 :                          :
    :    Connect    :     Connect     :                          :
    +-------------->+---------------->+                          :
    :               :                 : "connect()"              :
    :               :                 +------------------------->+
    :               :                 :               token UUID :
    :               :                 +<-------------------------+
    :  SessionToken :                 :                          :
    +<--------------+<----------------+                          :
```

`GrpcSessionService` inserisce il token nel messaggio `SessionToken` come stringa. `GrpcNodeClient` lo riconverte in `UUID` e lo restituisce a `RemoteCallExecutor`, che lo conserva per le chiamate successive. [GrpcNodeClient.java](../src/main/java/prototest/example/grpc/GrpcNodeClient.java#L42) · [GrpcSessionService.java](../src/main/java/prototest/example/grpc/GrpcSessionService.java#L26) · [RemoteCallExecutor.java](../src/main/java/prototest/remote/client/RemoteCallExecutor.java#L238)

## Lettura di uno snapshot

`NodeProxy.readPartitionSnapshot("p")` passa l’operazione a `RemoteCallExecutor`. L’esecutore crea il `RequestId` con il token di sessione e il numero di sequenza, quindi chiama `GrpcNodeClient` tramite `NodeNetworkInterface`. Il diagramma mostra il caso in cui la lettura riesce al primo tentativo.

```text
+--------+       +------+        +------------+      +----------------------------+
| Client |       | gRPC |        | Partizioni |      | ServerNodeNetworkInterface |
+---+----+       +--+---+        +-----+------+      +-------------+--------------+
    :               :                  :                           :
    :               : PartitionRequest :                           :
    +-------------->+----------------->+                           :
    :               :                  : readPartitionSnapshot     :
    :               :                  +-------------------------->+
    :               :                  :          stringa snapshot :
    :               :                  +<--------------------------+
    :               : SnapshotResponse :                           :
    +<--------------+<-----------------+                           :
```

`GrpcPartitionService` ricostruisce il `RequestId` dal messaggio e passa l’identificativo e il nome della partizione a `ServerNodeNetworkInterface` dentro `GrpcCalls.respond`. L’adattatore server passa da `ServerSessionEndpoint.executeOnceAndReturn` prima di eseguire la lettura su `NodeServer`. La stringa torna in `SnapshotResponse`, da cui `GrpcNodeClient` la estrae. [NodeProxy.java](../src/main/java/prototest/example/proxy/NodeProxy.java#L116) · [GrpcPartitionService.java](../src/main/java/prototest/example/grpc/GrpcPartitionService.java#L58) · [ServerNodeNetworkInterface.java](../src/main/java/prototest/example/server/ServerNodeNetworkInterface.java#L66) · [GrpcNodeClient.java](../src/main/java/prototest/example/grpc/GrpcNodeClient.java#L88)

## Lettura di una partizione assente

La richiesta segue lo stesso percorso. Sul ritorno, i due metodi di `GrpcCalls` traducono l’errore ai confini del trasporto.

```text
+----------------------+     +----------------------+     +----------------------+
| GrpcCalls sul client |     |         gRPC         |     | GrpcCalls sul server |
+----------+-----------+     +----------+-----------+     +----------+-----------+
           :                            :                            :
           :                            :       eccezione dal nodo   :
           :                            :                            *
           :                  NOT_FOUND :                  NOT_FOUND :
           +<---------------------------+<---------------------------+
           :                            :                            :
           *                            :                            :
```

Sul server, `respond` riceve una `PartitionNotFoundException` e invia lo stato `NOT_FOUND` a gRPC. Sul client, `call` riceve quello stato e rilancia una `PartitionNotFoundException`. Il test verifica che l’eccezione arrivi a chi usa il proxy e che la sessione resti utilizzabile. [GrpcCalls.java](../src/main/java/prototest/example/grpc/GrpcCalls.java#L18) · [GrpcNodeTest.java](../src/test/java/prototest/example/grpc/GrpcNodeTest.java#L90)
