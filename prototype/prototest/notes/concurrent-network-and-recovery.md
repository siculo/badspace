# Rete concorrente e stato di errore del proxy

`NodeProxy` delega l'esecuzione delle chiamate a un `RemoteCallExecutor`
dedicato. La gestione di retry, errori, concorrenza e recupero locale è descritta
nelle [Javadoc di `RemoteCallExecutor`](../src/main/java/prototest/proxy/RemoteCallExecutor.java).

## Controllo degli esperimenti di rete

`FakeNetworkInterface` è un builder: `wrap(endpoint)` avvia la configurazione
e `build()` genera un'implementazione di `NodeNetworkInterface`, fissando
le regole. La configurazione avviene in un solo thread; dopo `build()` il
proxy generato e i controlli di osservazione possono essere usati da più thread.
Il simulatore non introduce latenze casuali.

I metodi vengono identificati tramite lambda, senza un elenco parallelo di
operazioni. Gli argomenti della lambda di selezione vengono ignorati;
`withArgs` filtra gli argomenti effettivi. Per esempio:

```java
var network = FakeNetworkInterface.wrap(endpoint)
        .on(n -> n.createPartition(null, null)).onFirstCall().dropRequest()
        .on(n -> n.writeToPartition(null, null, null)).onCall(2).dropResponse();
var node = network.build();
```

Ogni ingresso nel metodo del proxy conta come chiamata, inclusi i retry e
le richieste perse. I contatori sono distinti per metodo, condivisi tra
thread e indipendenti dai filtri sugli argomenti. `onEveryCall()` è il
comportamento predefinito; `onFirstCall`, `onCall`, `onFirstCalls` e
`onCallsFrom` limitano gli ingressi su cui si applica una regola.
Le regole restano registrate e vince la prima che corrisponde; una chiamata
senza corrispondenze procede normalmente.

`onlyIfCalled`, `onlyIfNeverCalled` e `onlyIfCalledTimes` osservano gli
ingressi precedenti alla chiamata corrente, anche falliti o ancora pendenti.
Non indicano il completamento dell'operazione. La registrazione e la scelta
della regola sono atomiche; la chiamata corrente è esclusa dalla storia
usata per queste condizioni. L'ordine fra ingressi concorrenti segue
l'acquisizione del lock, non l'ordine di avvio dei thread.

La richiesta e la risposta possono essere consegnate, perse o trattenute.
`awaitCall(selettore, numero, timeout)` restituisce i controlli distinti
della singola invocazione: `request()` e `response()`. Il test osserva
l'arrivo al punto con `awaitArrival`, poi decide se consegnare o perdere
il messaggio trattenuto. L'arrivo della risposta indica che il server ha
già completato l'operazione. Una regola `onEveryCall()` crea controlli
separati per ogni invocazione.

Un messaggio trattenuto resta in attesa anche dopo l'interruzione del worker:
il test può così consegnare risposte tardive dopo il recupero del proxy.
La pulizia del test deve rilasciare tutti i messaggi trattenuti.
`awaitCompletion` attende la conclusione del tentativo nella rete simulata;
non attende che il proxy elabori il risultato.
`assertAllRulesUsedAndCallsFinished()` segnala con `AssertionError` regole
mai applicate e chiamate non concluse; l'uso prima di `build()` resta un
errore d'uso (`IllegalStateException`). I timeout delle attese di osservazione
servono a evitare test bloccati, non a simulare il tempo di rete.

Il contratto completo è nelle
[Javadoc di `FakeNetworkInterface`](../src/main/java/prototest/proxy/FakeNetworkInterface.java).

## Limiti

I limiti del registro delle richieste sono descritti nelle
[note sulle richieste da più proxy](multiple-proxies.md).
Il simulatore propaga direttamente le eccezioni del servizio: non simula
la perdita di una risposta contenente un errore del servizio.
