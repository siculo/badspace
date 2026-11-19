# Test del proxy su rete inaffidabile

I test del package `prototest.unreliablenetwork` verificano come si comporta
`NodeProxy` quando la rete perde o trattiene richieste e risposte. Ogni test
mette la rete fittizia descritta in
[Rete concorrente e stato di errore del proxy](concurrent-network-and-recovery.md)
tra il proxy reale e l'adattatore reale del server:

```text
+-------------+     +----------------------+     +-----------------------------+
|  NodeProxy  |---->| FakeNetworkInterface |---->| ServerNodeNetworkInterface  |
+-------------+     +----------------------+     +-----------------------------+
                      perde o trattiene             registro delle richieste
                      singoli messaggi              e NodeServer
```

Le regole della rete sono esplicite: nessun guasto e nessun ritardo casuale,
quindi ogni test esegue sempre lo stesso scenario. I casi con un timeout dei
retry usano un timeout zero, così il primo messaggio perso è già un errore
finale e il risultato non dipende dalla velocità della macchina.

## Stato del proxy

I test controllano lo stato del proxy dopo ogni scenario. Il proxy resta
usabile finché un errore non rende incerto l'esito di una chiamata; da quel
momento rifiuta tutte le operazioni fino a `recover()`.

```text
    errore del servizio
    o retry riuscito
        +-------+
        |       |
        |       v
     +-------------+   retry esauriti, chiamante interrotto  +-----------------+
     |   usabile   |---------------------------------------->|  da recuperare  |
     +-------------+   o errore finale dall'endpoint         +-----------------+
            ^                                                         |
            |                        recover()                        |
            +---------------------------------------------------------+
```

Il diagramma riguarda solo lo stato locale del proxy: `recover()` non
ripristina il nodo remoto e non annulla gli effetti già applicati dal server.

## Retry: `RetryTest`

Il proxy ripete una chiamata finché il timeout dei retry lo consente, sempre
con lo stesso ID di richiesta. Il server riconosce l'ID e restituisce il
risultato salvato senza applicare di nuovo l'operazione.

| Test | Rete | Verifica |
|---|---|---|
| `retriesOnRequestMissing` | prima richiesta persa | la partizione viene creata; i due tentativi hanno lo stesso ID |
| `retriesOnResponseMissing` | prima risposta persa | il proxy riceve il risultato; i due tentativi hanno lo stesso ID |
| `retriesWithoutDuplicatingEffects` | risposte perse della prima scrittura e del primo commit | il contenuto è scritto una sola volta |
| `aSuccessfulRetryLeavesTheProxyUsable` | richiesta persa, poi risposta persa | il terzo tentativo riesce e il proxy resta usabile |
| `retriesOfSeveralProxiesKeepTheirIdsAndDoNotDuplicateWritesOrCommits` | due proxy, ognuno con la sua rete, perdono richieste e risposte | ogni retry mantiene l'ID della sua chiamata; nessuna scrittura o commit duplicato; ID diversi tra i proxy |
| `requiresNodeRecoveryAfterRetryTimeout` | prima richiesta persa, timeout zero | nessun retry: `"Unrecoverable timeout"` e proxy da recuperare |
| `returnsAServiceException` | consegna normale | l'errore del servizio arriva al chiamante senza retry; il proxy resta usabile |

## Recupero: `RecoveryTest`

| Test | Rete | Verifica |
|---|---|---|
| `aLostResponseRequiresRecoveryAndBlocksAllNodeOperations` | risposta persa, timeout zero | il server ha creato la partizione, ma il proxy rifiuta tutte le operazioni con il primo errore come causa |
| `interruptionPreservesTheFlagAndRequiresRecovery` | nessuna regola | un chiamante già interrotto rende il proxy da recuperare senza inviare la richiesta e conserva il flag di interruzione |
| `recoveryAllowsNewCallsWithoutReusingRequestIds` | risposta persa, timeout zero | dopo `recover()` le nuove chiamate hanno ID nuovi e raggiungono il server; un secondo `recover()` non cambia nulla |
| `aHeldResponseCanBeDeliveredAfterProxyRecovery` | risposta trattenuta, poi consegnata | la risposta tardiva non riprende la vecchia chiamata e non cambia lo stato |
| `aHeldResponseCanBeLostAfterProxyRecovery` | risposta trattenuta, poi persa | la vecchia chiamata non ripete il tentativo dopo il recupero |

### Risposta tardiva dopo il recupero

Gli ultimi due test seguono lo stesso scenario e cambiano solo la sorte della
risposta trattenuta. Le distanze sul diagramma non sono in scala.

```text
                   1             2              3             4              5
tempo            --*-------------*--------------*-------------*--------------*----->
                   :             :              :             :              :
chiamata "old"     +-------------x              :             :              :
risposta "old"     +---------------------------------------------------------*
chiamata "fail"    :       +-----x              :             :              :
chiamata "new"     :             :              :       +-----*              :
stato del proxy    usabile        da recuperare  usabile
```

1. Il server crea la partizione `"old"`; la rete trattiene la risposta.
2. La richiesta di `"fail"` è persa e il timeout è zero: il proxy diventa da
   recuperare. La chiamata `"old"`, ancora in attesa, fallisce con
   `ConnectionUnavailableException`, che ha come causa l'errore di `"fail"`.
3. `recover()` apre una nuova sessione.
4. La chiamata `"new"` riesce.
5. La rete consegna o perde la risposta di `"old"`. La vecchia chiamata non fa
   un secondo tentativo e il proxy resta usabile.

## Test collegati

- `prototest.proxy.NodeProxyRecoveryTest`: errori finali prodotti direttamente
  dall'endpoint, senza messaggi persi, e chiamate concorrenti al primo errore.
- `prototest.fakenetwork`: test della rete fittizia stessa.
