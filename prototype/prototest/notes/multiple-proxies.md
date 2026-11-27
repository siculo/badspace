# Richieste da più proxy

## Obiettivo

Permettere a più istanze di `NodeProxy` di invocare lo stesso endpoint
senza scambiare una richiesta nuova per il retry di un altro proxy.

## Identificatori delle richieste

`RequestId` contiene il token della sessione e una sequenza crescente,
a partire da zero. Il server genera un nuovo token con `UUID.randomUUID()`
a ogni apertura, anche quando lo stesso proxy recupera la sessione.

L'executor client assegna la sequenza sotto lo stesso lock con cui ammette le
chiamate concorrenti. All'esaurimento del contatore, l'incremento fallisce.
Ogni chiamata conserva il suo ID per tutti i tentativi. Più proxy possono
usare la stessa sessione condividendo l'executor e la sua sequenza.

Il registro verifica il token e cerca il risultato nella sessione
corrispondente. La sequenza indica l'ordine di assegnazione nella sessione.
Richieste concorrenti possono arrivare in ordine diverso o avere lo stesso
numero in sessioni diverse.

## Durata delle sessioni

`connect()` apre la prima sessione. Dopo un errore irrecuperabile,
`recover()` chiede al server di revocare il vecchio token e di emetterne uno
nuovo. Il proxy ammette chiamate solo dopo aver ricevuto un token valido.
`disconnect()` blocca le chiamate locali, interrompe l'attesa delle operazioni
pendenti e chiede al server di eliminare la sessione. Se si perde la conferma,
il proxy conserva il token per ripetere `disconnect()`. Il server conferma
la disconnessione anche se il token è già assente.

`ServerSessionEndpoint` elimina periodicamente risultati e sessioni scadute,
anche in assenza di traffico. Gli adattatori applicativi gli affidano le
operazioni attraverso `executeOnce` ed `executeOnceAndReturn`. Lo stesso lock
copre il controllo delle scadenze, l'esecuzione, la registrazione del risultato
e le transizioni di sessione.
Il timeout della sessione parte quando viene eliminato l'ultimo risultato,
oppure all'apertura se la sessione è ancora vuota. Il valore predefinito è
30 minuti ed è configurabile separatamente dal timeout dei risultati.
La chiusura dell'endpoint ferma il timer e revoca tutte le sessioni.

Finché la sessione esiste, il registro conserva la sequenza più alta tra
i risultati scaduti. Rifiuta ogni richiesta fino a quella soglia, anche
se è arrivata fuori ordine e non era ancora stata eseguita. Il registro
conserva i valori restituiti e le eccezioni applicative per la stessa durata.
I guasti di comunicazione seguono il protocollo di retry e recupero.

## Ripristino del nodo

Il timeout del proxy lascia incerto l'esito dell'operazione. Il rinnovo
della sessione blocca le vecchie richieste ancora in transito dal momento
della revoca. Il server conserva gli effetti già applicati, che il client
può correggere con `recover(repair)`.

Se una sessione inattiva scade, la prima chiamata riceve
`SessionUnavailableException`: il server ha respinto quel tentativo prima
di eseguirlo. Se era il primo tentativo e la chiamata era isolata, basta
`recover()`. Se il rifiuto arriva durante un retry, il proxy segnala
`NodeRecoveryRequiredException`: il primo tentativo può avere modificato
il nodo. Anche le altre chiamate pendenti possono avere avuto effetto.

Con `recover(repair)` il client rinnova il token e usa le operazioni di
`Node` sul thread chiamante. Gli altri thread dello stesso proxy restano
esclusi e ricevono un errore immediato. La funzione di riparazione
restituisce `true` quando il client conferma la consistenza, oppure
`false` se serve ancora intervenire. Il proxy restituisce lo stesso
valore e riapre le operazioni solo dopo una riparazione riuscita. Se era
già operativo, restituisce `true` senza invocare la funzione. La
riparazione deve propagare ogni errore di chiamata che richiede recupero.
Se lo intercetta e restituisce un valore, `recover(repair)` solleva
comunque un'eccezione. Dopo `false` o un'eccezione, il client può ripetere
`recover(repair)` partendo dagli effetti già applicati. Gli altri proxy
vanno coordinati dal client.

Per esempio, con accesso esclusivo e una copia dello stato atteso, il
client può eliminare una partizione nota, ricrearla, riscrivere lo snapshot,
fare commit e riscrivere i dati ancora da pubblicare. Ripartire
dall'eliminazione evita di duplicare le scritture dopo una risposta persa.
L'API permette di leggere lo snapshot di una partizione nota. Per ricostruire
l'intero nodo servono anche i nomi delle partizioni interessate e i dati
pendenti: il client deve conservarli perché l'API non li espone.
