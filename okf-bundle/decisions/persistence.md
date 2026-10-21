---
type: Design Decision
title: Persistenza
description: Persistenza periodica del contenuto di una singola partizione, eseguita dal nodo che la gestisce su una destinazione configurata sul nodo, da una copia autonoma dello snapshot; l'API comanda il salvataggio con una chiamata sincrona e ne riceve l'esito, mentre la periodicità e la conservazione dei salvataggi sono del software; formato binario con file scritti in modo atomico; caricamento in una nuova partizione creata con il contenuto salvato e misure dei costi nel prototipo, con recupero coerente dei nodi e durabilità osservabile rimandati.
tags: [badspace, design, persistence, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-19T17:00:00Z }
---

# Perimetro attuale

Nel prototipo la persistenza si implementa dopo i
[metadati](/architecture/entity-metadata.md) e riguarda il **contenuto
di una singola partizione**: salvataggio periodico da uno snapshot e
caricamento dei dati in una nuova partizione. Serve anche a valutare i
tempi e i costi del salvataggio e del ripristino.

Si salvano tutti i dati delle entità presenti nello snapshot: **ID,
posizioni e metadati applicativi**. Gli indici si ricostruiscono durante
il caricamento delle entità, senza salvarne la struttura interna.
Quando arrivano nuovi dati, per esempio le
[tombstone](/architecture/tombstones.md), si estende il contenuto
persistito (vedi [approccio di sviluppo](/process/development-approach.md#meccanismi-prima-estesi-man-mano)).

# Copia e salvataggio

Lo snapshot pubblicato è immutabile e contiene solo lo stato confermato
dell'ultimo commit del tick completato (vedi [isolamento delle
letture](/decisions/read-isolation.md)). Il nodo che gestisce la
partizione esegue il salvataggio, su comando dell'API, come un reader
interno: usa le stesse acquisizioni degli altri reader, con la finestra e
la scadenza, senza regole speciali. I passi sono:

1. Acquisire lo snapshot della partizione, con la scadenza di default.
2. Copiarne ID, posizioni e metadati in una query sull'acquisizione.
3. Rilasciare l'acquisizione.
4. Salvare la copia autonoma, senza dipendere dalla scadenza
   dell'acquisizione o dalla vita dello snapshot originale.

La copia deve iniziare mentre l'acquisizione è valida, cioè subito
dopo l'acquisizione. Poi, come ogni query già ammessa, finisce anche se
l'acquisizione scade: lo snapshot resta in vita fino alla fine della
query. La scadenza quindi non limita la durata della copia, e le
chiamate di salvataggio non hanno opzioni di scadenza. Una copia lunga
costa memoria: lo snapshot e i suoi buffer restano in vita, anche fuori
dalla finestra, fino alla sua fine. La scrittura successiva usa solo la
copia. Il writer continua a lavorare durante entrambe le fasi: non ci
sono scritture sincrone nel game loop.
La copia avviene dentro il nodo e non passa dall'API (vedi
[conservazione degli snapshot](/architecture/snapshot-retention.md#copia-autonoma-per-la-persistenza)).
Una funzione di copia esposta al software resta utile per tenere uno
snapshot oltre la scadenza massima delle acquisizioni, ma non serve a
questo blocco.

## Chiamate

La partizione dell'API offre `save(tick)` e `saveLatest()`, come
`acquire(tick)` e `acquireLatest()`: il primo salva lo snapshot di un
tick della finestra, il secondo l'ultimo snapshot pubblicato, e
falliscono come le acquisizioni corrispondenti. Salvare un tick scelto
permette, con le [letture coerenti](/mechanisms/consistent-reads.md),
di salvare tutte le partizioni allo stesso tick N e ottenere un
salvataggio coerente tra partizioni senza un meccanismo apposta.

Le chiamate sono **sincrone**: ritornano a scrittura finita con il
riferimento al salvataggio (ID della partizione e tick), o falliscono
con un errore. Bloccano solo il thread che salva, che il software
dedica a questo; il writer e gli altri reader continuano. L'esito è il
valore di ritorno, senza un meccanismo di notifica, e la forma si
traduce in modo diretto in C e in Rust. Se al software serve un
salvataggio asincrono, lo costruisce sopra con un suo executor.

Un salvataggio asincrono nel contratto del nodo si valuta con il [nodo
gRPC](/decisions/tool-type.md), dove una chiamata lunga occupa la
chiamata remota e il suo timeout, e con la [durabilità
osservabile](/architecture/observable-durability.md), dove serve sapere
quando un salvataggio è finito senza aspettarlo. La forma più probabile
è avviare il salvataggio e poi interrogarne lo stato.

Due salvataggi dello stesso tick di una partizione, anche
contemporanei, producono lo stesso file e lo stesso riferimento: la
scrittura atomica lascia comunque un file valido.

## Formato

Ogni salvataggio è un file nella destinazione del nodo. La destinazione
ha una sottodirectory `partitions`, con una directory per ogni
partizione, che ha come nome l'ID della partizione; dentro, il file di
un salvataggio ha come nome il tick (`partitions/<ID>/<tick>.bsave`).
L'elenco dei salvataggi di una partizione è il contenuto della sua
directory.

Il formato è binario e semplice:

1. un'intestazione: identificativo del formato, versione, numero di
   dimensioni, ID della partizione, tick, numero di entità; l'ID della
   partizione permette di verificare un file anche fuori dalla sua
   directory;
2. gli array paralleli di ID e coordinate;
3. i metadati applicativi: una tabella delle chiavi in ordine di
   stringa e, per ogni entità, le coppie (indice della chiave, valore)
   in ordine di chiave;
4. un checksum CRC32 di tutti i byte precedenti, che riconosce un file
   corrotto.

I numeri sono little-endian, l'ordine dei byte delle CPU più diffuse
(x86, ARM, RISC-V). Gli ordini fissati di entità, chiavi e coppie fanno
sì che due salvataggi dello stesso snapshot producano gli stessi byte.
Un formato testuale sarebbe più leggibile, ma più grande e lento, e
distorcerebbe le misure dei costi.

Il file si scrive in modo atomico: prima un file temporaneo con un nome
unico nella directory della partizione, poi la sincronizzazione sul
disco, il rename sul nome finale, che sostituisce un file già presente,
e la sincronizzazione della directory, che rende durevole il rename. Un
salvataggio interrotto non produce un file valido e non tocca i
salvataggi esistenti. Del ciclo di un salvataggio è responsabile un
oggetto apposito, che crea il file temporaneo, ci scrive la copia dello
snapshot, lo rinomina e lo elimina in caso di errore.

I file temporanei rimasti da salvataggi interrotti, per esempio da un
crash, li elimina una funzione di pulizia del nodo, che li cerca nelle
directory di tutte le partizioni. Il nodo non tiene traccia dei
temporanei: la pulizia va chiamata quando nessun salvataggio è in
corso, per esempio dopo l'avvio del nodo.

## Periodicità e conservazione

Né l'API né il nodo hanno uno scheduler: la periodicità è una politica
del software, secondo l'[architettura a livelli](/architecture/layers.md).
Il software dedica un thread che, per esempio ogni N tick, chiama il
salvataggio e gestisce l'esito.

Il nodo offre l'elenco dei salvataggi di una partizione e
l'eliminazione di un salvataggio. Quanti salvataggi conservare lo
decide il software; l'elenco serve anche a ritrovarli dopo un riavvio
del software.

# Destinazione

I dati persistiti vanno su una **destinazione configurata sul nodo**
che gestisce la partizione, non su un volume del software che usa
l'API. Nel prototipo la destinazione è una directory; più avanti può
essere un volume condiviso o un object store. L'API comanda il
salvataggio e riceve l'esito: completamento o errore.

I motivi:

- **Nessun trasferimento del contenuto.** Se scrivesse il software, a
  ogni salvataggio periodico tutto il contenuto della partizione
  passerebbe dal nodo al software: con un nodo remoto è un traffico
  proporzionale alla dimensione della partizione, ripetuto a ogni
  salvataggio.
- **Durabilità di una partizione.** La [durabilità
  osservabile](/architecture/observable-durability.md) è una proprietà
  della partizione: il nodo sa da solo quando un salvataggio è
  completo, senza che il software glielo comunichi.
- **Recupero dei nodi.** Il futuro recupero dopo un crash trova i dati
  delle partizioni sullo storage del nodo.
- **Forma a servizio.** È coerente con la possibile centralizzazione
  della destinazione in una forma a servizio (vedi [tipo di
  strumento](/decisions/tool-type.md)).

Al software, secondo l'[architettura a livelli](/architecture/layers.md),
restano le politiche: quando salvare, quali partizioni, quanti
salvataggi conservare e da quale salvataggio caricare. Un salvataggio
è identificato da un riferimento (partizione e tick) che il software
usa per il caricamento.

Il caricamento su un altro nodo richiede che la destinazione sia
raggiungibile da quel nodo, cioè uno storage condiviso. Con il solo
disco locale si carica sullo stesso nodo; lo spostamento tra nodi è un
caso successivo.

# Caricamento

Lo spazio dell'API offre `loadPartition(node, config, saved)`: il nodo
crea una nuova partizione già con il contenuto di un salvataggio, cioè
con le entità con gli stessi ID, posizioni e metadati. Il contenuto è
confermato al tick di creazione, quindi è nello snapshot di quel tick e
diventa acquisibile al successivo avanzamento del tick, senza stati
intermedi visibili. La partizione nasce senza proprietario, come le
altre. Gli indici si ricostruiscono dagli inserimenti. Indice e
finestra degli snapshot li sceglie chi carica, non vengono dal
salvataggio; una posizione fuori dai limiti dell'indice scelto fa
fallire il caricamento, e la partizione non viene creata. Nel contratto
del nodo il caricamento è una variante della creazione della
partizione, così l'API non espone un inserimento con ID scelti dal
chiamante.

Il tick segue il contesto di creazione della nuova partizione: può
partire da zero in una nuova esecuzione del tempo logico, mentre in uno
spazio già attivo segue il tick dello spazio. Non è necessario ripartire
dal tick dello snapshot salvato.

I metadati applicativi si caricano senza reinterpretarli: eventuali
valori che rappresentano tick o scadenze restano responsabilità del
software utilizzatore.

# Misure

Si misurano separatamente:

- tempo di copia dello snapshot;
- tempo di scrittura dei dati;
- tempo di caricamento, compresa la ricostruzione degli indici;
- dimensione dei dati salvati e memoria aggiuntiva occupata dalla copia.

# Recupero coerente dei nodi: lavoro successivo

Gli snapshot sono indipendenti per partizione, senza salvataggio atomico
tra partizioni. Il caricamento attuale ricrea il contenuto di una
partizione; il recupero della stessa partizione e di un nodo dopo un
crash richiede un contratto successivo. In un sistema con più nodi, il
crash di un nodo non ferma il tempo e le attività degli altri.

Sono quindi rimandati il recupero coerente dei nodi, la continuità del
tempo logico e della [proprietà](/architecture/partition-ownership.md),
e l'esposizione dell'ultimo tick persistito prevista dalla
[durabilità osservabile](/architecture/observable-durability.md).
Quest'ultima servirà ai protocolli che confermano operazioni solo dopo
la persistenza, come la [migrazione](/mechanisms/entity-migration.md).
Il salvataggio deve comunque avere un esito di completamento o errore.

# Punti aperti

- Salvataggio asincrono nel contratto del nodo, con il nodo gRPC e la
  durabilità osservabile.
- Durabilità del salvataggio secondo la piattaforma. Il nodo sincronizza
  file e directory sul disco, ma la garanzia dipende da sistema
  operativo, filesystem e disco: su macOS la sincronizzazione non svuota
  la cache del disco (serve `F_FULLFSYNC`), su Windows la directory non
  si sincronizza, e alcuni dischi ignorano il flush. Va rivista con
  l'implementazione finale e con destinazioni diverse da una directory
  locale (volume condiviso, object store), dove le garanzie cambiano; il
  nodo dovrà probabilmente dichiarare il livello di durabilità che offre.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — una futura forma a
  servizio potrebbe centralizzare dove vengono scritti gli snapshot.
