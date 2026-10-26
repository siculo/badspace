---
type: Design Decision
title: "Salvataggio e caricamento delle partizioni"
description: "Persistenza implementata nel nodo locale: copia autonoma dello snapshot, file binario, elenco ed eliminazione, caricamento e misure dei costi."
tags: [badspace, design, persistence, partitioning]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro attuale

La persistenza implementata nel prototipo riguarda il **contenuto
di una singola partizione**: salvataggio periodico da uno snapshot e
caricamento dei dati in una nuova partizione. Serve anche a valutare i
tempi e i costi del salvataggio e del ripristino.

Si salvano tutti i dati delle entità presenti nello snapshot: **ID,
posizioni e metadati applicativi**. Gli indici si ricostruiscono durante
il caricamento delle entità, senza salvarne la struttura interna.

# Copia e salvataggio

Lo snapshot pubblicato è immutabile e contiene solo lo stato confermato
dell'ultimo commit del tick completato (vedi [isolamento delle
letture](/current/decisions/read-isolation.md)). Il nodo che gestisce la
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
[conservazione degli snapshot](/current/architecture/snapshot-retention.md#copia-autonoma-per-la-persistenza)).

## Chiamate

La partizione dell'API offre `save(tick)` e `saveLatest()`, come
`acquire(tick)` e `acquireLatest()`: il primo salva lo snapshot di un
tick della finestra, il secondo l'ultimo snapshot pubblicato, e
falliscono come le acquisizioni corrispondenti. Ogni salvataggio riguarda una singola partizione e il tick richiesto.

Le chiamate sono **sincrone**: ritornano a scrittura finita con il
riferimento al salvataggio (nome della partizione e tick), o falliscono
con un errore. Bloccano solo il thread che salva, che il software
dedica a questo; il writer e gli altri reader continuano. L'esito è il
valore di ritorno, senza un meccanismo di notifica. Se al software serve un
salvataggio asincrono, lo costruisce sopra con un suo executor.

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
partizione](/current/decisions/entity-ids.md#nome-della-partizione); dentro, il
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
(x86, ARM, RISC-V). Le stringhe (nome e chiavi) sono in UTF-8 rigoroso:
una stringa che UTF-8 non può conservare esattamente fa fallire la
scrittura, e byte non validi in UTF-8 fanno fallire la lettura, invece
di sostituire un carattere. Una chiave compare una sola volta nella
tabella e nelle coppie di un'entità: un duplicato fa fallire la
lettura, invece di sovrascrivere un valore. Gli ordini fissati di entità, chiavi e coppie fanno
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
del software, secondo l'[architettura a livelli](/current/architecture/layers.md).
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
corso non è impedita: il file può ricomparire con il rename.

L'elenco si basa solo sui nomi dei file e delle directory: salta i
file temporanei e quelli con un nome diverso da `<tick>.bsave`, senza
aprire i file; un file corrotto lo trova il caricamento. Le directory
delle partizioni restano anche senza salvataggi, perché eliminarle
competerebbe con un salvataggio che le crea.

# Destinazione

I dati persistiti vanno su una **destinazione configurata sul nodo**
che gestisce la partizione, non su un volume del software che usa
l'API. Nel prototipo la destinazione è una directory. L'API comanda il
salvataggio e riceve l'esito: completamento o errore.

Il nodo conserva i dati e realizza il salvataggio; l’API riceve solo il
riferimento al file. Questo mantiene la rappresentazione dello storage
e il formato dei salvataggi dentro il servizio.

Al software, secondo l'[architettura a livelli](/current/architecture/layers.md),
restano le politiche: quando salvare, quali partizioni, quanti
salvataggi conservare e da quale salvataggio caricare. Un salvataggio
è identificato da un riferimento (nome della partizione e tick) che il
software usa per il caricamento.

Il nodo carica i salvataggi presenti nella propria directory configurata.

# Caricamento

Lo spazio dell'API offre `loadPartition(node, config, saved)`: il nodo
crea una nuova partizione già con il contenuto di un salvataggio, cioè
con le entità con gli stessi ID, posizioni e metadati.

Il [contratto del generatore degli ID](/current/decisions/entity-ids.md#contratto-del-generatore)
richiede che i nuovi ID delle entità restino diversi anche da quelli
conservati nel salvataggio e ricaricati.

La partizione caricata ha il nome del salvataggio e un ID di runtime
nuovo; il caricamento fa il commit del contenuto al tick di creazione,
senza un commit del software: il contenuto è nello snapshot
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
  sistema operativo, filesystem e disco (vedi le
  [garanzie e i limiti](#garanzie-e-limiti-del-salvataggio)).
- I tempi sono di Java e di una sola macchina: valgono come rapporti e
  ordini di grandezza, non come previsione dell'implementazione finale.
- Solo distribuzione uniforme, un solo insieme di metadati, fino a 100 000
  entità, un solo thread e nessun writer concorrente.

# Garanzie e limiti del salvataggio

Il commit in memoria e il salvataggio sono operazioni distinte. L’esito
di `save` riguarda un singolo snapshot; il nodo non sceglie un insieme
coerente di salvataggi tra partizioni. Il software decide cosa conservare
e cosa caricare, e deve evitare di cancellare file ancora necessari.

Il nodo sincronizza file e directory secondo il supporto della
piattaforma. La garanzia in caso di interruzione dell’alimentazione
dipende da sistema operativo, filesystem e disco: su macOS il flush
ordinario non svuota la cache del disco; su Windows la directory non si
sincronizza. Un completamento non è una garanzia uniforme per ogni dispositivo.

# Correlati

- [Contratto dell’API](/current/api/operations.md#salvataggio).
- [Acquisizione e copia](/current/architecture/snapshot-retention.md#copia-autonoma-per-la-persistenza).
