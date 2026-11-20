# Richieste da più proxy

## Obiettivo

Permettere a più istanze di `NodeProxy` di invocare lo stesso endpoint
senza scambiare una richiesta nuova per il retry di un altro proxy.

## Identificatori delle richieste

`RequestId` contiene il token della sessione e una sequenza crescente,
a partire da zero. Il server genera un nuovo token con `UUID.randomUUID()`
a ogni apertura, anche quando lo stesso proxy recupera la sessione.

Il proxy assegna la sequenza sotto lo stesso lock con cui ammette le
chiamate concorrenti. All'esaurimento del contatore, l'incremento fallisce.
Ogni chiamata conserva il suo ID per tutti i tentativi.

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

L'endpoint elimina periodicamente risultati e sessioni scadute, anche in
assenza di traffico. Controlla le scadenze anche quando ammette una richiesta.
Il timeout della sessione parte quando viene eliminato l'ultimo risultato,
oppure all'apertura se la sessione è ancora vuota. Il valore predefinito è
30 minuti ed è configurabile separatamente dal timeout dei risultati.
La chiusura dell'endpoint ferma il timer e revoca tutte le sessioni.

Finché la sessione esiste, il registro conserva la sequenza più alta tra
i risultati scaduti. Rifiuta ogni richiesta fino a quella soglia, anche
se è arrivata fuori ordine e non era ancora stata eseguita. Il registro
conserva i risultati delle sole operazioni riuscite.

## Ripristino del nodo

Il timeout del proxy lascia incerto l'esito dell'operazione. Il rinnovo
della sessione blocca le vecchie richieste ancora in transito dal momento
della revoca. Il server conserva gli effetti già applicati. Per simulare
il ripristino, l'endpoint può installare un altro `NodeServer` già pronto.
Nella stessa sezione critica revoca tutti i token e sostituisce il nodo.
Una richiesta porta sempre il suo token originale, quindi viene respinta
se arriva dopo il ripristino. L'esperimento usa uno stato fornito dal
chiamante per simulare il caricamento da uno snapshot.
