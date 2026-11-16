# Rete concorrente e stato di errore del proxy

## Struttura

`FakeNetworkInterface` e `NodeProxy` possono essere condivisi tra più thread.
Il proxy distingue un errore del servizio da un errore definitivo di
comunicazione che richiede il recupero del nodo.

## Stato del proxy

`recoveryRequired()` indica se il proxy ha ricevuto un errore definitivo di
protocollo. Il primo errore viene conservato e notificato alle chiamate pendenti. La distinzione è questa:

| Esito | Comportamento |
| --- | --- |
| Errore del servizio, come `PartitionNotFoundException` | Propagato al chiamante; il proxy resta utilizzabile. |
| Errore recuperabile di protocollo | Retry con lo stesso ID, entro il timeout. |
| Retry concluso con successo | Il proxy resta utilizzabile, salvo un errore definitivo concorrente. |
| Timeout dei retry | Il chiamante riceve `NodeRecoveryRequiredException`, con l'ultimo errore recuperabile come causa; il proxy passa a recupero necessario. |
| `NodeRecoveryRequiredException` dal trasporto | Il chiamante riceve l'errore originale; il proxy passa a recupero necessario. |
| Altre chiamate pendenti nella sessione fallita | Ricevono `ConnectionUnavailableException`, con l’errore originale come causa, senza aspettare il trasporto. |
| Nuova chiamata attraverso un proxy da recuperare | Rifiutata prima di invocare la rete con `ConnectionUnavailableException`. |
| `recover()` | Riabilita il proxy per le chiamate successive. |

Il timeout dei retry usa il tempo monotono. Rimane un limite ai retry,
non una cancellazione di una chiamata bloccata: il proxy controlla il tempo
dopo un errore recuperabile. Il trattamento degli errori definitivi è
conservativo: non cerca di dimostrare che nessun tentativo abbia raggiunto
il nodo, neppure quando l'ultimo errore segnala una richiesta persa.

Il proxy separa l'attesa del chiamante dall'esecuzione del trasporto.
Ogni chiamata usa un worker virtuale privato e un `CompletableFuture` per
consegnare il risultato al thread applicativo. L'API resta sincrona.
Il timeout dei retry parte dall'avvio del lavoro sul trasporto.

Al primo errore definitivo, il proxy completa i risultati delle altre
chiamate pendenti con `ConnectionUnavailableException`. I chiamanti vengono
sbloccati senza attendere la risposta o la conclusione dei worker.
Non è una garanzia di latenza assoluta: resta il tempo di schedulazione
locale, ma non viene introdotta un'attesa del trasporto o un polling.

Il proxy tenta anche di fermare i worker privati con `interrupt()`, utile
per chiudere le attese simulate con `Thread.sleep()`. Non interrompe i
thread applicativi. La consegna dell'errore funziona anche se il trasporto
ignora questa cancellazione; in quel caso il worker può restare attivo fino
al ritorno dell'operazione e può ancora produrre effetti sul server.
Non si annullano gli effetti già avvenuti né si ritirano risultati già
consegnati.

Ogni chiamata appartiene a una sessione locale del proxy. La registrazione,
la consegna dei risultati e il passaggio in errore sono protetti dallo
stesso lock, senza mantenerlo durante le operazioni sulla rete. Un risultato
già completato prima dell'errore resta valido; quelli ancora pendenti
ricevono l'errore di connessione non disponibile.

`recover()` sostituisce la sessione in errore con una sana, senza attendere
la terminazione dei vecchi worker. Le vecchie chiamate restano concluse
con errore: non fanno nuovi retry e risultati o errori tardivi non modificano
la nuova sessione. Il reset ripristina soltanto lo stato locale di
`NodeProxy`; se il proxy è sano, non fa niente. UUID e contatore degli ID
restano invariati, evitando il riuso delle richieste registrate sul server.

Se il chiamante viene interrotto dall'esterno durante la propria attesa,
il proxy conserva il suo flag e considera la chiamata fallita con esito
incerto. Questo è distinto dalla notifica degli errori agli altri chiamanti,
che non modifica i loro flag.

`recover()` non ripristina il nodo da uno snapshot persistente e non
propaga il recupero agli altri proxy dello stesso nodo: modifica soltanto
lo stato locale della singola istanza di `NodeProxy`.

## Limiti

I limiti del registro delle richieste sono descritti nelle
[note sulle richieste da più proxy](multiple-proxies.md).
Il simulatore propaga direttamente le eccezioni del servizio: non simula
la perdita di una risposta contenente un errore del servizio.
