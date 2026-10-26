# TODO del prototipo Java

<a id="blocchi"></a>
## Cose da fare, in blocchi

I blocchi sono in ordine: ognuno usa solo quanto fatto nei blocchi precedenti.

- [x] **Basi:** ID Snowflake e rimozione delle partizioni. → [cose da fare](#fare-basi)

  Documenti: [ID delle entità](okf-bundle/current/decisions/entity-ids.md), [Partizionamento del DB](okf-bundle/current/decisions/partitioning.md), [Architettura a livelli](okf-bundle/current/architecture/layers.md).

- [x] **Configurazione della partizione:** indice scelto alla creazione nell'API. Si usano gli indici così come sono oggi (scansione lineare, grid uniforme, griglia di quadtree); gli indici e i loro test sono nel [TODO degli indici](TODO-indices.md). → [cose da fare](#fare-configurazione)

  Documenti: [Indicizzazione spaziale](okf-bundle/current/decisions/spatial-indexing.md), [Contratto dell’API](okf-bundle/current/api/operations.md).

- [x] **Commit e proprietà:** un writer e più reader su una partizione: commit, indici copy-on-write, snapshot degli slot, conservazione e acquisizione degli snapshot (k nella configurazione, rilascio, scadenza), tick logico e semantica temporale degli snapshot, rimozione della partizione, writer su thread diversi, fencing token. → [cose da fare](#fare-commit)

  Documenti: [Commit della partizione](okf-bundle/current/architecture/partition-commit.md), [Tick logico](okf-bundle/current/architecture/logical-time.md), [Isolamento delle letture](okf-bundle/current/decisions/read-isolation.md), [Proprietà della partizione](okf-bundle/current/architecture/partition-ownership.md), [Concorrenza](okf-bundle/current/decisions/concurrency.md), [Conservazione degli snapshot](okf-bundle/current/architecture/snapshot-retention.md), [Rimozione della partizione](okf-bundle/current/architecture/partition-removal.md).

- [x] **Metadati:** metadati applicativi delle entità, API dei metadati, scritture condizionate. I metadati di sistema arrivano con [tombstone e migrazione](#fare-migrazione). → [cose da fare](#fare-metadati)

  Documenti: [Metadati delle entità](okf-bundle/current/architecture/entity-metadata.md), [Architettura minima](okf-bundle/current/architecture/minimal-core.md).

- [x] **Persistenza:** salvataggio periodico e caricamento del contenuto di una singola partizione, da una copia autonoma dello snapshot; misure dei costi. → [cose da fare](#fare-persistenza) · [decisioni](#decisioni-persistenza)

  Documenti: [Persistenza](okf-bundle/current/decisions/persistence.md), [Durabilità osservabile](okf-bundle/future/architecture/observable-durability.md), [Conservazione degli snapshot](okf-bundle/current/architecture/snapshot-retention.md).

- [x] **Query su più partizioni:** aggregazione dei risultati per tipo di query. → [cose da fare](#fare-query) · [decisioni](#decisioni-query)

  Documenti: [Query su più partizioni](okf-bundle/current/architecture/multi-partition-queries.md), [Contratto dell’API](okf-bundle/current/api/operations.md#query-su-più-partizioni), [Estensioni dell’aggregazione](okf-bundle/future/mechanisms/query-aggregation.md), [Entità statiche vs dinamiche](okf-bundle/future/decisions/static-vs-dynamic-entities.md).

- [ ] **Nodo gRPC:** nodo remoto dietro il contratto dei nodi. → [cose da fare](#fare-grpc) · [decisioni](#decisioni-grpc)

  Documenti: [Nodi remoti e distribuzione](okf-bundle/future/decisions/tool-type.md), [Architettura a livelli](okf-bundle/current/architecture/layers.md).

- [ ] **Ribilanciamento e split:** trasferimento dell'esclusività tra writer. → [cose da fare](#fare-ribilanciamento)

  Documenti: [Ribilanciamento e split](okf-bundle/future/mechanisms/rebalancing-and-split.md), [Proprietà della partizione](okf-bundle/current/architecture/partition-ownership.md).

- [ ] **Tombstone e migrazione:** metadati di sistema delle entità, tombstone, protocollo di spostamento delle entità tra partizioni, deduplica per epoca nelle letture. → [cose da fare](#fare-migrazione) · [decisioni](#decisioni-migrazione)

  Documenti: [Metadati delle entità](okf-bundle/future/architecture/entity-metadata.md), [Tombstone](okf-bundle/future/architecture/tombstones.md), [Migrazione di entità tra partizioni](okf-bundle/future/mechanisms/entity-migration.md), [Aggregazione delle query su più partizioni](okf-bundle/future/mechanisms/query-aggregation.md).

- [ ] **Letture coerenti:** barriera sul gTick, lettura al tick N sugli snapshot conservati, verifica e acquisizione in un passo, rimozione differita delle entità. → [cose da fare](#fare-letture) · [decisioni](#decisioni-letture)

  Documenti: [Conservazione degli snapshot](okf-bundle/current/architecture/snapshot-retention.md), [Letture coerenti su più partizioni](okf-bundle/future/mechanisms/consistent-reads.md), [Architettura minima](okf-bundle/current/architecture/minimal-core.md).

- [ ] **Gruppi di partizioni:** API costruita sopra quella che lavora sulle singole partizioni. → [cose da fare](#fare-gruppi) · [decisioni](#decisioni-gruppi)

  Documenti: [Estensioni dell’API](okf-bundle/future/decisions/api-surface.md), [Architettura a livelli](okf-bundle/current/architecture/layers.md).

- [ ] **Raycast:** primo hit e tutti gli hit. Richiede di riprendere il lavoro sugli indici. → [cose da fare](#fare-raycast) · [decisioni](#decisioni-raycast)

  Documenti: [Estensioni dell’API](okf-bundle/future/decisions/api-surface.md), [Aggregazione delle query su più partizioni](okf-bundle/future/mechanisms/query-aggregation.md), [Indicizzazione spaziale](okf-bundle/current/decisions/spatial-indexing.md).

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
- [x] Modulo `integration-tests` con i test dell'API insieme ai nodi locali e moduli dei client d'esempio in `samples` (`api-tour`, `random-updater`), al posto del modulo client
- [x] Esempi in `samples`, un modulo per ogni aspetto dell'API (`spaces-and-partitions`, `writes-and-commits`, `snapshot-reads`, `ticks-and-retention`, `acquisition-timeouts`, `ownership-handoff`, `partition-removal`) e per ogni caso d'uso (`random-updater`, `multi-writer`, `writer-failover`, `lagged-reader`), al posto di `api-tour`

<a id="fare-basi"></a>
### Basi [↑](#blocchi)

- [x] Generatore di ID in stile Snowflake a 64 bit
- [x] Rimozione dinamica delle partizioni (politica di rimozione scelta alla creazione)

<a id="fare-configurazione"></a>
### Configurazione della partizione [↑](#blocchi)

- [x] Contratto delle query nel nodo (range e k-nearest)
- [x] Scelta dell'indice alla creazione della partizione nel contratto del nodo
- [x] Limiti delle coordinate per partizione, fissati dall'indice (`IndexConfig.limits()`, `limits()` su partizione e nodo): scritture fuori dai limiti rifiutate, solo valori finiti anche nelle query → [Limiti delle coordinate](okf-bundle/current/decisions/spatial-indexing.md#limiti-delle-coordinate)
- [x] Configurazione della partizione alla creazione nell'API (`PartitionConfig`): indice e politica di rimozione

<a id="fare-commit"></a>
### Commit e proprietà [↑](#blocchi)

Le letture isolate dai commit non completi seguono la [decisione](okf-bundle/current/decisions/read-isolation.md): snapshot a ogni commit, slot copiati per intero, indici persistenti copy-on-write. Si arriva in più passi:

- [x] Commit minimo con contatore monotono: il commit incrementa solo il contatore, le scritture restano visibili subito (`commit(n)` e `lastCommit()`, vedi la [forma delle chiamate](okf-bundle/current/api/operations.md#commit))
- [x] Indici persistenti copy-on-write che usano il contatore: un nodo si modifica sul posto solo se è del commit corrente, altrimenti si copia; test che una radice tenuta da parte non cambia dopo un commit (basta un solo thread)
- [x] Slot nello snapshot: coordinate copiate a ogni commit, ID e mappa ID → slot copiati solo con inserimenti o rimozioni (per ora tabella copiata, non HAMT)
- [x] Pubblicazione atomica della radice `(slot_N, indice_N)`: i reader vedono solo commit completi
- [x] Conservazione e acquisizione degli snapshot, in passi (semantica in [Conservazione degli snapshot](okf-bundle/current/architecture/snapshot-retention.md#acquisizione-e-rilascio), forma in [Contratto dell’API](okf-bundle/current/api/operations.md#acquisizione-degli-snapshot)); ogni passo per 2D e 3D, con i test:
  - [x] k nella configurazione della partizione: snapshot passati conservati oltre al corrente, ≥ 0, default 0, con un metodo che fa una copia della configurazione con k cambiato; passato dallo spazio alla creazione nel contratto del nodo e nel nodo locale. Test: valore negativo rifiutato, default 0
  - [x] Catalogo degli snapshot per partizione nel service: al commit registra lo stesso snapshot pubblicato dallo storage (niente copie di slot o indici) e tiene acquisibili gli ultimi k + 1, snapshot vuoto del commit 0 compreso; nessun reader vede un commit nuovo con il catalogo vecchio. `lastCommit()` letto dallo snapshot pubblicato, così è visibile agli altri thread. Test: k = 0, e k = 2 con commit 100, 103, 107, 110
  - [x] Acquisizione e rilascio nel contratto del nodo (identificatore serializzabile, `acquireLatest`, `acquirePrevious`, `acquireAtOrBefore`, `acquireExact`, `release` idempotente, eccezione dedicata per lo snapshot non disponibile) e view `AutoCloseable` nell'API; ammissione atomica delle query, con il riferimento rilasciato anche quando la query fallisce. Test: offset fuori dalla finestra, due reader sullo stesso snapshot, snapshot fuori dalla finestra leggibile finché acquisito e poi non più acquisibile, acquisizione fallita senza pin, doppio `close()`, identificatore non valido che non libera altre acquisizioni, read-your-writes del writer invariato. I test del catalogo si riscrivono con gli offset e `snapshots()` del catalogo, usato solo dai test, si toglie
  - [x] `lastSnapshot()` fuori dal proxy e dal contratto del nodo, sostituita dall'acquisizione nei test; poi eliminata anche dallo storage nella revisione dei test: il service usa il catalogo
  - [x] Test di concorrenza con latch o barriere, senza sleep: commit durante una query, acquisizione concorrente alla rotazione della finestra, rilascio concorrente a una query; slot e indice sempre dello stesso commit, writer mai bloccato da una query lenta
  - [x] Scadenza opzionale delle acquisizioni: `noTimeout()` di default, durata positiva con clock monotono sostituibile nei test, durata zero o negativa rifiutata, durata troppo grande come nessuna scadenza, acquisizioni scadute eliminate a ogni evento della partizione. Test con clock finto: scadenza prima della prima query, durante una query ammessa, rilascio dopo la scadenza, nuova acquisizione distinta
  - [x] Fine degli snapshot (semantica in [Vita di uno snapshot](okf-bundle/current/architecture/snapshot-retention.md#vita-di-uno-snapshot)): per ogni snapshot un contatore degli utilizzi (acquisizioni valide e query ammesse) e il segno "fuori dalla finestra"; lo snapshot finisce una sola volta, all'evento che toglie l'ultima condizione (commit, rilascio, scadenza, fine dell'ultima query); ammissione e acquisizione aggiungono un utilizzo solo se lo snapshot non è finito (e l'acquisizione solo se è nella finestra: altrimenti tutte le selezioni scelgono di nuovo dal catalogo nuovo); lo storage conta gli snapshot non finiti. Test: fine a ognuno degli eventi, snapshot nella finestra che non finisce al rilascio, query che tiene vivo uno snapshot rilasciato e fuori dalla finestra, snapshot uscito dalla finestra non più acquisibile, query del nodo che restituiscono lo snapshot anche quando falliscono, corse tra rilascio e ammissione e tra commit e acquisizione
  - [x] Scadenza obbligatoria (semantica in [Scadenza](okf-bundle/current/architecture/snapshot-retention.md#scadenza)): niente più acquisizioni senza scadenza, `AcquireOptions.defaultTimeout()` al posto di `noTimeout()` e nelle forme senza opzioni dell'API; costanti per la scadenza di default (1 minuto) e la scadenza massima (1 ora), durata maggiore rifiutata. Test: scadenza massima accettata e durata maggiore rifiutata, scadenza di default e scadenza massima che scadono anche loro
  - [x] Rimozione della partizione in due fasi (semantica in [Rimozione della partizione](okf-bundle/current/architecture/snapshot-retention.md#rimozione-della-partizione)): `SpaceConfig` costruito con metodi statici (`of(entityIds, partitionIds)`, `withEntityIds`, `withPartitionIds`, senza default) al posto del generatore nel costruttore degli spazi, con il generatore degli ID delle partizioni al posto del contatore di `Space2`, mai riusati; lo storage dice al nodo con il valore di ritorno quando non ha più snapshot non finiti, senza riferimenti al nodo; segno di rimozione (niente scritture, commit né nuove acquisizioni, finestra chiusa) con la partizione segnata che resta nella mappa del nodo per ID e rimozione effettiva dal nodo alla fine dell'ultimo snapshot; letture e rilascio per ID anche sulla partizione segnata; `createPartition` rifiutato con un ID ancora presente, anche se segnato; acquisizioni scadute delle partizioni segnate cercate a ogni evento del nodo. Test per `removePartition` e `dropPartition`: acquisizioni leggibili dopo il segno fino al rilascio o alla scadenza, rimozione effettiva al rilascio, alla fine dell'ultima query e alla scadenza trovata da un evento di un'altra partizione del nodo (reader che muore senza rilasciare: con la scadenza obbligatoria la rimozione finisce sempre), `createPartition` rifiutato con l'ID di una partizione segnata, ID delle partizioni diversi tra due spazi, `close()` della view dopo la rimozione, rimozione concorrente ad acquisizioni. Oggi gli ID delle partizioni vengono da un contatore per spazio e `close()` della view fallisce dopo la rimozione
- [x] Semantica temporale degli snapshot, in passi (semantica in [Tick logico](okf-bundle/current/architecture/logical-time.md), [Conservazione degli snapshot](okf-bundle/current/architecture/snapshot-retention.md) e [Rimozione della partizione](okf-bundle/current/architecture/partition-removal.md), forma in [Contratto dell’API](okf-bundle/current/api/operations.md#tick-dello-spazio)); ogni passo per 2D e 3D, con i test:
  - [x] Tick nel nodo e nella partizione: `setTick(t)` nel contratto del nodo, propagato alle partizioni (rifiutato all'indietro con `IllegalArgumentException`, idempotente con lo stesso valore, salti ammessi), tick iniziale del nodo 0, `tick()` e `creationTick()` con la partizione creata al tick corrente del nodo; ordine tra `createPartition` e `setTick` del nodo; nell'API `Space.setTick` e `tick()`, con lo spazio che tiene i nodi su cui ha creato partizioni e chiama `setTick` su un nodo nuovo prima della creazione. Commit e acquisizioni non cambiano ancora. Test: avanzamento, idempotenza, rifiuto all'indietro, salto, creazione a un tick diverso da 0, nodo nuovo allineato dallo spazio, creazione concorrente a un avanzamento
  - [x] Commit senza numero e catalogo per tick: `commit()` prepara lo snapshot del tick corrente (più commit nello stesso tick, vale l'ultimo; le scritture non confermate passano al tick dopo) e `setTick` lo pubblica; catalogo con le voci (snapshot, tick da cui vale) e `S(n)` per ogni tick, salti compresi; finestra di k tick (`retainedTicks`, ≥ 1, default 1, `withRetainedTicks`) con l'uscita dalla finestra all'avanzamento; `acquire(tick, options)` e `acquireLatest` = `S(T − 1)` al posto delle quattro selezioni; `Acquisition` e view con il tick; `SnapshotNotAvailableException` con `reason()`; sequenza dei commit interna per `index.commit`; snapshot non finiti contati alla pubblicazione; tolti `commit(n)` e `lastCommit()`; client aggiornati (`Main`, `SampleRandomUpdater`). Test: esempi del bundle (T = 110 con k = 5; `setTick(105)` da 102), `S(T)` mai acquisibile, niente da acquisire prima del primo avanzamento, creazione al tick 0 con `S(0)` dopo `setTick(1)`, tick senza commit con lo stesso contenuto, due commit nello stesso tick, scritture non confermate al tick dopo, motivi del fallimento; test di scadenza, fine degli snapshot e concorrenza riscritti con i tick
  - [x] Concorrenza tra avanzamento e writer: lock per partizione su scritture, commit, `setTick` e `remove()`; i reader restano senza lock. Test con latch o barriere, senza sleep: commit concorrente a `setTick` che finisce in `S(T)` o in `S(t)`, mai a metà; acquisizione concorrente all'avanzamento; `setTick` da un thread diverso dal writer; writer mai bloccato da un reader
  - [x] Rimozione della partizione con gli stati: `ACTIVE`, `REMOVAL_PENDING`, `RETIRED`, `DESTROYED`; `remove()` ultima operazione del writer (dopo niente scritture né commit, secondo `remove()` rifiutato), che scarta le scritture non confermate, con `REQUIRE_EMPTY` sullo stato confermato del tick; ritiro al primo avanzamento con `removalTick`; acquisizioni nuove sui tick `n < removalTick` nella finestra; distruzione con `T − k ≥ removalTick` e nessun utilizzo; `setTick` come punto di pulizia delle acquisizioni scadute per tutte le partizioni, al posto della scansione delle partizioni segnate a ogni evento del nodo; proxy che non blocca più le acquisizioni dopo la rimozione; errore generico per un ID sconosciuto. Test: esempi del bundle (`removalTick` = 105 da 102; con k = 5 `S(104)` al tick 109 e niente al 110), `setTick` idempotente che non applica la rimozione, scritture e commit dopo `remove()` rifiutati, `REQUIRE_EMPTY` con entità tolte ma non confermate, acquisizioni che tengono in vita gli snapshot dopo `T − k ≥ removalTick`, `createPartition` rifiutato con l'ID di una partizione ritirata, rimozione concorrente ad acquisizioni e avanzamenti
- [x] Writer su thread diversi per partizioni diverse, con pool dei buffer degli snapshot, in passi (semantica in [Isolamento delle letture](okf-bundle/current/decisions/read-isolation.md#gli-slot) e [Concorrenza](okf-bundle/current/decisions/concurrency.md)); ogni passo per 2D e 3D, con i test:
  - [x] Writer di partizioni diverse su thread diversi, senza pool: niente stato condiviso tra le partizioni nelle scritture e nei commit (lock e sequenza dei commit per partizione; mappa delle partizioni, tick e lock del tick del nodo; nodi dello spazio e generatori degli ID); il bundle dice che la sequenza dei commit è del nodo, nel codice è della partizione: allineare il bundle. Test con latch o barriere, senza sleep: più writer sulle partizioni dello stesso nodo, ognuno sul suo thread, con `setTick` da un altro thread e reader concorrenti, e ogni snapshot con tutte e sole le scritture confermate della sua partizione; un `setTick` fermo sul lock di una partizione non blocca i writer delle altre; inserimenti concorrenti su partizioni diverse con ID tutti distinti
  - [x] Pool dei buffer delle coordinate, con la copia completa: un pool per partizione, sicuro tra i thread perché uno snapshot finisce su qualsiasi thread; lo snapshot degli slot con la sua dimensione, separata dalla lunghezza degli array; il buffer torna al pool alla fine dello snapshot, e subito per lo snapshot preparato e sostituito da un commit dopo nello stesso tick, mai pubblicato; il writer prende un buffer dal pool o ne alloca uno nuovo, senza mai aspettare, e scarta i buffer troppo piccoli; pool svuotato alla distruzione della partizione; ID e tabella ID → slot restano fuori dal pool. Test: buffer riusato dopo la fine dello snapshot; mai riusato mentre lo snapshot è nella finestra, acquisito o in una query; reader lento con il writer che alloca un buffer nuovo; contenuto corretto dopo il riuso; snapshot sostituito nello stesso tick riusato; fine dello snapshot su un altro thread concorrente a un commit
  - [x] Copia delle sole pagine cambiate: slot divisi in pagine di dimensione fissa, con la sequenza dei commit dell'ultima modifica di ogni pagina (movimenti, inserimenti, rimozioni con lo spostamento degli slot); il buffer sa di quale commit è lo snapshot che contiene, e al riuso copia solo le pagine modificate dopo; un buffer nuovo fa la copia completa; un commit senza movimenti condivide le coordinate con lo snapshot precedente, come gli ID, e il buffer condiviso torna al pool alla fine dell'ultimo snapshot che lo usa. Test: dopo sequenze di scritture qualsiasi, contenuto uguale a quello della copia completa; con poche entità in movimento si copiano solo le loro pagine; partizione senza movimenti che non copia niente; buffer condiviso che non torna al pool finché uno snapshot lo usa
- [x] Misura del costo per commit della copia degli slot e dei nodi dell'indice, con diverse frazioni di entità in movimento e reader concorrenti, e costo di acquisizione e rilascio; la ripetizione dei benchmark degli indici con gli snapshot copy-on-write è nel [TODO degli indici](TODO-indices.md):
  - [x] Benchmark `CommitBenchmark` (solo 2D, come gli altri): `commitTick` (spostamenti, commit, avanzamento del tick) contro `movesWithoutCommit` (gli stessi spostamenti senza commit), e `acquireAndRelease`; parametri `movingFraction` e `readers` (thread non misurati che acquisiscono, interrogano e rilasciano l'ultimo snapshot); fuori dal run di default, scelti con `--include`; piano `plans/commit-cost.json` con tre indici, due distribuzioni e due dimensioni
  - [x] Misure con il piano `plans/commit-cost.json` sulla macchina dedicata ai benchmark, poi i risultati nel bundle ([Costi](okf-bundle/current/decisions/read-isolation.md#costi))
- [x] Proprietà della partizione con fencing token, in passi (semantica in [Proprietà della partizione](okf-bundle/current/architecture/partition-ownership.md), forma in [Contratto dell’API](okf-bundle/current/api/operations.md#proprietà-e-handle-del-writer)); ogni passo per 2D e 3D, con i test:
  - [x] Letture solo sugli snapshot (forma in [Letture](okf-bundle/current/api/operations.md#letture)): tolti `get`, `getAll`, `findInRegion`, `findNearest` e `size()` da `Partition2`/`Partition3` e dall'interfaccia comune, e le letture e `size` con l'ID della partizione dal contratto del nodo; le letture restano solo sulla view dello snapshot e con l'`Acquisition`. Test, test di integrazione e client d'esempio (`ApiTour`, `RandomUpdater`) riscritti con le acquisizioni, e con un loro stato dove devono ricordarsi delle scritture; tolto il test read-your-writes del writer
  - [x] Stato di proprietà nel nodo: record `Ownership` (generazione, posseduta) e `WriteToken` (ID della partizione, generazione) in `common`; `ownership(PartitionId)`, `claim(PartitionId, long)` che restituisce `Optional<WriteToken>` e `release(WriteToken)` nel contratto del nodo e nel nodo locale; partizione creata libera alla generazione 0; `claim` e `release` sotto il lock della partizione, con la generazione che cresce a ogni cambio e le scritture non confermate scartate; `release` idempotente; `claim` rifiutato dalla rimozione in poi. Le scritture non usano ancora il token. Test: `claim` sulla partizione libera, `claim` con una generazione vecchia o futura che fallisce e non cambia lo stato, passaggio, revoca, rilascio e rilascio ripetuto, scritture non confermate scartate a `claim` e a `release` con i commit precedenti confermati, generazione attesa negativa rifiutata, `claim` dopo `remove()` rifiutato, errore generico su una partizione distrutta
  - [x] Token obbligatorio nel contratto del nodo: `insertAll`, `updateAll`, `removeAll`, `commit`, `removePartition` e `dropPartition` ricevono il `WriteToken` al posto dell'ID della partizione, controllato sotto il lock; `OwnershipChangedException` per un token che non è quello corrente, senza cambiare la partizione; `setTick` e acquisizioni non cambiano. L'API usa per ora un token preso alla creazione, finché non arriva l'handle. Test: scrittura, commit e rimozione con un token vecchio rifiutati; operazione del vecchio writer prima del cambio confermata, dopo rifiutata. Test di concorrenza con latch o barriere, senza sleep: commit concorrente a `claim` (confermato o rifiutato, mai a metà), due `claim` sulla stessa generazione con un solo vincitore, `release` concorrente alle scritture, `setTick` concorrente a `claim`
  - [x] Handle del writer nell'API: `PartitionWriter2`/`PartitionWriter3` con l'interfaccia comune `PartitionWriter`, `AutoCloseable`, con le scritture, `commit()`, `removePartition()` e `token()`; `ownership()`, `claim(long)` e `writer(WriteToken)` sulla partizione; scritture e `commit()` tolti dalla partizione; `Space.removePartition(partition)` tolto; `close()` che rilascia, idempotente e che non fallisce mai. Test: passaggio cooperativo con il token mandato a un altro writer, revoca da un coordinatore con il vecchio handle che fallisce, `close()` dopo un'eccezione che scarta le scritture non confermate, `close()` dopo il passaggio o la distruzione, `writer(token)` con il token di un'altra partizione rifiutato; client d'esempio e test di integrazione aggiornati

<a id="fare-metadati"></a>
### Metadati [↑](#blocchi)

- [x] Metadati applicativi delle entità: storage, snapshot di ogni commit e contratto del nodo (`updateMetadataAll` con `MetadataWrite`, `getMetadataAll` con `ApplicationMetadata`; `HashTrie` spostata in `badspace.service.persistent`)
- [x] API dei metadati (lettura e scrittura dei metadati applicativi): `putMetadata`, `deleteMetadata` e `updateMetadataAll` sull'handle del writer, `getMetadata` e `getMetadataAll` sulla view dello snapshot; client d'esempio `entity-metadata`
- [x] Scritture condizionate (compare-and-set) sui metadati applicativi: `ConditionalWrite2`/`ConditionalWrite3` con `MetadataCondition` (`ValueIs`, `Absent`), `updateIf` nel contratto del nodo e sull'handle del writer, `compareAndSetMetadata` sull'handle

<a id="fare-persistenza"></a>
### Persistenza [↑](#blocchi)

- [x] Copia autonoma dello snapshot dentro il nodo, per il salvataggio (ID, posizioni e metadati): il nodo, come un reader interno, acquisisce, copia prima della scadenza e rilascia; la copia non dipende più dalla vita dello snapshot e non passa dall'API
- [x] Salvataggio nel nodo su una destinazione configurata (una directory): un file binario per salvataggio in una directory per partizione (`partitions/<ID>/<tick>.bsave`), little-endian con CRC32, scritto con file temporaneo, sincronizzazione e rename atomico da un oggetto per ogni salvataggio (`PartitionSave2`/`PartitionSave3`, con la lettura del file); temporanei rimasti eliminati da una funzione di pulizia del nodo; salvataggi dello stesso tick che producono lo stesso file
- [x] Chiamate sincrone `save(tick)` e `saveLatest()` sulla partizione dell'API (interfaccia comune `Partition`) e nel contratto del nodo, con il riferimento `SavedPartition` (partizione e tick) come esito o un errore, senza fermare il writer e senza opzioni di scadenza: la copia è una query ammessa, che finisce anche dopo la scadenza
- [x] Nome della partizione scelto dal software alla creazione (`createPartition(node, name, …)`, `name()` sulla partizione, nome nel contratto del nodo con le regole e l'unicità sul nodo); `SavedPartition`, directory e intestazione dei file dei salvataggi con il nome al posto dell'ID; aggiornare il sample `partition-saves`
- [x] Elenco ed eliminazione dei salvataggi nel nodo e nell'API, per nome e non legati a una partizione viva, come meccanismi generici senza politiche di conservazione: `saves()`, `saves(name)` e `deleteSaves(saves)` batch del nodo; nell'API sullo spazio con il nodo e `saves()` sulla partizione
- [x] Caricamento con `loadPartition(node, config, saved)`: partizione creata nel nodo con il nome del salvataggio e un ID nuovo, già con il contenuto (stessi ID, posizioni e metadati), indici ricostruiti dagli inserimenti, contenuto confermato al tick di creazione, nessun proprietario, indice e finestra scelti da chi carica; metadati senza reinterpretazione
- [x] Esempio in `samples` con un thread che salva ogni N tick mentre il writer continua, conserva gli ultimi salvataggi e carica una partizione
- [x] Misure separate di tempo di copia, scrittura e caricamento (ricostruzione degli indici compresa), dimensione dei dati salvati e memoria aggiuntiva della copia, in 2D e 3D: benchmark `PersistenceBenchmark2`/`PersistenceBenchmark3` e `PersistenceSizes`, piani `persistence-cost-quick`/`-full`, campagna del 2026-10-24 sulla macchina dedicata (vedi [misure](okf-bundle/current/decisions/persistence.md#misure))

Durabilità osservabile e recupero coerente dei nodi dopo un crash sono rimandati: questo blocco ricrea il contenuto di una singola partizione.

<a id="fare-query"></a>
### Query su più partizioni [↑](#blocchi)

- [x] Insieme di snapshot `SnapshotSet2`/`SnapshotSet3` (`AutoCloseable`): acquisizione di `S(N)` sulle partizioni di una lista di handle, fallimenti come esito (`SnapshotNotAvailableException` e `UnknownPartitionException`) e ogni altro errore propagato dopo il rilascio, controllo del tick condiviso con il nodo (`RetentionWindow.checkAcquiredTick`)
- [x] `UnknownPartitionException` (sottoclasse di `IllegalArgumentException`, in `common`) nel contratto del nodo per le partizioni sconosciute o distrutte
- [x] Aggregazione delle query su più partizioni (range e k-nearest) sull'insieme di snapshot
- [x] Più partizioni con indici diversi (statiche e dinamiche) interrogate insieme: test di integrazione contro una partizione unica ed esempio `multi-partition-queries`

<a id="fare-grpc"></a>
### Nodo gRPC [↑](#blocchi)

- [ ] Nodo remoto via gRPC
- [ ] Salvataggio asincrono nel contratto del nodo (avvio e poi interrogazione dello stato), se una chiamata sincrona lunga pesa sulla chiamata remota e sul suo timeout (vedi [persistenza](okf-bundle/current/decisions/persistence.md#chiamate))
- [ ] Durata della scadenza nel record `Acquisition` e controllo anticipato della scadenza nella view dell'API, se serve a risparmiare chiamate remote destinate a fallire (oggi controlla solo il nodo)

<a id="fare-ribilanciamento"></a>
### Ribilanciamento e split [↑](#blocchi)

- [ ] Ribilanciamento tramite trasferimento dell'esclusività
- [ ] Split di una partizione
- [ ] Deduplica per ID nell'aggregazione durante lo split (l'epoca si aggiunge con la [migrazione](#fare-migrazione))

<a id="fare-migrazione"></a>
### Tombstone e migrazione [↑](#blocchi)

- [ ] Metadati di sistema delle entità nello strato service (epoca, stato, destinazione), in sola lettura per il software, nello snapshot di ogni commit
- [ ] Lettura dei metadati di sistema nell'API dei metadati e scritture condizionate anche sui metadati di sistema
- [ ] Tombstone alla rimozione
- [ ] Regola di inserimento con confronto dell'epoca
- [ ] Lettura ed eliminazione delle tombstone
- [ ] Puntatore di inoltro nelle letture per ID
- [ ] Durabilità osservabile (ultimo tick persistito), necessaria alle conferme della migrazione
- [ ] Recupero coerente dei nodi dopo un crash: tempo logico, proprietà delle partizioni e coordinamento con gli altri nodi
- [ ] Protocollo di migrazione tra partizioni
- [ ] Entità congelate durante la migrazione
- [ ] Reinvio, ACK, ACK "superato" e messaggio REMOVED
- [ ] Primitiva "sposta da A a B" nell'API
- [ ] GC delle tombstone (conferma spontanea e verifica su richiesta)
- [ ] Scenari di guasto della migrazione (crash di A, crash di B, catene, rimbalzi)
- [ ] Deduplica per ID ed epoca nell'aggregazione (le copie in uscita perdono)

<a id="fare-letture"></a>
### Letture coerenti [↑](#blocchi)

- [ ] Barriera sul gTick (il tick è già nel livello base, con `Space.setTick`)
- [ ] Lettura al tick N: scelta di N e nuovo tentativo se N esce dalla finestra di una partizione; l'acquisizione esatta di `S(N)` su ogni partizione e il rilascio ci sono già con l'[insieme di snapshot](okf-bundle/current/architecture/multi-partition-queries.md)
- [ ] Verifica e acquisizione in un passo (`tryAcquire` con un risultato sealed, se serve) e, se ancora servono, `partitionExistsAt` e `canAcquireSnapshot`; l'insieme di snapshot dà già i fallimenti senza eccezione
- [ ] Rimozione differita delle entità come alternativa alle letture al tick N

<a id="fare-gruppi"></a>
### Gruppi di partizioni [↑](#blocchi)

- [ ] API dei gruppi di partizioni sopra l'API delle singole partizioni

<a id="fare-raycast"></a>
### Raycast [↑](#blocchi)

- [ ] Raycast negli indici (da aggiungere al [TODO degli indici](TODO-indices.md) quando si arriva qui)
- [ ] Raycast nel nodo e nell'API (primo hit e tutti gli hit), con l'aggregazione su più partizioni

<a id="fare-client"></a>
### Client di test (tema trasversale) [↑](#blocchi)

Non è deciso quanti e quali client servono: si costruiscono man mano. Scenari possibili:

- [ ] Aree non contigue (utile dalla migrazione)
- [ ] Zone contigue con confini (utile dalle letture coerenti)
- [ ] Strategia mista per tipo di entità (utile dalle [query su più partizioni](#fare-query))

<a id="fare-bassa-priorita"></a>
### Cose che si potrebbero fare, a bassa priorità [↑](#blocchi)

Non fanno parte dei blocchi: quando farle è da stabilire. Quelle degli indici sono nel [TODO degli indici](TODO-indices.md).

- [ ] Funzione di copia autonoma di uno snapshot esposta al software, per tenerlo oltre la scadenza massima delle acquisizioni (vedi [conservazione degli snapshot](okf-bundle/current/architecture/snapshot-retention.md))
- [ ] Array di oggetti negli indici sostituiti da array paralleli di primitivi, come chiedono le [linee guida](prototype/coding-guidelines.md) (principio 2): `Node[] children` in `GridQuadtreeIndex2` e `GridOctreeIndex3`, `Object[]` ed `Entry[]` in `HashTrie`. Va fatto indipendentemente dal [TODO degli indici](TODO-indices.md)


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
- [x] Tick logico nel livello base: tick dello spazio, del nodo e della partizione, `setTick` monotono con salti, tick comune a tutte le partizioni di uno spazio (sostituisce "commit distinto dal tick")
- [x] Commit senza numero nel tick corrente: più commit per tick (vale l'ultimo), scritture non confermate portate al tick dopo, snapshot di ogni tick pubblicato all'avanzamento del tick
- [x] k configurabile per partizione: numero di tick consultabili, ≥ 1, default 1 (sostituisce gli snapshot passati oltre al corrente, default 0)
- [x] Acquisizione e rilascio degli snapshot: acquisizione al tick e ultimo (`S(T − 1)`), atomica rispetto all'avanzamento del tick, pin fuori dalla finestra, scadenza obbligatoria con clock monotono (durata zero rifiutata, senza rinnovo), acquisizioni leggibili dopo la rimozione della partizione
- [x] Rimozione della partizione: `remove()` ultima operazione del writer, ritiro al primo avanzamento del tick, snapshot dei tick precedenti acquisibili nella finestra, distruzione quando sono usciti tutti e nessuno li usa
- [x] Scritture condizionate al posto delle transazioni
- [x] Regole delle tombstone ed epoca
- [x] Protocollo di migrazione (outbox con consumer idempotente)
- [x] GC delle tombstone su conferma, non a TTL
- [x] Ribilanciamento e split tramite trasferimento dell'esclusività
- [x] Forma della query di range (box e cerchio/sfera, dietro un'interfaccia sealed estendibile)
- [x] Forma delle chiamate per il commit e il tick (`commit()` senza numero, `setTick` sullo spazio, `tick()`, `creationTick()`; sostituisce `commit(n)`)
- [x] Limiti delle coordinate fissati dall'indice: tetto globale 2^60, 2^30 celle per lato per gli indici a celle, query senza limiti, solo valori finiti (niente `NaN` né infiniti)
- [x] Struttura dei metadati applicativi: chiavi `String`, valori `long`, nessun limite sul numero e sulla lunghezza delle chiavi per ora; nel prototipo una `HashTrie` copy-on-write, con lettura per ID separata da quella delle entità → [Metadati delle entità](okf-bundle/current/architecture/entity-metadata.md#metadati-applicativi)
- [x] Forma delle scritture condizionate: batch tutto-o-niente con condizioni "chiave = valore" o "chiave assente" e scritture di metadati e posizioni; una condizione falsa restituisce le condizioni fallite, senza eccezione → [Metadati delle entità](okf-bundle/current/architecture/entity-metadata.md#forma)

<a id="decisioni-persistenza"></a>
### Persistenza [↑](#blocchi)

- [x] Perimetro iniziale: persistenza del contenuto della singola partizione da una copia autonoma dello snapshot; caricamento in una nuova partizione, durabilità osservabile e recupero coerente dei nodi rimandati
- [x] Destinazione dei dati persistiti: il nodo che gestisce la partizione scrive su una destinazione configurata sul nodo; l'API comanda il salvataggio e riceve l'esito
- [x] Formato dei dati persistiti: binario, un file per salvataggio, scritto in modo atomico (temporaneo, sincronizzazione, rename)
- [x] Frequenza e attivazione dei salvataggi periodici: decise dal software, senza scheduler nell'API o nel nodo
- [x] Forma delle chiamate: il nodo salva come un reader interno; `save(tick)` e `saveLatest()` sincroni con il riferimento come esito; caricamento con `loadPartition(node, config, saved)`; asincrono rimandato al nodo gRPC e alla durabilità osservabile
- [x] Salvataggi interrotti e conservazione: un salvataggio interrotto non lascia file validi né tocca gli altri; elenco ed eliminazione nel nodo, politica di conservazione del software

<a id="decisioni-query"></a>
### Query su più partizioni [↑](#blocchi)

- [x] Forma delle chiamate: insieme di snapshot dello stesso tick, scelto dal chiamante, su una lista di handle (vuota ammessa); i fallimenti di disponibilità sono un esito e non un'eccezione, senza modalità "tutti o nessuno", perché la decisione è del chiamante; un solo tick per tutte le partizioni; nessun registro dei nomi nello spazio → [Query su più partizioni](okf-bundle/current/architecture/multi-partition-queries.md)

<a id="decisioni-grpc"></a>
### Nodo gRPC [↑](#blocchi)

- [ ] Chiamate a lotti ed errori remoti nel contratto dei nodi
- [ ] Nodi condivisi tra spazi e ID globali delle partizioni

<a id="decisioni-migrazione"></a>
### Tombstone e migrazione [↑](#blocchi)

- [ ] Forma della gestione delle tombstone nell'API
- [ ] Trasporto dei messaggi di migrazione (fornito dall'API o lasciato al software)
- [ ] Forma delle chiamate per la migrazione
- [ ] Copie in uscita con posizione diversa nell'aggregazione
- [ ] Mappa ID → partizione offerta dall'API come aiuto opzionale

<a id="decisioni-letture"></a>
### Letture coerenti [↑](#blocchi)

- [ ] Lettura al tick N o rimozione differita delle entità come default
- [ ] Servizio del gTick e della barriera nella forma a cluster
- [ ] Forma della verifica e acquisizione in un passo: solo il motivo nell'eccezione o anche `tryAcquire` senza eccezione; `partitionExistsAt` e `canAcquireSnapshot` ancora necessarie

<a id="decisioni-gruppi"></a>
### Gruppi di partizioni [↑](#blocchi)

- [ ] Gruppi di partizioni con configurazione condivisa (indice, k, politica di rimozione), distinti dagli insiemi di partizioni da interrogare

<a id="decisioni-raycast"></a>
### Raycast [↑](#blocchi)

- [ ] Raycast su entità puntiformi (raggio di hit, estensione delle entità o altro), in base ai possibili usi

<a id="decisioni-lungo-termine"></a>
### Senza blocco, a lungo termine [↑](#blocchi)

- [ ] Strategie di partizionamento pronte come componenti opzionali
- [ ] Unità di misura (fissa o scelta dal software)
- [ ] Determinismo dei calcoli tra macchine diverse
- [ ] Tipo delle coordinate nell'implementazione finale
- [ ] Divisione dei bit dell'ID (41/10/12 o 41/12/10): si può cambiare anche più avanti, purché il timestamp resti negli stessi bit
- [ ] Linguaggio dell'implementazione finale (C11, C++, Rust)
- [ ] Filtri sui metadati nelle query, con i relativi indici sui metadati, fuori dal livello base → [Metadati delle entità](okf-bundle/future/architecture/entity-metadata.md#estensioni-possibili)
- [ ] Più indici per una partizione (non è deciso se supportarli) → [Indice per partizione](okf-bundle/current/decisions/spatial-indexing.md#indice-per-partizione)
