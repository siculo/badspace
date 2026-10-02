# TODO del prototipo Java

<a id="blocchi"></a>
## Cose da fare, in blocchi

- [x] **Basi:** ID Snowflake e rimozione delle partizioni. → [cose da fare](#fare-basi)

  Documenti: [ID delle entità](okf-bundle/decisions/entity-ids.md), [Partizionamento del DB](okf-bundle/decisions/partitioning.md), [Architettura a livelli](okf-bundle/architecture/layers.md).

- [ ] **Indice e query:** range e k-nearest con scansione lineare, test di correttezza, benchmark e misure di riferimento. Poi indice minimo, configurazione della partizione, aggregazione, partizioni con indici diversi, il client della strategia mista e i benchmark tra indici. → [cose da fare](#fare-indice) · [decisioni](#decisioni-indice)

  Documenti: [Indicizzazione spaziale](okf-bundle/decisions/spatial-indexing.md), [Entità statiche vs dinamiche](okf-bundle/decisions/static-vs-dynamic-entities.md), [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md), [API da esporre](okf-bundle/decisions/api-surface.md), [Casi d'uso](okf-bundle/use-cases.md).

- [ ] **Commit e proprietà:** commit e letture isolate, writer su thread diversi, fencing token. → [cose da fare](#fare-commit)

  Documenti: [Commit della partizione](okf-bundle/architecture/partition-commit.md), [Proprietà della partizione](okf-bundle/architecture/partition-ownership.md), [Concorrenza](okf-bundle/decisions/concurrency.md).

- [ ] **Metadati e tombstone:** metadati, scritture condizionate, tombstone e deduplica per epoca. → [cose da fare](#fare-metadati) · [decisioni](#decisioni-metadati)

  Documenti: [Metadati delle entità](okf-bundle/architecture/entity-metadata.md), [Tombstone](okf-bundle/architecture/tombstones.md), [Aggregazione delle query su più partizioni](okf-bundle/mechanisms/query-aggregation.md).

- [ ] **Migrazione:** durabilità osservabile, poi il protocollo con i suoi pezzi, il GC delle tombstone, gli scenari di guasto e il client per aree non contigue. → [cose da fare](#fare-migrazione) · [decisioni](#decisioni-migrazione)

  Documenti: [Durabilità osservabile](okf-bundle/architecture/observable-durability.md), [Migrazione di entità tra partizioni](okf-bundle/mechanisms/entity-migration.md), [Tombstone](okf-bundle/architecture/tombstones.md), [Persistenza](okf-bundle/decisions/persistence.md).

- [ ] **Letture coerenti:** versioni conservate, tick globale, lettura al tick N, rimozione differita e il client per zone contigue. → [cose da fare](#fare-letture) · [decisioni](#decisioni-letture)

  Documenti: [Conservazione delle versioni](okf-bundle/architecture/version-retention.md), [Letture coerenti su più partizioni](okf-bundle/mechanisms/consistent-reads.md), [Architettura minima del layer delle partizioni](okf-bundle/architecture/minimal-core.md).

- [ ] **Chiusura:** ribilanciamento e split, nodo gRPC, raycast e aggiornamento del bundle. → [cose da fare](#fare-chiusura) · [decisioni](#decisioni-chiusura)

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
- [x] Rimozione dinamica delle partizioni (politica di rimozione scelta alla creazione)

<a id="fare-indice"></a>
### Indice e query [↑](#blocchi)

- [x] Contratto delle query nel nodo (range e k-nearest)
- [x] Scansione lineare come primo tipo di indice, dietro l'interfaccia comune degli indici
- [x] Query di range su una partizione, con scansione lineare
- [x] Query k-nearest su una partizione, con scansione lineare
- [x] Test di correttezza degli indici, con la scansione lineare come riferimento
- [x] Modulo di benchmark (JMH) a livello di nodo, con profili rapido e completo
- [x] Scelta dell'indice alla creazione della partizione nel contratto del nodo
- [x] Generatori di dati riproducibili (seed fisso) con distribuzioni della posizione: uniforme, a cluster, hotspot, corridoi, punti coincidenti
- [x] Movimento negli aggiornamenti: spostamenti locali e teletrasporti casuali
- [x] Benchmark per operazione (costruzione, inserimento, aggiornamento, rimozione, lettura per ID, range, k-nearest) al variare di numero di entità, batch e selettività
- [x] Metriche: tempo medio, percentili di latenza, memoria per entità, tempo di costruzione
- [x] Report HTML per confrontare i risultati (grafici e rapporti rispetto a un riferimento)
- [x] Misure di riferimento con la scansione lineare (profilo rapido), salvate nel repo
- [ ] Misure di riferimento con il profilo completo, a macchina scarica
- [ ] Benchmark a livello di API
- [ ] Carico misto a tick (aggiornamenti e query per tick)
- [x] Indice spaziale minimo per partizione: grid uniforme 2D e 3D, con la dimensione della cella scelta alla creazione (`IndexConfig`)
- [ ] Misure della grid uniforme con più dimensioni di cella (25, 50, 100, 200, 400), confrontate con la scansione lineare, sulla macchina dedicata ai benchmark: `run quick --param index=UNIFORM_GRID_25,UNIFORM_GRID_50,UNIFORM_GRID_100,UNIFORM_GRID_200,UNIFORM_GRID_400` (circa 50 minuti); la misura di riferimento della scansione lineare va ripresa sulla stessa macchina
- [ ] Configurazione della partizione alla creazione nell'API (indice e k)
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
- [ ] Raycast (primo hit e tutti gli hit), dopo la decisione sulla sua forma
- [ ] Aggiornamento del bundle OKF con quanto appreso dai prototipi

<a id="fare-bassa-priorita"></a>
### Cose che si potrebbero fare, a bassa priorità [↑](#blocchi)

Non fanno parte dei blocchi: quando farle è da stabilire.

- [ ] TUI per il tool dei benchmark con JLine 3 (`jline-console-ui`): una procedura guidata a domande (profilo, indici, filtri, parametri, percentili), con la scelta dei file in `results/` per il report e l'apertura del report HTML alla fine. Si lancia come nuovo comando dello stesso jar (`./bench.sh tui`). Se serve una dashboard a schermo intero con l'avanzamento dal vivo, si può passare a TamboUI, che usa JLine.

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
- [x] Forma della query di range (box e cerchio/sfera, dietro un'interfaccia sealed estendibile)

<a id="decisioni-indice"></a>
### Indice e query [↑](#blocchi)

- [ ] Struttura dell'indice spaziale (grid, quadtree/octree, R-tree)
- [ ] Più di un indice per partizione
- [ ] Gruppi di partizioni con configurazione condivisa (indice, k, politica di rimozione), distinti dagli insiemi di partizioni da interrogare
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
- [ ] Divisione dei bit dell'ID (41/10/12 o 41/12/10): si può cambiare anche più avanti, purché il timestamp resti negli stessi bit
- [ ] Linguaggio dell'implementazione finale (C11, C++, Rust)
- [ ] Raycast su entità puntiformi (raggio di hit, estensione delle entità o altro), in base ai possibili usi
