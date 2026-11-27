# Rete concorrente e stato di errore del proxy

`NodeProxy` delega l'esecuzione delle chiamate a un `RemoteCallExecutor`.
L'executor gestisce la sessione tramite `SessionNetworkInterface`.
Le [Javadoc di `RemoteCallExecutor`](../../remote/src/main/java/badspace/remote/client/RemoteCallExecutor.java)
descrivono retry, errori e concorrenza. Il [ciclo di vita delle sessioni](multiple-proxies.md#durata-delle-sessioni)
spiega quando il server accetta il token del proxy.

## Controllo degli esperimenti di rete

`FakeNetworkInterface<T>` è un builder. `wrap(Contratto.class, endpoint)`
avvia la configurazione per un'interfaccia remota pubblica e `build()`
genera un'implementazione di quel contratto, fissando le regole.
La configurazione avviene in un solo thread; dopo `build()` il
proxy generato e i controlli di osservazione possono essere usati da più thread.
Il simulatore non introduce latenze casuali.

I metodi vengono identificati tramite lambda, senza un elenco parallelo di
operazioni. Ogni argomento del selector usa un matcher: `any()` accetta
qualsiasi riferimento, incluso `null`; `eq(valore)` confronta con
`Objects.equals` e supporta anche `eq(null)`. Non si mescolano matcher e
valori diretti. Per esempio:

```java
import static badspace.remote.network.FakeNetworkInterface.any;
import static badspace.remote.network.FakeNetworkInterface.eq;

var network = FakeNetworkInterface.wrap(NodeNetworkInterface.class, endpoint)
        .on(n -> n.createPartition(any(), eq("example"))).onFirstCall().dropRequest()
        .on(n -> n.writeToPartition(any(), any(), any())).onCall(2).dropResponse();
var node = network.build();
```

Il selector viene eseguito su un proxy di registrazione che non chiama il
server. Durante la sua esecuzione, `any()` ed `eq()` aggiungono predicati a
un contesto del thread corrente e restituiscono segnaposto `null`. Il proxy
associa i predicati ai parametri in ordine di valutazione. Scrivere ogni matcher
direttamente nella posizione del suo argomento, come in `createPartition(any(), eq("example"))`.
Calcolare prima `eq("example")` in una variabile assocerebbe quel predicato al primo parametro. Il contesto viene rimosso anche
se la registrazione fallisce; i matcher fuori dalla registrazione e le
registrazioni annidate sono errori. I matcher coprono i parametri di tipo
riferimento. `withArgs` resta disponibile per
condizioni più complesse sugli argomenti effettivi.

Ogni ingresso nel metodo del proxy conta come chiamata, inclusi i retry e
le richieste perse. Frequenze, attese e condizioni sulla storia contano le
invocazioni che corrispondono al selector completo, metodo e argomenti.
Una chiamata per un'altra partizione non avanza il conteggio del selector
limitato a `eq("example")`. I conteggi sono condivisi tra thread e indipendenti
dagli ulteriori filtri `withArgs` e `and`, e dall'esito delle regole.
`Invocation.callNumber()` mantiene invece il numero globale per metodo. `onEveryCall()` è il
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
`awaitAttempt(selettore, numero)` restituisce un `Attempt` con i controlli
`request()` e `response()` della singola invocazione. Il numero parte da 1
ed è relativo alle invocazioni che corrispondono al selector completo. L'attesa usa un timeout di sicurezza
di cinque secondi, configurabile per istanza con `wrap(Contratto.class, endpoint, timeout)`.
L'overload `awaitAttempt(selettore, numero, timeout)` permette di specificare
un limite diverso, anche `Duration.ZERO` per controllare subito la storia.
Questo valore predefinito riguarda solo l'osservazione dei tentativi;
`awaitArrival` e `awaitCompletion` ricevono ancora un timeout esplicito.
Il test osserva l'arrivo al punto con `awaitArrival`, poi decide se consegnare o perdere
il messaggio trattenuto. L'arrivo della risposta indica che il server ha
già completato l'operazione. Una regola `onEveryCall()` crea controlli
separati per ogni invocazione.

Un messaggio trattenuto resta in attesa anche dopo l'interruzione del worker:
il test può così consegnare risposte tardive dopo il recupero del proxy.
Nel `finally` il test chiama `releaseHeldMessages()` prima di aspettare i worker.
Il rilascio vale anche per i messaggi in arrivo e per i tentativi successivi.
Le perdite configurate o già decise restano valide.
`awaitCompletion` attende la conclusione del tentativo nella rete simulata;
non attende che il proxy elabori il risultato.
`assertAllRulesUsedAndCallsFinished()` segnala con `AssertionError` regole
mai applicate e chiamate non concluse; l'uso prima di `build()` resta un
errore d'uso (`IllegalStateException`). I timeout delle attese di osservazione
servono a evitare test bloccati, non a simulare il tempo di rete.

Il contratto completo è nelle
[Javadoc di `FakeNetworkInterface`](../../remote/src/main/java/badspace/remote/network/FakeNetworkInterface.java).

## Limiti

I limiti del registro delle richieste sono descritti nelle
[note sulle richieste da più proxy](multiple-proxies.md).
Il simulatore propaga direttamente le eccezioni del servizio: non simula
la perdita di una risposta contenente un errore del servizio.
