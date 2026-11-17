# Rete concorrente e stato di errore del proxy

`FakeNetworkInterface` e `NodeProxy` possono essere condivisi tra più thread.
`NodeProxy` delega l'esecuzione delle chiamate a un `RemoteCallExecutor`
dedicato. La gestione di retry, errori, concorrenza e recupero locale è descritta
nelle [Javadoc di `RemoteCallExecutor`](../src/main/java/prototest/proxy/RemoteCallExecutor.java).

## Controllo degli esperimenti di rete

`FakeNetworkInterface` usa piani espliciti per singolo tentativo, senza
latenze casuali. Il selettore riconosce operazione, ID e nome della partizione;
il primo piano non usato che corrisponde viene assegnato al tentativo.
Se più chiamate corrispondono allo stesso selettore, l'assegnazione segue
l'ordine di ingresso. Per controllarle separatamente servono selettori distinti
o un ordine di avvio controllato dal test.

La richiesta e la risposta possono essere consegnate, perse o trattenute.
Il test può osservare l'arrivo a ciascun punto e poi decidere se consegnare
o perdere il messaggio trattenuto. L'arrivo della risposta indica che il
server ha già completato l'operazione. I tentativi senza un piano
corrispondente procedono normalmente; ogni retry richiede un piano distinto.

Un messaggio trattenuto resta in attesa anche dopo l'interruzione del worker:
il test può così consegnare risposte tardive dopo il recupero del proxy.
La pulizia del test deve rilasciare tutti i messaggi trattenuti.
`awaitCompletion` attende la conclusione del tentativo nella rete simulata;
non attende che il proxy elabori il risultato. `verify()` rileva piani
inutilizzati e tentativi non conclusi. I timeout delle attese di osservazione
servono a evitare test bloccati, non a simulare il tempo di rete.

Il contratto completo è nelle
[Javadoc di `FakeNetworkInterface`](../src/main/java/prototest/proxy/FakeNetworkInterface.java).

## Limiti

I limiti del registro delle richieste sono descritti nelle
[note sulle richieste da più proxy](multiple-proxies.md).
Il simulatore propaga direttamente le eccezioni del servizio: non simula
la perdita di una risposta contenente un errore del servizio.
