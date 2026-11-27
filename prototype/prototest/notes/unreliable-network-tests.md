# Test del proxy su rete inaffidabile

I test del package `prototest.unreliablenetwork` verificano come si comporta
`NodeProxy` quando la rete perde o trattiene richieste e risposte. Ogni test
mette la rete fittizia descritta in
[Rete concorrente e stato di errore del proxy](concurrent-network-and-recovery.md)
tra il proxy e gli endpoint del server. La fixture `NodeTestEndpoint` inoltra
entrambi i contratti attraverso la stessa simulazione.

```text
+-------------+     +----------------------+     +------------------+
|  NodeProxy  |---->| FakeNetworkInterface |---->| NodeTestEndpoint |
+-------------+     +----------------------+     +--------+---------+
                                                          |
                                                   +------+------+
                                                   |             |
                                                   v             v
                                              sessione       operazioni
                                              del server     sulle partizioni
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
| `aRetryReturnsTheOriginalFailureWithoutRepeatingTheWrite` | risposta di errore persa dopo una modifica | il retry usa lo stesso ID e restituisce l'eccezione registrata; la scrittura viene eseguita una volta |
| `aRetryCanCompleteAfterACommunicationFailure` | guasto di comunicazione prima della scrittura | il retry usa lo stesso ID e completa la scrittura |

## Ingresso in recupero necessario — `RecoveryRequiredTest`

| Stato iniziale | Evento | Stato finale e verifica |
|---|---|---|
| Proxy usabile | Si perde la risposta alla creazione, con timeout di retry zero | Recupero necessario. La partizione esiste sul server. |
| Proxy usabile | Un chiamante già interrotto tenta di creare una partizione | Recupero necessario. Il flag resta impostato e la richiesta resta locale. |
| Proxy usabile, risposta al commit trattenuta | Il chiamante viene interrotto | Recupero necessario. Il flag resta impostato e lo snapshot pubblicato è leggibile sul server. |
| Recupero necessario | Il client tenta un'operazione sul nodo | Recupero necessario. Il proxy rifiuta ogni operazione con il primo errore come causa. |

## Riparazione — `RepairTest`

| Stato iniziale | Evento | Stato finale e verifica |
|---|---|---|
| Recupero necessario dopo una riparazione incompleta | Il callback successivo scrive e verifica lo snapshot atteso | Proxy usabile. Le chiamate ordinarie leggono il nuovo snapshot. |
| Proxy usabile | Il client invoca `recover(repair)` | Proxy usabile. Il metodo restituisce `true` e salta il callback. |
| Riparazione in corso | Il callback restituisce `false` | Recupero necessario. Le operazioni ordinarie restano bloccate. |
| Riparazione in corso | Il callback solleva una propria eccezione dopo il commit | Recupero necessario. Il client riceve la stessa eccezione e può confermare lo snapshot in una riparazione successiva. |
| Riparazione in corso | Il callback intercetta l'errore finale del commit e restituisce `true` | Recupero necessario. Il proxy segnala l'errore intercettato come causa. |
| Riparazione in corso | Un altro thread tenta una chiamata ordinaria o una transizione di connessione | Riparazione in corso. Il proxy respinge subito i tentativi e il callback può completarsi. |

Lo scenario con la risposta al commit persa durante la riparazione verifica
due tentativi. Il primo lascia il proxy da recuperare con il commit già
applicato sul server. Il secondo ricostruisce la partizione e riapre le
operazioni una volta verificato lo snapshot atteso.

## Risposte tardive — `LateResponseTest`

I due test partono con il proxy di nuovo usabile e una risposta della vecchia
sessione ancora trattenuta. La rete la consegna in un caso e la perde
nell'altro. Il diagramma include gli eventi che preparano lo stato iniziale.
Le distanze non sono in scala.

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
  revoca o scadenza. Verifica il rinnovo di una sessione scaduta e controlla
  che, dopo `recover()`, la prima richiesta usi un token diverso con
  sequenza zero.
- `badspace.remote.server.SessionRegistryTest` controlla la scadenza di risultati
  e sessioni con un orologio controllabile e verifica la pulizia periodica.
- `badspace.remote.client.RemoteCallExecutorTest`: con un tempo monotono
  controllato, l'executor ritenta prima della scadenza e si ferma quando il
  timeout è raggiunto. Verifica anche l'uso della stessa sessione con due
  API applicative distinte.
- `prototest.example.proxy.NodeProxyRecoveryTest`: errori finali prodotti direttamente
  dall'endpoint, senza messaggi persi, e chiamate concorrenti al primo errore.
- `prototest.fakenetwork`: test della rete fittizia stessa.
