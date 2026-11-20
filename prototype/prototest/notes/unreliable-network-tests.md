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
                      perde o trattiene             registro delle sessioni
                      singoli messaggi              e NodeServer
```

Le regole della rete sono esplicite: nessun guasto e nessun ritardo casuale,
quindi ogni test esegue sempre lo stesso scenario. I casi di rete che
esauriscono il timeout dei retry usano un timeout zero, così il primo messaggio
perso è già un errore finale. Il test della scadenza del risultato sul server
fa avanzare l'orologio del registro mentre la prima risposta è trattenuta.

## Stato del proxy

I test controllano lo stato del proxy dopo ogni scenario. Il proxy diventa
usabile dopo `connect()`. Un errore irrecuperabile blocca le operazioni
fino al successo di `recover()`. `disconnect()` blocca subito le operazioni
e, quando il server conferma la revoca, permette un nuovo `connect()`.

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

Il diagramma mostra le transizioni dopo il primo collegamento. `recover()`
revoca la vecchia sessione sul server e ne apre un'altra. Gli effetti già
applicati restano sul nodo.

## Retry: `RetryTest`

Il proxy ripete una chiamata finché il timeout dei retry lo consente, sempre
con lo stesso ID di richiesta. Il server riconosce l'ID e restituisce il
risultato salvato senza applicare di nuovo l'operazione.

| Test | Rete | Verifica |
|---|---|---|
| `retriesOnRequestMissing` | prima richiesta persa | la partizione viene creata; i due tentativi hanno lo stesso ID |
| `aRetryReturnsTheOriginalResultAfterThePartitionIsRemoved` | prima risposta trattenuta e poi persa, dopo la rimozione della partizione | il retry usa lo stesso ID e riceve il risultato salvato; una nuova chiamata vede la rimozione |
| `aRetryOfAnExpiredRequestRequiresRecovery` | prima risposta trattenuta e poi persa, dopo la rimozione del risultato dal registro | il server rifiuta l'ID scaduto, la scrittura resta applicata una sola volta e il proxy richiede il recupero |
| `retriesWithoutDuplicatingEffects` | risposte perse della prima scrittura e del primo commit | il contenuto è scritto una sola volta |
| `aSuccessfulRetryLeavesTheProxyUsable` | richiesta persa, poi risposta persa | il terzo tentativo riesce e il proxy resta usabile |
| `retriesFromDifferentProxiesWithTheSameSequenceApplyEachWriteOnce` | la prima risposta alla scrittura di ciascun proxy va persa | due richieste con lo stesso numero di sequenza producono una sola scrittura ciascuna, su due partizioni distinte |
| `requiresNodeRecoveryAfterRetryTimeout` | prima richiesta persa, timeout zero | nessun retry: `"Unrecoverable timeout"` e proxy da recuperare |
| `returnsAServiceExceptionWithoutRetrying` | consegna normale | l'errore del servizio arriva al chiamante dopo un solo tentativo; il proxy resta usabile |

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

- `prototest.unreliablenetwork.SessionLifecycleTest` ritarda o perde le
  conferme delle transizioni e consegna richieste rimaste in transito dopo
  revoca, scadenza e ripristino.
- `prototest.server.SessionRegistryTest` controlla la scadenza di risultati
  e sessioni con un orologio controllabile e verifica la pulizia periodica.
- `prototest.proxy.RemoteCallExecutorTest`: con un tempo monotono controllato,
  l'esecutore ritenta prima della scadenza e si ferma quando il timeout è raggiunto.
- `prototest.proxy.NodeProxyRecoveryTest`: errori finali prodotti direttamente
  dall'endpoint, senza messaggi persi, e chiamate concorrenti al primo errore.
- `prototest.fakenetwork`: test della rete fittizia stessa.
