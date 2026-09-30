# TODO del prototipo Java

<a id="blocchi"></a>
## Cose da fare, in blocchi

- [ ] **Basi:** ID Snowflake e rimozione delle partizioni. → [cose da fare](#fare-basi) · [decisioni](#decisioni-basi)

  Documenti: [ID delle entità](okf-bundle/decisions/entity-ids.md), [Partizionamento del DB](okf-bundle/decisions/partitioning.md), [Architettura a livelli](okf-bundle/architecture/layers.md).

- [ ] **Indice e query:** indice minimo, configurazione della partizione, range, k-nearest, raycast e aggregazione. Poi partizioni con indici diversi, il client della strategia mista e i benchmark. → [cose da fare](#fare-indice) · [decisioni](#decisioni-indice)

  Documenti: [Indicizzazione spaziale](okf-bundle/decisions/spatial-indexing.md), [Entità statiche vs dinamiche](okf-bundle/decisions/static-vs-dynamic-entities.md), [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md), [API da esporre](okf-bundle/decisions/api-surface.md), [Casi d'uso](okf-bundle/use-cases.md).

- [ ] **Commit e proprietà:** commit e letture isolate, writer su thread diversi, fencing token. → [cose da fare](#fare-commit)

  Documenti: [Commit della partizione](okf-bundle/architecture/partition-commit.md), [Proprietà della partizione](okf-bundle/architecture/partition-ownership.md), [Concorrenza](okf-bundle/decisions/concurrency.md).

- [ ] **Metadati e tombstone:** metadati, scritture condizionate, tombstone e deduplica per epoca. → [cose da fare](#fare-metadati) · [decisioni](#decisioni-metadati)

  Documenti: [Metadati delle entità](okf-bundle/architecture/entity-metadata.md), [Tombstone](okf-bundle/architecture/tombstones.md), [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md).

- [ ] **Migrazione:** durabilità osservabile, poi il protocollo con i suoi pezzi, il GC delle tombstone, gli scenari di guasto e il client per aree non contigue. → [cose da fare](#fare-migrazione) · [decisioni](#decisioni-migrazione)

  Documenti: [Durabilità osservabile](okf-bundle/architecture/observable-durability.md), [Migrazione di entità tra partizioni](okf-bundle/mechanisms/entity-migration.md), [Tombstone](okf-bundle/architecture/tombstones.md), [Persistenza](okf-bundle/decisions/persistence.md).

- [ ] **Letture coerenti:** versioni conservate, tick globale, lettura al tick N, rimozione differita e il client per zone contigue. → [cose da fare](#fare-letture) · [decisioni](#decisioni-letture)

  Documenti: [Conservazione delle versioni](okf-bundle/architecture/version-retention.md), [Letture coerenti su più partizioni](okf-bundle/mechanisms/consistent-reads.md), [Architettura minima del layer delle partizioni](okf-bundle/architecture/minimal-core.md).

- [ ] **Chiusura:** ribilanciamento e split, nodo gRPC e aggiornamento del bundle. → [cose da fare](#fare-chiusura) · [decisioni](#decisioni-chiusura)

  Documenti: [Ribilanciamento e split](okf-bundle/mechanisms/rebalancing-and-split.md), [Tipo di strumento](okf-bundle/decisions/tool-type.md), [Architettura a livelli](okf-bundle/architecture/layers.md), [Tipo delle coordinate](okf-bundle/decisions/coordinate-type.md), [Linguaggio](okf-bundle/decisions/language.md), [Approccio di sviluppo](okf-bundle/process/development-approach.md).

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
- [ ] Rimozione dinamica delle partizioni

<a id="fare-indice"></a>
### Indice e query [↑](#blocchi)

- [ ] Indice spaziale minimo per partizione
- [ ] Configurazione della partizione alla creazione (indice e k)
- [ ] Query di range
- [ ] Query k-nearest
- [ ] Raycast (primo hit e tutti gli hit)
- [ ] Aggregazione delle query su più partizioni
- [ ] Più partizioni con indici diversi (statiche e dinamiche)
- [ ] Client di test per la strategia mista per tipo di entità
- [ ] Benchmark relativi tra strutture di indice

<a id="fare-commit"></a>
### Commit e proprietà [↑](#blocchi)

- [ ] Commit della partizione con contatore monotono
- [ ] Letture isolate dai commit non completi
- [ ] Writer su thread diversi per partizioni diverse
- [ ] Proprietà della partizione con fencing token (acquisire, rilasciare, trasferire)

<a id="fare-metadati"></a>
### Metadati e tombstone [↑](#blocchi)

- [ ] Metadati di sistema delle entità (epoca, stato, destinazione)
- [ ] Metadati applicativi delle entità
- [ ] API dei metadati (lettura e scrittura)
- [ ] Scritture condizionate (compare-and-set)
- [ ] Tombstone alla rimozione
- [ ] Regola di inserimento con confronto dell'epoca
- [ ] Lettura ed eliminazione delle tombstone
- [ ] Puntatore di inoltro nelle letture per ID
- [ ] Deduplica per ID ed epoca nell'aggregazione

<a id="fare-migrazione"></a>
### Migrazione [↑](#blocchi)

- [ ] Durabilità osservabile (ultimo commit persistito, anche simulato)
- [ ] Protocollo di migrazione tra partizioni
- [ ] Entità congelate durante la migrazione
- [ ] Reinvio, ACK, ACK "superato" e messaggio REMOVED
- [ ] Primitiva "sposta da A a B" nell'API
- [ ] GC delle tombstone (conferma spontanea e verifica su richiesta)
- [ ] Scenari di guasto della migrazione (crash di A, crash di B, catene, rimbalzi)
- [ ] Client di test per aree non contigue

<a id="fare-letture"></a>
### Letture coerenti [↑](#blocchi)

- [ ] Conservazione delle versioni degli ultimi k commit (copy-on-write)
- [ ] Tick globale e barriera
- [ ] Lettura al tick N
- [ ] Rimozione differita come alternativa alle letture al tick N
- [ ] Client di test per zone contigue con confini

<a id="fare-chiusura"></a>
### Chiusura [↑](#blocchi)

- [ ] Ribilanciamento tramite trasferimento dell'esclusività
- [ ] Split di una partizione
- [ ] Nodo remoto via gRPC
- [ ] Aggiornamento del bundle OKF con quanto appreso dai prototipi

## Decisioni da prendere

### Già prese [↑](#blocchi)

- [x] Metodologia a prototipi minimali con client di test
- [x] Tipo di strumento (libreria embedded, nodi via gRPC in futuro)
- [x] Linguaggio dei prototipi (Java)
- [x] Partizionamento con partizioni indipendenti e dinamiche
- [x] Concorrenza (un solo writer per partizione)
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

<a id="decisioni-basi"></a>
### Basi [↑](#blocchi)

- [ ] Divisione dei bit dell'ID (41/10/12 o 41/12/10)

<a id="decisioni-indice"></a>
### Indice e query [↑](#blocchi)

- [ ] Struttura dell'indice spaziale (grid, quadtree/octree, R-tree)
- [ ] Più di un indice per partizione
- [ ] Forma delle chiamate per le query su più partizioni

<a id="decisioni-metadati"></a>
### Metadati e tombstone [↑](#blocchi)

- [ ] Struttura dei metadati applicativi (schema libero o tipizzati, limiti di dimensione)
- [ ] Forma delle chiamate per commit e scritture condizionate
- [ ] Filtri sui metadati nelle query
- [ ] Forma della gestione delle tombstone nell'API

<a id="decisioni-migrazione"></a>
### Migrazione [↑](#blocchi)

- [ ] Trasporto dei messaggi di migrazione (fornito dall'API o lasciato al software)
- [ ] Forma delle chiamate per la migrazione
- [ ] Copie in uscita con posizione diversa nell'aggregazione
- [ ] Mappa ID → partizione offerta dall'API come aiuto opzionale

<a id="decisioni-letture"></a>
### Letture coerenti [↑](#blocchi)

- [ ] Implementazione e costo della conservazione delle versioni
- [ ] Lettura al tick N o rimozione differita come default

<a id="decisioni-chiusura"></a>
### Chiusura [↑](#blocchi)

- [ ] Strategie di partizionamento pronte come componenti opzionali
- [ ] Strategia di persistenza (snapshot per partizione)
- [ ] Chiamate a lotti ed errori remoti nel contratto dei nodi
- [ ] Servizio del tick globale e della barriera nella forma a cluster
- [ ] Nodi condivisi tra spazi e ID globali delle partizioni
- [ ] Unità di misura (fissa o scelta dal software)
- [ ] Determinismo dei calcoli tra macchine diverse
- [ ] Tipo delle coordinate nell'implementazione finale
- [ ] Linguaggio dell'implementazione finale (C11, C++, Rust)
