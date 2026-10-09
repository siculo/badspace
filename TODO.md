# TODO del prototipo Java

<a id="blocchi"></a>
## Cose da fare, in blocchi

I blocchi sono in ordine: ognuno usa solo quanto fatto nei blocchi precedenti.

- [x] **Basi:** ID Snowflake e rimozione delle partizioni. → [cose da fare](#fare-basi)

  Documenti: [ID delle entità](okf-bundle/decisions/entity-ids.md), [Partizionamento del DB](okf-bundle/decisions/partitioning.md), [Architettura a livelli](okf-bundle/architecture/layers.md).

- [x] **Configurazione della partizione:** indice scelto alla creazione nell'API. Si usano gli indici così come sono oggi (scansione lineare, grid uniforme, griglia di quadtree); gli indici e i loro test sono nel [TODO degli indici](TODO-indices.md). → [cose da fare](#fare-configurazione)

  Documenti: [Indicizzazione spaziale](okf-bundle/decisions/spatial-indexing.md), [API da esporre](okf-bundle/decisions/api-surface.md).

- [ ] **Commit e proprietà:** un writer e più reader su una partizione: commit, indici copy-on-write, snapshot degli slot, k nella configurazione della partizione, writer su thread diversi, fencing token. → [cose da fare](#fare-commit)

  Documenti: [Commit della partizione](okf-bundle/architecture/partition-commit.md), [Isolamento delle letture](okf-bundle/decisions/read-isolation.md), [Proprietà della partizione](okf-bundle/architecture/partition-ownership.md), [Concorrenza](okf-bundle/decisions/concurrency.md), [Conservazione delle versioni](okf-bundle/architecture/version-retention.md).

- [ ] **Metadati:** metadati di sistema e applicativi nello strato service, API dei metadati, scritture condizionate. → [cose da fare](#fare-metadati) · [decisioni](#decisioni-metadati)

  Documenti: [Metadati delle entità](okf-bundle/architecture/entity-metadata.md), [Architettura minima](okf-bundle/architecture/minimal-core.md).

- [ ] **Persistenza:** snapshot per partizione e durabilità osservabile. → [cose da fare](#fare-persistenza) · [decisioni](#decisioni-persistenza)

  Documenti: [Persistenza](okf-bundle/decisions/persistence.md), [Durabilità osservabile](okf-bundle/architecture/observable-durability.md).

- [ ] **Query su più partizioni:** aggregazione dei risultati per tipo di query. → [cose da fare](#fare-query) · [decisioni](#decisioni-query)

  Documenti: [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md), [API da esporre](okf-bundle/decisions/api-surface.md), [Entità statiche vs dinamiche](okf-bundle/decisions/static-vs-dynamic-entities.md), [Casi d'uso](okf-bundle/use-cases.md).

- [ ] **Ribilanciamento e split:** trasferimento dell'esclusività tra writer. → [cose da fare](#fare-ribilanciamento)

  Documenti: [Ribilanciamento e split](okf-bundle/mechanisms/rebalancing-and-split.md), [Proprietà della partizione](okf-bundle/architecture/partition-ownership.md).

- [ ] **Tombstone e migrazione:** tombstone, protocollo di spostamento delle entità tra partizioni, deduplica per epoca nelle letture. → [cose da fare](#fare-migrazione) · [decisioni](#decisioni-migrazione)

  Documenti: [Tombstone](okf-bundle/architecture/tombstones.md), [Migrazione di entità tra partizioni](okf-bundle/mechanisms/entity-migration.md), [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md).

- [ ] **Letture coerenti:** versioni conservate, tick globale, lettura al tick N, rimozione differita. → [cose da fare](#fare-letture) · [decisioni](#decisioni-letture)

  Documenti: [Conservazione delle versioni](okf-bundle/architecture/version-retention.md), [Letture coerenti su più partizioni](okf-bundle/mechanisms/consistent-reads.md), [Architettura minima](okf-bundle/architecture/minimal-core.md).

- [ ] **Gruppi di partizioni:** API costruita sopra quella che lavora sulle singole partizioni. → [cose da fare](#fare-gruppi) · [decisioni](#decisioni-gruppi)

  Documenti: [API da esporre](okf-bundle/decisions/api-surface.md), [Architettura a livelli](okf-bundle/architecture/layers.md).

- [ ] **Raycast:** primo hit e tutti gli hit. Richiede di riprendere il lavoro sugli indici. → [cose da fare](#fare-raycast) · [decisioni](#decisioni-raycast)

  Documenti: [API da esporre](okf-bundle/decisions/api-surface.md), [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md), [Indicizzazione spaziale](okf-bundle/decisions/spatial-indexing.md).

- [ ] **Nodo gRPC:** nodo remoto dietro il contratto dei nodi. → [cose da fare](#fare-grpc) · [decisioni](#decisioni-grpc)

  Documenti: [Tipo di strumento](okf-bundle/decisions/tool-type.md), [Architettura a livelli](okf-bundle/architecture/layers.md).

**Tema trasversale:** i client di test si costruiscono man mano, accanto ai blocchi che li richiedono. → [cose da fare](#fare-client)

## Cose da fare

### Già fatto [↑](#blocchi)

- [x] Moduli common, service, api e client
- [x] Record `Point2`/`Point3` ed `Entity2`/`Entity3`
- [x] Contratto del nodo `PartitionNode2`/`PartitionNode3`
- [x] Storage delle partizioni in array primitivi
- [x] Nodo locale `LocalPartitionNode2`/`LocalPartitionNode3`
- [x] Generazione degli ID di entità e partizioni nell'API (contatore)
- [x] Spazi 2D e 3D con interfaccia comune `Space`
- [x] Partizioni 2D e 3D con interfaccia comune `Partition`
- [x] Creazione delle partizioni su un nodo scelto dal software
- [x] Inserimento di una o più entità
- [x] Lettura per ID di una o più entità
- [x] Aggiornamento della posizione di una o più entità
- [x] Rimozione di una o più entità
- [x] Scritture batch tutto-o-niente
- [x] Test unitari di spazi e nodi locali
- [x] Client di test di base
- [x] Run configuration di IntelliJ

<a id="fare-basi"></a>
### Basi [↑](#blocchi)

- [x] Generatore di ID in stile Snowflake a 64 bit
- [x] Rimozione dinamica delle partizioni (politica di rimozione scelta alla creazione)

<a id="fare-configurazione"></a>
### Configurazione della partizione [↑](#blocchi)

- [x] Contratto delle query nel nodo (range e k-nearest)
- [x] Scelta dell'indice alla creazione della partizione nel contratto del nodo
- [x] Limiti delle coordinate per partizione, fissati dall'indice (`IndexConfig.limits()`, `limits()` su partizione e nodo): scritture fuori dai limiti rifiutate, solo valori finiti anche nelle query → [Limiti delle coordinate](okf-bundle/decisions/spatial-indexing.md#limiti-delle-coordinate)
- [x] Configurazione della partizione alla creazione nell'API (`PartitionConfig`): indice e politica di rimozione

<a id="fare-commit"></a>
### Commit e proprietà [↑](#blocchi)

Le letture isolate dai commit non completi seguono la [decisione](okf-bundle/decisions/read-isolation.md): snapshot a ogni commit, slot copiati per intero, indici persistenti copy-on-write. Si arriva in più passi:

- [x] Commit minimo con contatore monotono: il commit incrementa solo il contatore, le scritture restano visibili subito (`commit(n)` e `lastCommit()`, vedi la [forma delle chiamate](okf-bundle/decisions/api-surface.md#commit))
- [x] Indici persistenti copy-on-write che usano il contatore: un nodo si modifica sul posto solo se è del commit corrente, altrimenti si copia; test che una radice tenuta da parte non cambia dopo un commit (basta un solo thread)
- [ ] Slot nello snapshot: coordinate copiate a ogni commit, ID e mappa ID → slot copiati solo con inserimenti o rimozioni (per ora tabella copiata, non HAMT)
- [ ] Pubblicazione atomica della radice `(slot_N, indice_N)`: i reader vedono solo commit completi
- [ ] k nella configurazione della partizione: il numero di commit passati da conservare (default 0), usato dalle [letture coerenti](#fare-letture); per ora solo le ultime k radici tenute vive, le versioni non usate le libera il GC
- [ ] Writer su thread diversi per partizioni diverse, con pool dei buffer degli snapshot e conteggio dei reader per versione (o epoch-based reclamation)
- [ ] Misura del costo per commit della copia degli slot e dei nodi dell'indice, con diverse frazioni di entità in movimento e reader concorrenti; i benchmark degli indici nel [TODO degli indici](TODO-indices.md) andranno ripetuti sulla versione copy-on-write
- [ ] Proprietà della partizione con fencing token (acquisire, rilasciare, trasferire)

<a id="fare-metadati"></a>
### Metadati [↑](#blocchi)

- [ ] Metadati di sistema delle entità nello strato service (epoca, stato, destinazione), in sola lettura per il software
- [ ] Metadati applicativi delle entità
- [ ] Metadati nello snapshot di ogni commit
- [ ] API dei metadati (lettura e scrittura)
- [ ] Scritture condizionate (compare-and-set)

<a id="fare-persistenza"></a>
### Persistenza [↑](#blocchi)

- [ ] Salvataggio e ripristino degli snapshot per partizione, senza fermare il writer
- [ ] Durabilità osservabile (ultimo commit persistito)

<a id="fare-query"></a>
### Query su più partizioni [↑](#blocchi)

- [ ] Aggregazione delle query su più partizioni (range e k-nearest)
- [ ] Deduplica per ID nell'aggregazione (l'epoca si aggiunge con la [migrazione](#fare-migrazione))
- [ ] Più partizioni con indici diversi (statiche e dinamiche) interrogate insieme

<a id="fare-ribilanciamento"></a>
### Ribilanciamento e split [↑](#blocchi)

- [ ] Ribilanciamento tramite trasferimento dell'esclusività
- [ ] Split di una partizione

<a id="fare-migrazione"></a>
### Tombstone e migrazione [↑](#blocchi)

- [ ] Tombstone alla rimozione
- [ ] Regola di inserimento con confronto dell'epoca
- [ ] Lettura ed eliminazione delle tombstone
- [ ] Puntatore di inoltro nelle letture per ID
- [ ] Protocollo di migrazione tra partizioni
- [ ] Entità congelate durante la migrazione
- [ ] Reinvio, ACK, ACK "superato" e messaggio REMOVED
- [ ] Primitiva "sposta da A a B" nell'API
- [ ] GC delle tombstone (conferma spontanea e verifica su richiesta)
- [ ] Scenari di guasto della migrazione (crash di A, crash di B, catene, rimbalzi)
- [ ] Deduplica per ID ed epoca nell'aggregazione (le copie in uscita perdono)

<a id="fare-letture"></a>
### Letture coerenti [↑](#blocchi)

- [ ] Conservazione delle versioni degli ultimi k commit (copy-on-write)
- [ ] Tick globale e barriera
- [ ] Lettura al tick N
- [ ] Rimozione differita come alternativa alle letture al tick N

<a id="fare-gruppi"></a>
### Gruppi di partizioni [↑](#blocchi)

- [ ] API dei gruppi di partizioni sopra l'API delle singole partizioni

<a id="fare-raycast"></a>
### Raycast [↑](#blocchi)

- [ ] Raycast negli indici (da aggiungere al [TODO degli indici](TODO-indices.md) quando si arriva qui)
- [ ] Raycast nel nodo e nell'API (primo hit e tutti gli hit), con l'aggregazione su più partizioni

<a id="fare-grpc"></a>
### Nodo gRPC [↑](#blocchi)

- [ ] Nodo remoto via gRPC

<a id="fare-client"></a>
### Client di test (tema trasversale) [↑](#blocchi)

Non è deciso quanti e quali client servono: si costruiscono man mano. Scenari possibili:

- [ ] Aree non contigue (utile dalla migrazione)
- [ ] Zone contigue con confini (utile dalle letture coerenti)
- [ ] Strategia mista per tipo di entità (utile dalle [query su più partizioni](#fare-query))

<a id="fare-bassa-priorita"></a>
### Cose che si potrebbero fare, a bassa priorità [↑](#blocchi)

Non fanno parte dei blocchi: quando farle è da stabilire. Quelle degli indici sono nel [TODO degli indici](TODO-indices.md).


## Decisioni da prendere

### Già prese [↑](#blocchi)

- [x] Metodologia a prototipi minimali con client di test
- [x] Tipo di strumento (libreria embedded, nodi via gRPC in futuro)
- [x] Linguaggio dei prototipi (Java)
- [x] Partizionamento con partizioni indipendenti e dinamiche
- [x] Concorrenza (un solo writer per partizione)
- [x] Isolamento delle letture (snapshot a ogni commit: slot copiati per intero, indici persistenti copy-on-write)
- [x] Nessuna transazione tra partizioni
- [x] Confine di rete tra API e nodi
- [x] Un nodo serve un solo spazio
- [x] Schema degli ID delle entità (Snowflake, mai riusati, senza partizione)
- [x] Generatore di ID (uno per processo, tempo logico, generatorId assegnato a ogni avvio)
- [x] Generazione degli ID di partizione nell'API
- [x] Mappa ID → partizione a carico del software
- [x] Supporto a 2D e 3D con implementazioni parallele
- [x] Tipo delle coordinate nei prototipi (`double`)
- [x] Coordinate passate come record
- [x] Contratto del nodo solo batch
- [x] Scritture tutto-o-niente ed eccezioni
- [x] Indice scelto per partizione
- [x] Entità statiche e dinamiche come caso di partizionamento
- [x] Aggregazione per tipo di query
- [x] Commit distinto dal tick (al più un commit per tick)
- [x] k configurabile per partizione, default 0
- [x] Scritture condizionate al posto delle transazioni
- [x] Regole delle tombstone ed epoca
- [x] Protocollo di migrazione (outbox con consumer idempotente)
- [x] GC delle tombstone su conferma, non a TTL
- [x] Ribilanciamento e split tramite trasferimento dell'esclusività
- [x] Forma della query di range (box e cerchio/sfera, dietro un'interfaccia sealed estendibile)
- [x] Forma delle chiamate per il commit (`commit(n)` con n scelto dal software e maggiore dell'ultimo commit)
- [x] Limiti delle coordinate fissati dall'indice: tetto globale 2^60, 2^30 celle per lato per gli indici a celle, query senza limiti, solo valori finiti (niente `NaN` né infiniti)

<a id="decisioni-metadati"></a>
### Metadati [↑](#blocchi)

- [ ] Struttura dei metadati applicativi (schema libero o tipizzati, limiti di dimensione)
- [ ] Forma delle chiamate per le scritture condizionate
- [ ] Filtri sui metadati nelle query

<a id="decisioni-persistenza"></a>
### Persistenza [↑](#blocchi)

- [ ] Strategia di persistenza (snapshot per partizione)

<a id="decisioni-query"></a>
### Query su più partizioni [↑](#blocchi)

- [ ] Forma delle chiamate per le query su più partizioni

<a id="decisioni-migrazione"></a>
### Tombstone e migrazione [↑](#blocchi)

- [ ] Forma della gestione delle tombstone nell'API
- [ ] Trasporto dei messaggi di migrazione (fornito dall'API o lasciato al software)
- [ ] Forma delle chiamate per la migrazione
- [ ] Copie in uscita con posizione diversa nell'aggregazione
- [ ] Mappa ID → partizione offerta dall'API come aiuto opzionale

<a id="decisioni-letture"></a>
### Letture coerenti [↑](#blocchi)

- [ ] Lettura al tick N o rimozione differita come default
- [ ] Servizio del tick globale e della barriera nella forma a cluster

<a id="decisioni-gruppi"></a>
### Gruppi di partizioni [↑](#blocchi)

- [ ] Gruppi di partizioni con configurazione condivisa (indice, k, politica di rimozione), distinti dagli insiemi di partizioni da interrogare

<a id="decisioni-raycast"></a>
### Raycast [↑](#blocchi)

- [ ] Raycast su entità puntiformi (raggio di hit, estensione delle entità o altro), in base ai possibili usi

<a id="decisioni-grpc"></a>
### Nodo gRPC [↑](#blocchi)

- [ ] Chiamate a lotti ed errori remoti nel contratto dei nodi
- [ ] Nodi condivisi tra spazi e ID globali delle partizioni

<a id="decisioni-lungo-termine"></a>
### Senza blocco, a lungo termine [↑](#blocchi)

- [ ] Strategie di partizionamento pronte come componenti opzionali
- [ ] Unità di misura (fissa o scelta dal software)
- [ ] Determinismo dei calcoli tra macchine diverse
- [ ] Tipo delle coordinate nell'implementazione finale
- [ ] Divisione dei bit dell'ID (41/10/12 o 41/12/10): si può cambiare anche più avanti, purché il timestamp resti negli stessi bit
- [ ] Linguaggio dell'implementazione finale (C11, C++, Rust)
- [ ] Più indici per una partizione (non è deciso se supportarli) → [Indice per partizione](okf-bundle/decisions/spatial-indexing.md#indice-per-partizione)
