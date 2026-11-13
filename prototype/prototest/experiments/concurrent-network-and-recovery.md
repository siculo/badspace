# Rete concorrente e stato di errore del proxy

## Obiettivo

Condividere una `FakeNetworkInterface` e un `NodeProxy` tra più thread,
distinguendo un errore del servizio da un errore definitivo di protocollo
che richiede il recupero del nodo.

## Rete fittizia

La coda degli esiti, il generatore casuale e la configurazione delle latenze
sono protetti dallo stesso monitor. Inserimento degli esiti, pulizia della
coda, estrazione e modifiche della configurazione sono sincronizzati.
Il monitor non viene mantenuto durante le attese o le chiamate al server:
più tentativi possono attraversare la stessa rete contemporaneamente.

Ogni tentativo estrae un solo esito, che resta valido per richiesta e risposta
anche se un altro thread modifica o svuota la coda nel frattempo. Gli esiti
sono globali alla rete, non associati a un thread o proxy: con chiamate
concorrenti non è stabilito quale chiamata consumi un particolare esito.
La configurazione della latenza viene letta a ogni tratta; una modifica può
quindi influire sulla risposta di una richiesta già inviata.

Sono ammesse latenze costanti, compreso zero. Un'interruzione durante
l'attesa conserva il flag del thread e diventa un errore definitivo di
protocollo, perché può avvenire anche dopo l'esecuzione sul server.

## Stato del proxy

I nomi delle eccezioni distinguono il tipo di errore e l'azione richiesta:

| Nome precedente | Nome attuale | Significato |
| --- | --- | --- |
| `ProtocolException` | `CommunicationException` | Base degli errori di comunicazione. |
| `RecoverableProtocolException` | `RetryableCommunicationException` | Il tentativo può essere ripetuto con lo stesso ID. |
| `UnrecoverableProtocolException` | `NodeRecoveryRequiredException` | Il proxy richiede recupero prima di nuove chiamate. |
| `PartitionException` | `PartitionNotFoundException` | La partizione richiesta non esiste. |

`ServiceException` e `ConnectionUnavailableException` mantengono i loro
nomi. Quest'ultima estende `NodeRecoveryRequiredException`, così il
chiamante può gestire insieme tutti gli errori che richiedono recupero,
oppure distinguere le chiamate rifiutate a causa della sessione già fallita.
La rinomina non cambia il comportamento.

`recoveryRequired()` indica se il proxy ha ricevuto un errore definitivo di
protocollo. Il primo errore viene conservato e notificato alle chiamate pendenti. La distinzione è questa:

| Esito | Comportamento |
| --- | --- |
| Errore del servizio, come `PartitionNotFoundException` | Propagato al chiamante; il proxy resta utilizzabile. |
| Errore recuperabile di protocollo | Retry con lo stesso ID, entro il timeout. |
| Retry concluso con successo | Il proxy resta utilizzabile, salvo un errore definitivo concorrente. |
| Timeout dei retry o `NodeRecoveryRequiredException` | Il chiamante riceve l’errore originale; il proxy passa a recupero necessario. |
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
Il timeout dei retry continua a partire dall'avvio del lavoro sul trasporto.

Al primo errore definitivo, il proxy completa i risultati delle altre
chiamate pendenti con `ConnectionUnavailableException`, che estende
`NodeRecoveryRequiredException`. Il messaggio indica che la connessione
non è disponibile e il proxy richiede recupero. I chiamanti vengono
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

La regola per il futuro recupero del nodo da snapshot persistente resta
valida, ma non viene applicata da questo reset semplificato. Anche la
propagazione agli altri proxy dello stesso nodo resta fuori da questo passo:
lo stato introdotto è locale alla singola istanza di `NodeProxy`.

## Verifiche

`FakeNetworkConcurrencyTest` verifica:

- consumo di 60 esiti da quattro thread, senza perdere o duplicare gli
  esiti, osservando anche le partizioni effettivamente create;
- condivisione dello stesso proxy e della stessa rete tra due thread,
  con risposte perse e scritture non duplicate;
- sovrapposizione delle chiamate al server e modifica della configurazione
  mentre sono in corso, senza alterare gli esiti già assegnati.

`NodeProxyRecoveryTest` verifica:

- errore del servizio seguito da nuove operazioni riuscite;
- creazione eseguita con risposta persa e timeout, seguita dal rifiuto di
  tutte le operazioni del nodo, conservando la causa e senza nuovi effetti;
- retry riusciti senza passaggio allo stato di recupero;
- errore definitivo ricevuto direttamente dall'endpoint;
- interruzione del thread, conservazione del flag e blocco del proxy;
- consegna di `ConnectionUnavailableException` alle altre chiamate in corso,
  conservando la causa originale e senza interrompere i thread applicativi;
- conclusione di tre chiamate pendenti, comprese chiamate con risultato,
  prima dello sblocco del trasporto che ignora la cancellazione;
- ripresa delle operazioni dopo `recover()`, senza riutilizzare gli ID;
- risposta tardiva di successo o errore di una vecchia chiamata dopo il
  recupero, senza riabilitare la vecchia chiamata o bloccare la nuova sessione.

Gli ordini concorrenti rilevanti sono controllati con latch; le attese
sono limitate e gli errori dei worker vengono propagati al test. I casi
di timeout del proxy usano timeout zero e un esito esplicito, senza
dipendere dalla velocità della macchina.

Comando dalla radice del repository:

```sh
mvn -f prototype/pom.xml -pl prototest test
```

Risultato: 30 test passati, di cui 13 nuovi rispetto all'esperimento sugli
ID, nessun errore o fallimento.

Restano i limiti del registro e del vecchio `TestService` descritti
nell'[esperimento precedente](multiple-proxies.md). Il simulatore continua
a propagare direttamente le eccezioni del servizio: la perdita di una
risposta contenente un errore del servizio non è ancora simulata.
