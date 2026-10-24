---
type: Design Decision
title: Persistenza
description: Persistenza periodica del contenuto di una singola partizione, eseguita dal nodo che la gestisce su una destinazione configurata sul nodo, da una copia autonoma dello snapshot; i salvataggi sono identificati dal nome della partizione, scelto dal software, e dal tick; l'API comanda il salvataggio con una chiamata sincrona e ne riceve l'esito, mentre la periodicità e la conservazione dei salvataggi sono del software; formato binario con file scritti in modo atomico; caricamento in una nuova partizione creata con il contenuto salvato; costi di copia, scrittura, caricamento e spazio misurati nel prototipo in 2D e 3D; recupero ricostruttivo dai soli salvataggi come direzione, con il taglio coerente scelto dal software.
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
riferimento al salvataggio (nome della partizione e tick), o falliscono
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

Due salvataggi dello stesso tick di una partizione producono lo stesso
file e lo stesso riferimento. Il nodo tiene traccia dei salvataggi in
corso: un salvataggio di uno snapshot che un altro thread sta già
salvando non lo copia e non lo scrive di nuovo, ma rilascia la sua
acquisizione, aspetta l'altro salvataggio e ne restituisce il
riferimento, o fallisce se l'altro fallisce, perché non ha più lo
snapshot per ripetere la copia. Così si evita una copia inutile e due
rename contemporanei sullo stesso file, che su alcune piattaforme
(Windows) falliscono. Il registro dei salvataggi in corso è
indipendente dal numero di dimensioni, come l'elenco e l'eliminazione.

## Formato

Ogni salvataggio è un file nella destinazione del nodo. La destinazione
ha una sottodirectory `partitions`, con una directory per ogni
partizione, che ha come nome il [nome della
partizione](/decisions/entity-ids.md#nome-della-partizione); dentro, il
file di un salvataggio ha come nome il tick
(`partitions/<nome>/<tick>.bsave`).
L'elenco dei salvataggi di una partizione è il contenuto della sua
directory.

Il formato è binario e semplice:

1. un'intestazione: identificativo del formato, versione, numero di
   dimensioni, nome della partizione, tick, numero di entità; il nome
   permette di verificare un file anche fuori dalla sua directory;
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

Il nodo offre meccanismi generici: l'elenco dei salvataggi, di tutti i
nomi o di un nome, e l'eliminazione di un insieme di salvataggi. Le
chiamate lavorano sui file della destinazione e non richiedono una
partizione viva, così servono anche dopo un riavvio, prima di
qualsiasi caricamento, e per i salvataggi delle partizioni rimosse.
Il nodo non ha regole su quali salvataggi conservare: quanti e quali
lo decide il software, che può adottare politiche diverse. Alcuni
esempi:

- **conservazione per taglio**: il software tiene i salvataggi del
  taglio coerente, ed eventualmente di qualche taglio precedente, ed
  elimina quelli più vecchi di tutti i nomi. Tenere gli ultimi N
  salvataggi di ogni partizione può invece eliminare l'unico tick
  comune, se le partizioni salvano a tick diversi o un salvataggio
  fallisce;
- **partizioni rimosse**: una partizione rimossa al tick R non salva
  più, ma i suoi salvataggi servono a un recupero a un taglio prima
  di R; si possono eliminare quando il software non tornerà più a un
  taglio prima di R. Il nodo non può deciderlo: non sa se il software
  ricaricherà quel nome;
- **salvataggi corrotti**: un caricamento che fallisce per il CRC
  porta il software a eliminare il salvataggio e a ripiegare su un
  taglio precedente;
- **ripartenza**: si eliminano tutti i salvataggi, o quelli di un
  insieme di nomi, per esempio nei test o per un mondo nuovo.

L'eliminazione è batch, come le altre operazioni del contratto del
nodo, perché una politica elimina spesso molti salvataggi insieme.
Eliminare un salvataggio che non c'è non fa niente, così due
politiche, o due thread, possono eliminare lo stesso salvataggio.
L'eliminazione di un tick mentre un salvataggio dello stesso tick è in
corso non è impedita: il file può ricomparire con il rename. Funzioni
di supporto nell'API, per esempio l'eliminazione dei salvataggi prima
di un tick per un insieme di nomi, si aggiungono se servono.

L'elenco si basa solo sui nomi dei file e delle directory: salta i
file temporanei e quelli con un nome diverso da `<tick>.bsave`, senza
aprire i file; un file corrotto lo trova il caricamento. Le directory
delle partizioni restano anche senza salvataggi, perché eliminarle
competerebbe con un salvataggio che le crea.

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
è identificato da un riferimento (nome della partizione e tick) che il
software usa per il caricamento.

Il caricamento su un altro nodo richiede che la destinazione sia
raggiungibile da quel nodo, cioè uno storage condiviso. Con il solo
disco locale si carica sullo stesso nodo; lo spostamento tra nodi è un
caso successivo.

# Caricamento

Lo spazio dell'API offre `loadPartition(node, config, saved)`: il nodo
crea una nuova partizione già con il contenuto di un salvataggio, cioè
con le entità con gli stessi ID, posizioni e metadati. La partizione
caricata ha il nome del salvataggio e un ID di runtime nuovo; un
caricamento con un altro nome, per copiare una partizione, si aggiunge
se serve. Il caricamento fa il commit del contenuto al tick di
creazione, senza un commit del software: il contenuto è nello snapshot
di quel tick e diventa acquisibile al successivo avanzamento del tick,
senza stati intermedi visibili. Un writer che vuole cambiare il
contenuto prima che i reader lo vedano prende la proprietà, scrive e fa
il commit nello stesso tick: il suo commit sostituisce quello del
caricamento. La partizione nasce senza proprietario, come le
altre. Gli indici si ricostruiscono dagli inserimenti. Indice e
finestra degli snapshot li sceglie chi carica, non vengono dal
salvataggio; una posizione fuori dai limiti dell'indice scelto fa
fallire il caricamento, e la partizione non viene creata. Nel contratto
del nodo il caricamento è una variante della creazione della
partizione, così l'API non espone un inserimento con ID scelti dal
chiamante.

Il caricamento non ferma l'avanzamento del tick, così si può caricare
una partizione mentre una simulazione va avanti. Il nodo lavora in due
fasi: prima, senza il lock del tick, legge il salvataggio e costruisce
tutta la partizione, con gli indici e il commit del contenuto; poi,
sotto il lock, controlla nome e ID, assegna il tick corrente come tick
di creazione e aggiunge la partizione. Il commit del contenuto cade
quindi nel tick in cui la partizione viene davvero popolata, che può
essere successivo al tick della chiamata; `creationTick()` lo dice. Lo
spazio tiene il suo lock solo per aggiungere il nodo e generare l'ID,
non durante il caricamento. Un nome occupato da un'altra creazione
durante la prima fase fa fallire il caricamento nella seconda.

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

## Come si misura

I benchmark sono nel modulo `prototype/benchmark` (`PersistenceBenchmark2`,
`PersistenceBenchmark3` e `PersistenceSizes`, piani
`plans/persistence-cost-quick.json` e `plans/persistence-cost-full.json`;
comandi e dettagli in `prototype/benchmark/README-it.md`). Ogni fase ha il
suo benchmark: il tempo di una fase non viene dalla differenza di due
misure.

- **Copia**: acquisizione dello snapshot pubblicato, copia autonoma e
  rilascio, sul percorso reale del nodo. Fuori misura la creazione e la
  pubblicazione dello snapshot.
- **Scrittura**: `PartitionSave.write()` completo, cioè codifica, CRC,
  scrittura del temporaneo, sincronizzazione del file, rename e
  sincronizzazione della directory, su un file sempre nuovo. Fuori misura
  la copia e l'eliminazione del file dopo ogni chiamata.
- **Caricamento**: `loadPartition()` completo, cioè lettura e controlli,
  ricostruzione dello storage e dell'indice, aggiunta al nodo. Fuori misura
  la creazione del file e del nodo vuoto.
- **Dimensione del file**: dimensione reale del file dopo un salvataggio
  del nodo.
- **Memoria della copia**: stima, come differenza dell'heap in uso dopo
  una garbage collection con e senza le copie, con lo snapshot di origine
  vivo in entrambe le osservazioni. Ogni campione tiene insieme copie per
  almeno 100 000 entità; si conservano 5 campioni.

La partizione è la stessa per tutte le misure, da seed fissi: ID da 1,
posizioni uniformi in un quadrato o in un cubo di lato 10 000, senza
metadati (`NONE`) o con 4 chiavi per entità (`health`, `owner`, `score`,
`team`) e valori casuali a 64 bit (`FIXED`). Copia e scrittura usano la
scansione lineare; il caricamento la scansione lineare, la grid uniforme
con celle di 100 e la griglia di quadtree (octree in 3D) con celle di 128.
JMH misura il tempo medio per chiamata con un solo thread, nessun writer
concorrente, 3 fork, 5 iterazioni di riscaldamento e 10 di misura da 1
secondo; il profiler `gc` registra allocazioni e collection.

## Campagna del 2026-10-24

Macchina dedicata: AMD Ryzen 5 5600 (6 core, 12 thread, governor
`schedutil`), 31 GiB di RAM, Linux Mint 21.3 con kernel 5.15, OpenJDK
25.0.2 con G1. Salvataggi su ext4 (`relatime`) in un Samsung SSD 850 PRO
da 512 GB su SATA, nella directory del job del servizio dei benchmark.
Commit `cc2c270`, piano completo, 47 minuti. Artefatti in
`prototype/benchmark/results/2026-10-24-1537-persistence-*-full.*`: JSON
grezzi con commit e ambiente, un report per run e il report di gruppo
`2026-10-24-1537-persistence-cost-full.html`.

## Osservazioni

Tempi medi per chiamata, in millisecondi. L'errore al 99,9% resta sotto il
3,5% del valore in tutte le misure.

| Fase | Metadati | 2D 1k | 2D 10k | 2D 100k | 3D 1k | 3D 10k | 3D 100k |
|---|---|---|---|---|---|---|---|
| Copia | `NONE` | 0,007 | 0,067 | 0,65 | 0,008 | 0,077 | 0,77 |
| Copia | `FIXED` | 0,137 | 1,56 | 20,3 | 0,141 | 1,57 | 20,1 |
| Scrittura | `NONE` | 5,75 | 6,81 | 15,9 | 5,73 | 6,78 | 18,6 |
| Scrittura | `FIXED` | 6,13 | 9,35 | 48,9 | 6,14 | 9,67 | 50,9 |
| Caricamento, scansione lineare | `NONE` | 0,47 | 4,80 | 48,2 | 0,47 | 4,87 | 49,1 |
| Caricamento, scansione lineare | `FIXED` | 0,84 | 9,29 | 104 | 0,85 | 9,17 | 105 |
| Caricamento, grid uniforme 100 | `NONE` | 0,52 | 5,83 | 61,0 | 0,52 | 5,71 | 64,6 |
| Caricamento, grid uniforme 100 | `FIXED` | 0,95 | 10,5 | 123 | 0,96 | 10,6 | 133 |
| Caricamento, griglia di quadtree/octree 128 | `NONE` | 0,52 | 5,80 | 62,8 | 0,53 | 5,76 | 65,9 |
| Caricamento, griglia di quadtree/octree 128 | `FIXED` | 0,94 | 10,6 | 129 | 0,95 | 10,6 | 134 |

Spazio per entità, in byte, uguale a tutte le dimensioni salvo dove
indicato:

| Misura | 2D `NONE` | 2D `FIXED` | 3D `NONE` | 3D `FIXED` |
|---|---|---|---|---|
| File del salvataggio | 28 | 76 | 36 | 84 |
| Memoria della copia | 28 | 236 | 36 (54 a 100k) | 245 (262 a 100k) |

Il file ha in più 45 byte fissi (intestazione, conteggi e CRC), e con
`FIXED` 36 byte per la tabella delle chiavi. I campioni della memoria
della copia differiscono al massimo dell'1%.

Allocazioni per chiamata a 100 000 entità (profiler `gc`): copia 2,8 MB
senza metadati e 61 MB con `FIXED`; caricamento con la scansione lineare
29 MB e 158 MB.

## Interpretazioni

- Tutte le fasi crescono in modo lineare con il numero di entità; la
  scrittura ha in più un costo fisso.
- **Scrittura**: con 1000 entità costa circa 5,7 ms, quasi tutti per le
  due sincronizzazioni (file e directory) su questo disco SATA. Il costo
  marginale è circa 0,1 µs per entità senza metadati e 0,4 µs con
  `FIXED`. Fino a 10 000 entità il costo di un salvataggio è dominato dalle
  sincronizzazioni, quindi dipende dal disco più che dalla partizione.
- **Metadati**: con 4 chiavi per entità la copia costa circa 30 volte di
  più, la scrittura fino a 3 volte e il caricamento circa il doppio. La
  copia ricava per ogni entità una `HashMap` dai metadati dello snapshot e
  poi ne fa la mappa immutabile di `ApplicationMetadata`: allocazioni da
  612 byte per entità e una copia che in memoria occupa circa 3 volte il
  file.
- **Ricostruzione dell'indice**: rispetto alla scansione lineare, la grid
  uniforme e la griglia di quadtree aggiungono circa il 25-35% al
  caricamento a 100 000 entità (13-17 ms senza metadati), il 10-20% con
  i metadati. Le due strutture costano quasi lo stesso.
- **2D e 3D**: tempi quasi uguali; il 3D costa 8 byte per entità in più
  nel file e nella copia, e qualche millisecondo in più nella scrittura e
  nel caricamento a 100 000 entità.
- **Memoria della copia in 3D a 100 000 entità**: 18 byte per entità in
  più del previsto. È l'array delle coordinate da 2,4 MB, che per G1 è un
  oggetto humongous e occupa una regione intera da 4 MiB. È memoria
  davvero occupata, ma dipende dal collector e dalla dimensione delle sue
  regioni.
- **Nel tick**: un salvataggio completo (copia e scrittura) di 100 000
  entità costa circa 17 ms senza metadati e 70 ms con `FIXED`; un
  caricamento 50-135 ms. Il writer non li aspetta: la copia è una query su
  uno snapshot e il caricamento costruisce la partizione fuori dal lock
  del tick. Su un thread separato restano quindi fuori dal game loop, ma
  la copia tiene vivo lo snapshot per la sua durata.

## Limiti

- Le letture del caricamento sono a caldo: il file è nella cache del
  sistema operativo. Non è un recupero dopo un riavvio della macchina.
- Il tempo di scrittura comprende le sincronizzazioni di ext4 su quel
  disco, ma non dice nulla sulla durabilità oltre quella garantita da
  sistema operativo, filesystem e disco (vedi i [punti
  aperti](#punti-aperti)).
- I tempi sono di Java e di una sola macchina: valgono come rapporti e
  ordini di grandezza, non come previsione dell'implementazione finale.
- Solo distribuzione uniforme, un solo insieme di metadati, fino a 100 000
  entità, un solo thread e nessun writer concorrente.

## Possibili attività future

Da valutare quando servono, nessuna è in corso:

- una rappresentazione compatta dei metadati nella copia e nel
  caricamento, per esempio array di chiavi e valori come nel file, se il
  costo dei metadati pesa nei casi d'uso;
- misure con il writer e i reader attivi durante copia e salvataggio;
- un recupero a freddo, con la cache del sistema operativo vuota;
- partizioni da 1 000 000 di entità e altri insiemi di metadati;
- altre destinazioni (disco NVMe, volume condiviso, object store).

# Recupero ricostruttivo

Per recuperare le partizioni ci sono due approcci: persistere lo stato
delle partizioni e dei nodi oltre agli snapshot, oppure **ricostruire lo
stato dai soli salvataggi**. La direzione scelta è la seconda, più
semplice da gestire: dopo un riavvio, o un crash, le partizioni si
ricreano caricando i loro salvataggi, e il resto dello stato è di
runtime e si ricrea.

L'identità persistente è il [nome della
partizione](/decisions/entity-ids.md#nome-della-partizione); l'ID è un
dato di runtime e non si persiste. Una partizione ricaricata ha lo
stesso nome e un ID nuovo, quindi i [token di
scrittura](/architecture/partition-ownership.md) dell'esecuzione
precedente, che contengono l'ID vecchio, non valgono più: il fencing
tra un'esecuzione e l'altra non richiede di persistere le generazioni.

Cosa si ricava dai salvataggi:

- **contenuto**: lo snapshot salvato;
- **stato dei protocolli**: epoca, stato, destinazione e
  [tombstone](/architecture/tombstones.md) sono nello snapshot di ogni
  commit, quindi nei salvataggi. La
  [migrazione](/mechanisms/entity-migration.md) non ha uno stato
  persistente separato: l'origine persiste la marcatura "in uscita"
  prima di spedire e la destinazione conferma dopo la persistenza, così
  una partizione ricaricata ritrova le entità da rispedire;
- **ultimo tick persistito** della [durabilità
  osservabile](/architecture/observable-durability.md): il tick più alto
  tra i salvataggi di un nome;
- **insieme delle partizioni** a un tick N: i nomi con un salvataggio
  al tick N, se il software ha salvato tutte le partizioni a quel tick
  con `save(N)`, comprese quelle create da uno split.

Non si persistono acquisizioni, sequenza dei commit, generazioni della
proprietà e tick interni, che sono stato di runtime; il generatore di
ID riceve un `generatorId` nuovo a ogni avvio.

**Il taglio è una politica del software.** Quale tick caricare per ogni
partizione lo sceglie il software, che ha i meccanismi per farlo:
`save(N)` per salvare tutte le partizioni allo stesso tick, l'elenco
dei salvataggi con nome e tick, e il caricamento. Il taglio coerente
(il tick più alto salvato da tutte le partizioni) è la scelta tipica,
non una regola: un taglio incoerente è possibile ed è responsabilità di
chi lo sceglie. Una funzione di supporto nell'API, per esempio il tick
più alto salvato da un insieme di nomi, si aggiunge se serve.

Limiti e condizioni:

- ogni riferimento persistito a una partizione usa il nome, mai l'ID;
- si perde il lavoro dopo l'ultimo salvataggio: i protocolli lo
  assumono già, perché non confermano niente prima della persistenza;
- con salvataggi completi l'ultimo tick persistito avanza al ritmo dei
  salvataggi, e la latenza della migrazione ne dipende. Se le misure lo
  rendono necessario, salvataggi incrementali (un salvataggio base e le
  differenze tra tick) restano un approccio ricostruttivo;
- il recupero di un solo nodo mentre gli altri continuano riporta le
  sue partizioni all'ultimo tick persistito, indietro rispetto allo
  spazio; va verificato con gli scenari di guasto della migrazione.

Sono rimandati il recupero di un nodo mentre gli altri continuano, la
continuità del tempo logico e l'esposizione dell'ultimo tick
persistito. Anche la persistenza della configurazione delle partizioni
(indice, finestra) è rimandata: si decide insieme al modo in cui il
software usa i nodi, crea le partizioni, spegne e riavvia il sistema e
recupera da un crash. Fino ad allora la configurazione la sceglie chi
carica. Il salvataggio deve comunque avere un esito di completamento o
errore.

# Punti aperti

- Eliminazione dei salvataggi e durabilità osservabile: l'ultimo tick
  persistito è il tick più alto tra i salvataggi di un nome, quindi
  eliminare il salvataggio più recente lo fa tornare indietro, mentre
  la migrazione conta sul fatto che non succeda. Si rivede con la
  durabilità osservabile.
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
