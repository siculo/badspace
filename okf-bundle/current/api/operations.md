---
type: Design Decision
title: "Contratto dell’API corrente"
description: "Firme, risultati ed errori delle operazioni implementate: entità, metadati, query, nodi, partizioni, proprietà, tick, snapshot, query su più partizioni e salvataggi."
tags: [badspace, design, api, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-05T17:31:55Z }
---

# Contratto corrente

Queste chiamate sono implementate nel prototipo Java. Per il ruolo
degli oggetti e la sequenza d’uso, leggere prima i
[componenti dell’API](/current/api/components.md). Le sezioni seguenti
sono un riferimento da consultare per operazione.

## Coordinate come record

Le posizioni si passano come record `Point2`/`Point3`; un'entità con
la sua posizione è un record `Entity2`/`Entity3` (ID + posizione). Non
si usano parametri distinti o array separati per ogni coordinata: nel
prototipo conta la chiarezza più delle prestazioni, e con i record non
servono controlli sulla lunghezza degli array né c'è il rischio di
scambiare le coordinate.

Per test e client i record hanno metodi statici da usare con `import
static`: `point(x, y)` e `point(x, y, z)` su `Point2`/`Point3`,
`entity(id, x, y)` ed `entity(id, x, y, z)` su `Entity2`/`Entity3`.
Per gli aggiornamenti di posizione i record hanno metodi di comodo, nati
da un client di prova: `add(dx, dy)` su `Point2` (e `add(dx, dy, dz)`
su `Point3`) restituisce il punto spostato, `withPosition(p)` e
`moveBy(dx, dy)` su `Entity2`/`Entity3` l'entità con lo stesso ID e la
nuova posizione. Falliscono con `IllegalArgumentException` se una
coordinata del risultato non è finita; i limiti della partizione li
controlla la scrittura.

Lo storage delle partizioni resta in array primitivi: la conversione
dai record avviene al confine con lo storage.

## Operazioni sulle entità

| Operazione | Forma | Note |
|---|---|---|
| Inserimento | `insert(Point)`, `insertAll(List<Point>)` | Sull'handle del writer; restituisce gli ID generati, nello stesso ordine delle posizioni; `insert(x, y[, z])` è una scorciatoia |
| Lettura per ID | `get(id)`, `getAll(long[])` | Sulla view dello snapshot; `get` restituisce un `Optional`; `getAll` restituisce le entità nell'ordine degli ID e salta quelli assenti |
| Impostazione della posizione | `set(id, Point)`, `setAll(List<Entity>)` | Sull'handle del writer; un'entità presente si sposta e conserva i metadati applicativi, un'entità assente si aggiunge con lo stesso ID e senza metadati, anche dopo una rimozione; se un ID compare più volte vale l'ultima posizione. L'ID deve venire dal generatore delle entità (vedi [provenienza degli ID](/current/decisions/entity-ids.md#provenienza-degli-id-delle-entità)): una nuova entità riceve un ID nuovo solo con `insert` |
| Rimozione | `remove(id)`, `removeAll(long[])` | Sull'handle del writer, nell'interfaccia comune `PartitionWriter`; cancella anche i metadati applicativi; idempotente: un ID assente, anche perché ripetuto nell'array, si salta |
| Numero di entità | `size()` | Sulla view dello snapshot |
| Limiti | `limits()` | Nell'interfaccia comune `Partition`: le coordinate ammesse per le entità, fissate dall'indice (vedi [limiti delle coordinate](/current/decisions/spatial-indexing.md#limiti-delle-coordinate)) |
| Query di range | `findInRegion(Region)` | Sulla view dello snapshot; restituisce le entità dentro la regione o sul suo bordo, in ordine non definito |
| Query k-nearest | `findNearest(Point, count)` | Sulla view dello snapshot; restituisce al più `count` entità, ordinate per distanza dal punto e a parità di distanza per ID |
| Lettura dei metadati applicativi | `getMetadata(id)`, `getMetadataAll(long[])` | Sulla view dello snapshot, separata dalla lettura delle entità; `getMetadata` restituisce un `Optional` della mappa chiave → valore, vuoto se l'ID non è nello snapshot; `getMetadataAll` restituisce un `ApplicationMetadata` per ID nell'ordine degli ID, con la mappa vuota per un'entità senza metadati, e salta gli ID assenti |
| Scrittura dei metadati applicativi | `putMetadata(id, key, value)`, `deleteMetadata(id, key)`, `updateMetadataAll(List<MetadataWrite>)` | Sull'handle del writer, nell'interfaccia comune `PartitionWriter`; `MetadataWrite` è `Put` o `Delete` su una chiave di un'entità; le scritture valgono nell'ordine della lista, quindi per la stessa chiave vince l'ultima; confermate dallo stesso commit delle posizioni; la rimozione dell'entità ne cancella i metadati; una scrittura su un'entità assente non fa niente e non la crea, anche se una `set` successiva la aggiunge |

Le scritture stanno sull'[handle del
writer](#proprietà-e-handle-del-writer) (`PartitionWriter2`/`PartitionWriter3`),
le letture solo sulla view dello [snapshot
acquisito](#acquisizione-degli-snapshot) (`AcquiredSnapshot2`/`AcquiredSnapshot3`).
`Point`/`Entity`/`Region` stanno per `Point2`/`Entity2`/`Region2` nei
tipi 2D e per `Point3`/`Entity3`/`Region3` nei tipi 3D.

## Query su una partizione

La **regione** di una query di range è un'interfaccia `sealed` con un
record per ogni forma: `Box2` e `Circle2` in 2D, `Box3` e `Sphere3` in
3D. Il box è allineato agli assi e si definisce con l'angolo minimo e
quello massimo. Servono entrambe le forme: il cerchio/sfera per raggi
d'azione ed esplosioni, il box per viste, zone rettangolari e confini
delle partizioni. Un indice le tratta allo stesso modo: prende i
candidati dal box che contiene la forma e poi applica il test esatto.

Scelte di dettaglio:

- **Bordo incluso**: un punto sul bordo è dentro la regione, per tutte
  le forme.
- **Ordine del k-nearest**: per distanza e poi per ID, così il
  risultato è deterministico e si può confrontare tra indici diversi.
- **`count`**: con 0 il risultato è vuoto; un valore negativo è un
  errore (`IllegalArgumentException`), come un box con il minimo
  maggiore del massimo o un raggio negativo.
- **Una query per chiamata**: per ora il contratto del nodo non
  raccoglie più query in una sola chiamata; ogni chiamata riguarda una singola query su una partizione.

Le query usano l'indice spaziale della partizione. Nel prototipo ci
sono la scansione lineare, la grid uniforme e la griglia di quadtree
(vedi [Indicizzazione spaziale](/current/decisions/spatial-indexing.md)): tutti
gli indici danno gli stessi risultati e cambiano solo in velocità e
memoria. La **scansione lineare** è il riferimento: il suo risultato è
quello atteso nei test degli altri indici, e le prestazioni degli altri
indici si misurano rispetto alla sua.

## Tick dello spazio

Il [tick logico](/current/architecture/logical-time.md) avanza con
`setTick(long t)`, che il software chiama sullo spazio, di solito con il
gTick. Lo spazio tiene il suo tick e i nodi che ha creato, e lo propaga
a ognuno, anche se non ha partizioni; un nodo nuovo riceve il tick dello
spazio prima che lo spazio ne restituisca l'handle (vedi [nodi dello
spazio](#nodi-dello-spazio)).

| Operazione | Forma | Note |
|---|---|---|
| Avanzamento | `setTick(long t)` sullo spazio (`Space`) | Con `t` uguale al tick corrente non fa niente; sono ammessi i salti |
| Tick corrente | `tick()` sullo spazio e sulla partizione | Il tick della partizione può restare indietro durante la propagazione; è solo un metadato |

| Errore | Eccezione |
|---|---|
| `t` minore del tick corrente | `IllegalArgumentException` |

Nel contratto del nodo `setTick(long)` fa avanzare il tick del nodo e
lo propaga alle partizioni; il tick iniziale di un nodo è 0.

## Creazione e rimozione delle partizioni

Le partizioni si creano e si rimuovono attraverso uno **spazio**
(`Space2`/`Space3`, con l'interfaccia comune `Space`). Uno spazio riceve
alla costruzione la sua **configurazione** (`SpaceConfig`), con due
proprietà separate: il generatore degli [ID](/current/decisions/entity-ids.md)
delle entità e il generatore degli [ID delle
partizioni](/current/decisions/entity-ids.md#id-delle-partizioni), univoci tra
spazi e mai riusati. Ogni generatore va condiviso da tutti gli spazi
del processo, quindi `SpaceConfig` non ha una configurazione di
default: si costruisce con `SpaceConfig.of(entityIds, partitionIds)`, e
`withEntityIds` e `withPartitionIds` ne fanno una copia con un
generatore cambiato.

### Nodi dello spazio

Lo spazio crea i propri nodi con una **factory dei nodi**
(`PartitionNodeFactory2`/`PartitionNodeFactory3`, interfaccia funzionale
con `create()`), che il software scrive di solito come una lambda che
costruisce il nodo locale con la sua **configurazione del nodo**
(`PartitionNodeConfig`): `() -> new LocalPartitionNode2(PartitionNodeConfig.of())`,
oppure `() -> new LocalPartitionNode2(PartitionNodeConfig.of().withSaveDirectory(directory))`
per un nodo con i salvataggi. A differenza di `SpaceConfig`, ogni
componente della configurazione del nodo ha un default: nessuna
directory dei salvataggi e la modalità diretta.
Ogni chiamata della factory deve restituire un nodo nuovo, vuoto e al
tick 0, che nessun altro spazio usa; nodi usati insieme hanno directory
diverse. Lo spazio restituisce l'**handle del nodo** (`Node2`/`Node3`),
con cui le altre operazioni scelgono il nodo (vedi [architettura a
livelli](/current/architecture/layers.md#nodi)).

| Operazione | Forma | Note |
|---|---|---|
| Creazione del nodo | `createNode(factory)` sullo spazio (`Space`) | Chiama la factory fuori dal lock dello spazio, poi sotto lock porta il nodo al tick dello spazio e lo registra; da allora il nodo riceve ogni `setTick`. Il nodo resta allo spazio per tutta la sua vita |

| Errore | Eccezione |
|---|---|
| Factory nulla | `NullPointerException` |
| Factory che restituisce `null` o un nodo già dello spazio | `IllegalArgumentException`; lo spazio non registra niente |
| Errore della factory o del `setTick` del nodo | Propagato; lo spazio non registra il nodo |

Le operazioni dello spazio che ricevono un handle (creazione e
caricamento delle partizioni, elenco ed eliminazione dei salvataggi)
rifiutano l'handle di un altro spazio con `IllegalArgumentException`,
prima di generare ID, fare I/O o chiamare il nodo. Il controllo usa
l'identità dello spazio, non la sua configurazione.

### Partizioni

| Operazione | Forma | Note |
|---|---|---|
| Creazione | `createPartition(node, name)`, `createPartition(node, name, PartitionConfig)` | Crea una partizione vuota con il [nome](/current/decisions/entity-ids.md#nome-della-partizione) scelto dal software sul nodo dell'handle `node`, creato da questo spazio, al tick corrente del nodo: `creationTick()` della partizione; senza configurazione vale quella di default. La partizione restituisce il nome con `name()` |
| Rimozione | `removePartition()` sull'handle del writer | Ultima operazione del writer, con il token corrente (vedi [Rimozione della partizione](/current/architecture/partition-removal.md)) |

La **configurazione della partizione** (`PartitionConfig`) si sceglie
alla creazione e poi non cambia; la partizione la restituisce con
`config()`. Contiene:

- l'**indice** (`IndexConfig`), che fissa anche i [limiti delle
  coordinate](/current/decisions/spatial-indexing.md#limiti-delle-coordinate);
  il default è la scansione lineare;
- la **politica di rimozione**, con default `REQUIRE_EMPTY`;
- **k** (`retainedTicks`), il numero di tick consultabili per
  l'[acquisizione degli snapshot](#acquisizione-degli-snapshot): ≥ 1,
  default 1.

`PartitionConfig.defaults()` dà la configurazione di default, e
`withIndex`, `withRemovalPolicy` e `withRetainedTicks` ne fanno una
copia con un valore cambiato. Seguendo lo [sviluppo
incrementale](/process/development-approach.md#sviluppo-incrementale),
la configurazione si estende quando arrivano concetti nuovi.

La politica di rimozione dice cosa succede alle entità quando la
partizione viene rimossa:

- `REQUIRE_EMPTY`: la partizione si rimuove solo se è vuoto lo stato
  confermato del tick corrente; le sue entità vanno prima rimosse o
  spostate, e la rimozione confermata con `commit()`;
- `DISCARD_ENTITIES`: la partizione si rimuove insieme alle sue entità.

La rimozione porta la partizione nello stato **in rimozione**: dopo, il
writer non può più fare scritture né commit, e le scritture non
confermate si scartano. Al primo avanzamento del tick la partizione
diventa **ritirata**, con `removalTick`: gli snapshot dei tick
precedenti restano acquisibili finché sono nella finestra. Poi la
partizione si distrugge (vedi [Rimozione della
partizione](/current/architecture/partition-removal.md)). Il proxy e l'handle
non sanno quando avvengono il ritiro e la distruzione: lo stato lo
conosce il nodo, e gli passano le chiamate.

| Errore | Eccezione |
|---|---|
| Stato confermato non vuoto con `REQUIRE_EMPTY` (la partizione non cambia), o rimozione già chiesta | `IllegalStateException` |
| Scrittura o commit dopo la rimozione | `IllegalStateException` |
| k minore di 1 nella configurazione | `IllegalArgumentException` |
| Nome che non rispetta le regole, o ancora presente sul nodo | `IllegalArgumentException` |
| Handle del nodo di un altro spazio | `IllegalArgumentException`, senza consumare ID |

Nel contratto del nodo la creazione riceve l'ID della partizione, il
suo nome, la configurazione dell'indice (`IndexConfig`, scansione lineare se manca)
e k (1 se manca), e crea la partizione al tick corrente del nodo;
fallisce con `IllegalArgumentException` se k è minore di 1 o se l'ID o
il nome sono ancora presenti sul nodo, anche con la partizione
ritirata. Il nome il nodo lo usa solo per la persistenza; tutte le
altre operazioni del contratto usano l'ID. La
rimozione ha due operazioni, una per le partizioni con lo stato
confermato vuoto e una che scarta anche le entità. Dopo la distruzione
l'ID è sconosciuto, e le operazioni falliscono con
`UnknownPartitionException`, sottoclasse di `IllegalArgumentException`
in `common`, senza distinguere una partizione mai esistita da una
distrutta.
L'API passa al nodo l'indice e k della configurazione. Le due
operazioni di rimozione ricevono il token di scrittura.

## Proprietà e handle del writer

La semantica è nella [proprietà della
partizione](/current/architecture/partition-ownership.md). La partizione nasce
senza proprietario, alla generazione 0.

| Operazione | Forma | Note |
|---|---|---|
| Stato | `ownership()` sulla partizione | Il record `Ownership`: generazione e se la partizione ha un proprietario |
| Presa | `claim(long expectedGeneration)` sulla partizione | Restituisce `Optional<WriteToken>`: vuoto se la generazione non è più quella attesa; serve anche a passare e revocare la proprietà |
| Handle | `writer(WriteToken)` sulla partizione | Crea l'handle del writer per un token, anche ricevuto da un altro writer o da un coordinatore; non controlla la generazione, lo fa il nodo alla prima operazione che lo raggiunge: una scrittura nella modalità diretta, commit, rollback, tick dell'ultimo commit o rimozione |
| Token | `token()` sull'handle | Il token dell'handle |
| Rilascio | `close()` sull'handle; `release` nel contratto del nodo | Scarta le scritture non confermate e rilascia la proprietà; idempotente, non fa niente se la proprietà è già passata ad altri o la partizione è distrutta, quindi `close()` non fallisce mai |
| Salute | `health()` sulla partizione; `health(PartitionId)` nel contratto del nodo | `PartitionHealth.Healthy`, o `RecoveryRequired` con il resoconto del fallimento (vedi [fallimenti e salute](#fallimenti-e-salute)) |

L'**handle del writer** (`PartitionWriter2`/`PartitionWriter3`, con
l'interfaccia comune `PartitionWriter`) è `AutoCloseable` e porta il
token: ha le [scritture sulle entità](#operazioni-sulle-entità),
`commit()` e `removePartition()`. La [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura) del
nodo decide quando le scritture gli arrivano: nella modalità diretta,
quella del nodo locale, ogni scrittura va subito al nodo e il
[commit](#commit) le conferma; nella modalità con buffer, pensata per il
nodo remoto, restano nell'handle, composte per entità, e il commit le
manda tutte insieme, se un `flush()` non le ha già mandate. Le due modalità danno gli stessi commit. In un
`try`-with-resources, se il writer esce per
un'eccezione prima del commit, `close()` scarta le sue scritture come
un rollback. Le letture non sono sull'handle: il writer
legge come ogni reader, con le [acquisizioni](#acquisizione-degli-snapshot).

`claim` restituisce il token e non l'handle, perché nei passaggi di
proprietà chi chiama `claim` spesso non è chi scriverà: nel passaggio
cooperativo A chiama `claim` con la generazione del suo token e manda il
token nuovo a B, che crea l'handle con `writer(token)`. Se `claim`
restituisse l'handle, chiuderlo per errore rilascerebbe la proprietà
appena passata.

**API e contratto del nodo.** Nel contratto del nodo il token è il record
serializzabile `WriteToken`, con l'ID della partizione e la generazione,
in `common` come `Acquisition`; lo stato è il record `Ownership`. Le
operazioni sono `ownership(PartitionId)`, `claim(PartitionId, long)` e
`release(WriteToken)`, e `write`, `commit`, `rollback`, `lastCommitTick`
e le due operazioni di rimozione ricevono il token al posto dell'ID
della partizione.

| Errore | Eccezione |
|---|---|
| Scrittura, flush, commit o rimozione con un token che non è quello corrente | `OwnershipChangedException`, eccezione di dominio dedicata; la partizione non cambia. Nella modalità con buffer le scritture non raggiungono il nodo fino al flush o al commit, quindi il token si controlla lì |
| Scrittura, flush o commit su una partizione che richiede un recupero, con il token corrente | `IllegalStateException`; la partizione non cambia. Con il buffer arriva al flush o al commit |
| `claim` dalla rimozione in poi | `IllegalStateException` |
| Generazione attesa negativa | `IllegalArgumentException` |
| `writer(token)` con il token di un'altra partizione | `IllegalArgumentException` |
| Operazione su una partizione distrutta o mai esistita | `UnknownPartitionException`, come per le acquisizioni |

## Commit

Il [commit](/current/architecture/partition-commit.md) si chiude con
`commit()`, sull'handle del writer, senza numero: conferma nel tick
corrente le scritture fatte fino a quel momento, applicate all'ultimo
commit della partizione. Nella modalità diretta il nodo le ha già; con
il buffer il commit le manda al nodo in una sola chiamata, e il nodo le
applica e le conferma in un solo passo. Le scritture possono venire da
più tick; quelle fatte dopo il commit restano non confermate fino al
commit successivo. Più commit nello stesso tick sono ammessi, e l'ultimo
definisce lo snapshot del tick.

Ogni commit conferma lo stato cumulativo della partizione, compresi gli
effetti dei commit precedenti che il batch non modifica o rimuove. Solo lo
stato dell’ultimo commit del tick diventa acquisibile all’avanzamento;
gli stati intermedi non si acquisiscono. `commit()` non avanza il tick,
non aspetta il successivo e permette di continuare a scrivere nello
stesso tick: decidere se attendere spetta al software del writer (vedi
l’esempio in [Commit e tick](/current/architecture/partition-commit.md#commit-e-tick)).

| Operazione | Forma | Note |
|---|---|---|
| Commit | `commit()` sull'handle | Conferma le scritture nel tick corrente, mandandole al nodo nella modalità con buffer; dopo il successo non ci sono scritture non confermate; se il nodo rifiuta il commit le scritture restano, se il commit fallisce dopo i controlli sono scartate, nelle due modalità (vedi [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura)); un commit senza scritture è valido |
| Flush | `flush()` sull'handle | Con il buffer manda subito al nodo le scritture dell'handle, che le applica senza confermarle; il commit successivo le conferma, un rollback, un cambio di proprietà o un fallimento le scartano. Un flush rifiutato lascia le scritture, uno fallito dopo i controlli le scarta tutte, anche quelle dei flush precedenti. Nella modalità diretta non fa niente (vedi [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura)) |
| Rollback | `rollback()` sull'handle | Scarta le scritture non confermate, anche dei tick precedenti: svuota il buffer, se c'è, e chiede al nodo di tornare all'ultimo commit; tiene commit, snapshot in attesa e proprietà; è anche il recupero di una partizione che lo richiede (vedi [rollback](/current/architecture/partition-failures.md#rollback)) |
| Tick dell'ultimo commit | `lastCommitTick()` sull'handle | Il tick dell'ultimo commit; la creazione e il caricamento contano come commit al tick di creazione. Lo cambiano solo i commit del proprietario: dopo `claim` dice al nuovo writer se il tick corrente ha già un commit (vedi [un writer per tick](/current/architecture/partition-ownership.md#un-writer-per-tick)) |
| Tick corrente | `tick()` sulla partizione | Il tick della partizione; sostituisce `lastCommit()` |
| Tick di creazione | `creationTick()` sulla partizione | Il tick in cui la partizione è stata creata: il primo snapshot è `S(creationTick)` |

| Errore | Eccezione |
|---|---|
| Commit, rollback o tick dell'ultimo commit dopo la rimozione | `IllegalStateException` |
| Tick dell'ultimo commit con un token non corrente | `OwnershipChangedException` |
| Rollback il cui ripristino fallisce | `PartitionFailureException` con esito `RECOVERY_REQUIRED`, o l'`Error` così com'è |

Scelte di dettaglio:

- **Controlli**: nella modalità diretta il nodo controlla limiti, token,
  stato e salute della partizione a ogni scrittura, e una scrittura
  rifiutata non cambia la partizione. Con il buffer l'handle controlla
  subito i limiti delle posizioni, e una scrittura rifiutata non entra
  nell'handle; token, stato e salute li controlla il nodo al flush o al
  commit, quindi il writer riceve questi rifiuti da `flush()` o da
  `commit()` e non dalla scrittura, l'unica differenza tra le modalità
  nei risultati: un flush o un commit rifiutato non cambia la partizione
  e l'handle conserva le scritture (vedi [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura)).
  Un fallimento dopo i controlli, di una scrittura, di un flush o del commit,
  annulla tutte le scritture non confermate e conserva l'ultimo commit
  (vedi [fallimenti e salute](#fallimenti-e-salute)).
- **Rimozione con scritture non confermate**: è permessa, e le scritture
  non confermate si scartano: quelle dell'handle senza arrivare al nodo,
  quelle nel nodo senza commit; la politica di rimozione guarda lo stato
  confermato del tick corrente.
- **Contratto del nodo**: `writeMode()`, `write(WriteToken, List<EntityWrite>)`,
  `commit(WriteToken, List<EntityWrite>)`, `rollback(WriteToken)`,
  `lastCommitTick(WriteToken)`,
  `tick(PartitionId)` e `creationTick(PartitionId)`.

### Letture

Il commit prepara lo snapshot del tick, e l'avanzamento del tick lo
pubblica (vedi [isolamento delle
letture](/current/decisions/read-isolation.md#il-commit)). Le letture passano
**solo** dalle [acquisizioni](#acquisizione-degli-snapshot) degli
snapshot, su `Partition2`/`Partition3` e nel contratto del nodo, per
tutti: anche il writer legge così. Uno snapshot acquisito è immutabile
e leggibile da ogni thread; prima del primo avanzamento dopo la
creazione non c'è niente da acquisire.

Non ci sono letture dello stato non confermato: il writer non vede con
una query le proprie scritture, né i propri commit del tick corrente,
finché l'avanzamento del tick non li pubblica. Se deve ricordarsi dei
cambiamenti che sta facendo, tiene un suo stato per il tempo necessario
a completare la scrittura. Con il modello a tick è il doppio buffer
tipico delle simulazioni: la logica del tick T legge `S(T − 1)` e scrive
il tick T, e il risultato non dipende dall'ordine delle scritture dentro
il tick. Così c'è una sola semantica di lettura, le letture non
richiedono il token, e un writer in ritardo non legge mai le scritture
non confermate di un altro writer.

Tutte le letture di uno snapshot acquisito vedono lo stesso tick; due
acquisizioni dell'ultimo snapshot possono invece dare tick diversi.
Lo snapshot interno della partizione non esce dal nodo. `tick()` si può
chiamare da ogni thread, ma è solo un metadato.

### Acquisizione degli snapshot

La semantica è nella [conservazione degli
snapshot](/current/architecture/snapshot-retention.md#acquisizione-e-rilascio).
I reader leggono uno snapshot solo attraverso un'acquisizione.

| Operazione | Forma | Note |
|---|---|---|
| Al tick | `acquire(long tick, options)` | `S(tick)`, se `max(T − k, C) ≤ tick ≤ T − 1` e la partizione esisteva a quel tick |
| Ultimo | `acquireLatest(options)` | `S(T − 1)` |
| Lettura | `tick()`, `size()`, `get`, `getAll`, `findInRegion`, `findNearest` sulla view | Ogni lettura controlla l'acquisizione all'inizio |
| Rilascio | `close()` sulla view; `release` nel contratto del nodo | Idempotente e thread-safe |

Le opzioni dicono la scadenza, che c'è sempre: la scadenza di default
oppure una durata positiva, non maggiore della scadenza massima,
misurata dal momento dell'acquisizione con il clock monotono del nodo.

Le opzioni sono il record `AcquireOptions` in `common`, con la durata
in nanosecondi: `AcquireOptions.defaultTimeout()` e
`AcquireOptions.timeout(Duration)`. Le costanti `DEFAULT_TIMEOUT`
(1 minuto) e `MAX_TIMEOUT` (1 ora) sono nel record, che rifiuta una
durata maggiore con `IllegalArgumentException` anche quando lo crea
il nodo;
Nel contratto del nodo le selezioni ricevono sempre le opzioni;
nell'API ogni selezione ha anche la forma senza opzioni, che vale
`defaultTimeout()`.

**API e contratto del nodo.** Nell'API lo snapshot acquisito è una view
`AutoCloseable`, `AcquiredSnapshot2`/`AcquiredSnapshot3`, da usare con
`try`-with-resources; il suo `tick()` viene dall'identificatore e non
controlla l'acquisizione. Nel contratto del nodo un'acquisizione
restituisce un identificatore serializzabile, il record `Acquisition`
con l'ID della partizione, il numero dell'acquisizione e il tick
acquisito; le letture (`size`, `getAll`, `findInRegion`, `findNearest`)
ricevono l'identificatore e `release` lo chiude. Il numero lo sceglie
la partizione, parte da 1 e non si riusa. Le acquisizioni le tiene il
nodo, non il processo del reader,.

| Errore | Eccezione |
|---|---|
| Snapshot non acquisibile: tick non ancora pubblicato (≥ T), fuori dalla finestra, prima della creazione, dalla rimozione in poi | `SnapshotNotAvailableException`, eccezione di dominio dedicata, con il motivo; nessuna acquisizione creata |
| Tick negativo; durata zero o negativa | `IllegalArgumentException` |
| Lettura attraverso un'acquisizione rilasciata o scaduta | `IllegalStateException`, con lo stesso messaggio nei due casi: dopo l'eliminazione di un'acquisizione scaduta il nodo non sa più se era stata rilasciata |
| Acquisizione su una partizione distrutta o mai esistita | `UnknownPartitionException`, sottoclasse di `IllegalArgumentException`: il nodo non distingue i due casi |
| Rilascio o lettura con un identificatore mai emesso o di un'altra partizione, nel contratto del nodo | `IllegalArgumentException`; le altre acquisizioni non cambiano |
| Lettura con un identificatore di una partizione che il nodo non ha, nel contratto del nodo | `IllegalStateException`, come per un'acquisizione rilasciata; il rilascio non fa niente. Gli ID delle partizioni non si riusano, quindi il nodo non distingue una partizione mai esistita da una già distrutta |
| Lettura con un identificatore il cui tick non è quello acquisito, nel contratto del nodo | `IllegalArgumentException` |

Scelte di dettaglio:

- **Rilascio ripetuto**: il secondo rilascio della stessa acquisizione,
  il rilascio dopo la scadenza o dopo la distruzione della partizione
  non fa niente, quindi `close()` della view non fallisce mai.
- **Scadenza controllata dal nodo**: il record `Acquisition` non porta
  la scadenza e la view dell'API non la controlla. La scadenza è
  misurata con il clock del nodo, che in un altro processo non vale, e
  il nodo non può fidarsi di una scadenza ricevuta dal reader.
- **Query in corso**: una query iniziata prima del rilascio o della
  scadenza finisce e il suo risultato è valido; i dati già letti
  restano validi anche dopo il rilascio.
- **`tick()`** resta un metadato: non si usa per scegliere lo snapshot
  da acquisire, perché tra i due passi la finestra può cambiare.
- **Dopo la rimozione della partizione** si acquisiscono ancora gli
  snapshot dei tick precedenti a `removalTick` che sono nella finestra,
  e le acquisizioni già fatte restano leggibili; il nodo trova la
  partizione per ID fino alla distruzione, e l'ID non torna mai.

### Query su più partizioni

La semantica è nelle [query su più
partizioni](/current/architecture/multi-partition-queries.md). L'insieme
di snapshot `SnapshotSet2`/`SnapshotSet3` acquisisce lo snapshot dello
stesso tick su una lista di partizioni dello spazio e aggrega le query
sugli snapshot acquisiti. Lo crea lo spazio: non c'è un altro modo
pubblico di costruirlo. È `AutoCloseable`, da usare con `try`-with-resources.

| Operazione | Forma | Note |
|---|---|---|
| Acquisizione | `acquire(List<Partition2>, long tick)`, `acquire(partitions, tick, options)` su `Space2` (e `Space3`) | Acquisisce `S(tick)` su ogni partizione, nell'ordine della lista, come `acquire(tick, options)`; senza opzioni vale `defaultTimeout()`. Una lista vuota dà un insieme vuoto e completo |
| Risultato | `tick()`, `snapshots()`, `failures()`, `isComplete()` | Il tick richiesto; gli snapshot acquisiti e i fallimenti, nell'ordine della lista; `isComplete()` è vero se non ci sono fallimenti |
| Fallimento | Record `SnapshotSet2.Failure`/`SnapshotSet3.Failure` | La partizione, il motivo `reason()` e l'eccezione `error()`. Il motivo è l'enum `FailureReason` dell'API: i quattro motivi di `SnapshotNotAvailableException` e `UNKNOWN_PARTITION` per una partizione che il nodo non conosce, cioè distrutta per un handle creato dallo spazio |
| Query di range | `findInRegion(Region)` | Unione dei risultati degli snapshot acquisiti, in ordine non definito |
| Query k-nearest | `findNearest(Point, count)` | Al più `count` entità degli snapshot acquisiti, ordinate per distanza dal punto e a parità di distanza per ID |
| Rilascio | `close()` | Rilascia tutte le acquisizioni, anche se un rilascio fallisce, e poi lancia il primo errore; una seconda chiamata non fa niente |

I fallimenti non sono eccezioni della chiamata: una partizione il cui
snapshot non è disponibile non ferma le altre.

| Caso | Esito |
|---|---|
| Snapshot non acquisibile: non ancora pubblicato, fuori dalla finestra, prima della creazione, dalla rimozione in poi | Fallimento con lo stesso motivo e `SnapshotNotAvailableException` |
| Partizione distrutta | Fallimento con `UNKNOWN_PARTITION` e `UnknownPartitionException` |
| Lista o un suo elemento nulli, opzioni nulle | `NullPointerException`, prima di ogni acquisizione |
| Tick negativo, partizione di un altro spazio, partizione ripetuta nella lista | `IllegalArgumentException`, prima di ogni acquisizione: lo spazio controlla tutta la lista |
| Ogni altro errore durante le acquisizioni | Propagato, dopo il rilascio degli snapshot già acquisiti |
| `count` negativo in `findNearest` | `IllegalArgumentException`, anche senza snapshot |
| Query dopo il rilascio o la scadenza di un'acquisizione | `IllegalStateException`, come sulla view |

Non c'è un'operazione nuova nel contratto del nodo: l'insieme usa le
acquisizioni e le query sulle singole partizioni.

### Salvataggio

La semantica è nella [persistenza](/current/decisions/persistence.md#chiamate).
Le chiamate sono nell'interfaccia comune `Partition`, perché non
dipendono dal numero di dimensioni, e nel contratto del nodo, con l'ID
della partizione.

| Operazione | Forma | Note |
|---|---|---|
| Al tick | `save(long tick)` | Salva `S(tick)`, con la stessa finestra di `acquire(tick)` |
| Ultimo | `saveLatest()` | Salva `S(T − 1)`, come `acquireLatest()` |

Le chiamate sono sincrone: ritornano a scrittura finita, con il
riferimento al salvataggio, il record `SavedPartition` in `common`
(nome della partizione e tick). Il riferimento non contiene il file: dove
e come il nodo scrive è un suo dettaglio. Non ci sono opzioni di
scadenza: il nodo acquisisce con la scadenza di default e la copia,
come ogni query ammessa, finisce anche dopo la scadenza. Si salva anche
una partizione in rimozione o ritirata, finché il tick è nella
finestra; i salvataggi restano dopo la distruzione della partizione.

| Errore | Eccezione |
|---|---|
| Snapshot non acquisibile | `SnapshotNotAvailableException`, come l'acquisizione corrispondente; nessun file scritto |
| Tick negativo | `IllegalArgumentException` |
| Nodo senza destinazione dei salvataggi | `IllegalStateException` |
| Partizione distrutta o mai esistita | `UnknownPartitionException`, come per l'acquisizione |
| Errore nella scrittura del file | `UncheckedIOException`; i salvataggi già presenti non cambiano. |
| Fallimento di un salvataggio contemporaneo dello stesso snapshot, di cui si aspettava la fine | `UncheckedIOException` |

L'elenco e l'eliminazione dei salvataggi non richiedono una partizione
viva (vedi [periodicità e
conservazione](/current/decisions/persistence.md#periodicità-e-conservazione)),
quindi sono sull'interfaccia comune `Space`, con l'handle del nodo come
parametro; lo spazio delega al nodo anche se il nodo non ha ancora
partizioni, come un nodo nuovo dopo un riavvio. L'elenco non registra
niente in più: il nodo è già dello spazio dalla sua creazione. Nel
contratto del nodo hanno la stessa forma, senza il nodo.

| Operazione | Forma | Note |
|---|---|---|
| Elenco | `saves(node)` | Tutti i salvataggi della destinazione del nodo, per nome e poi per tick crescente |
| Elenco di un nome | `saves(node, name)` | I salvataggi di un nome, per tick crescente; vuoto se non ce ne sono |
| Elenco della partizione | `saves()` su `Partition` | Come `saves(node, name())` |
| Eliminazione | `deleteSaves(node, saved)` | Batch, con una lista di `SavedPartition`; un salvataggio che non c'è viene saltato |

| Errore | Eccezione |
|---|---|
| Handle del nodo di un altro spazio | `IllegalArgumentException`, senza leggere o eliminare file |
| Nodo senza destinazione dei salvataggi | `IllegalStateException` |
| Errore nella lettura della directory o nell'eliminazione | `UncheckedIOException`; con l'eliminazione, i salvataggi prima di quello che fallisce sono già eliminati |

### Caricamento

Il caricamento crea una partizione, come la creazione, quindi è
sull'interfaccia comune `Space`, con l'handle del nodo come parametro.
La semantica è nel [caricamento](/current/decisions/persistence.md#caricamento).

| Operazione | Forma | Note |
|---|---|---|
| Caricamento | `loadPartition(node, PartitionConfig, saved)` | Crea sul nodo una partizione con il contenuto del salvataggio `saved` (`SavedPartition`), il nome del salvataggio e un ID nuovo, senza proprietario; il tick di creazione è quello del nodo alla fine del caricamento, che non ferma l'avanzamento del tick; indice e finestra vengono dalla configurazione |

Nel contratto del nodo la chiamata è `loadPartition(partitionId, saved,
index, retainedTicks)`, accanto a `createPartition`, con l'ID generato
dall'API come per la creazione. Il nodo legge il salvataggio dalla sua
destinazione.

| Errore | Eccezione |
|---|---|
| Nome o ID già in uso sul nodo, finestra minore di 1 | `IllegalArgumentException`, come la creazione |
| Handle del nodo di un altro spazio | `IllegalArgumentException`, senza leggere il salvataggio |
| Posizione del salvataggio fuori dai limiti dell'indice scelto | `IllegalArgumentException` |
| Nodo senza destinazione dei salvataggi | `IllegalStateException` |
| Salvataggio assente, corrotto o con un altro numero di dimensioni | `UncheckedIOException` |

In tutti i casi di errore la partizione non viene creata.

## Contratto del nodo

Il contratto del [nodo](/current/architecture/layers.md#nodi)
(`PartitionNode2`/`PartitionNode3`) espone solo operazioni batch, per
raggruppare il lavoro su più entità in una chiamata. Il nodo dichiara
la sua [modalità di scrittura](/current/architecture/partition-commit.md#modalità-di-scrittura) con `writeMode()`. Le scritture sono
batch di modifiche, `List<EntityWrite2>` (`EntityWrite3` in 3D), che si
applicano in ordine: nella modalità diretta arrivano con
`write(WriteToken, List<EntityWrite2>)`, che le applica senza commit, una
chiamata per ogni scrittura dell'handle, e il commit è
`commit(WriteToken, List.of())`; con il buffer arrivano tutte con
`commit(WriteToken, List<EntityWrite2>)`, con al più una modifica finale
per entità. Il nodo non controlla che sia una sola: se sono di più, le
applica in ordine. Le prepara `WriteSession2`/`WriteSession3` in
`common`, che l'handle usa, nella modalità del nodo; la composizione del
buffer è descritta in [composizione per entità](/current/architecture/partition-commit.md#scritture-accumulate-nel-writer).

## Scritture tutto-o-niente

Ogni batch, di una scrittura o del commit, è tutto-o-niente: il nodo
prima controlla tutto l'input, poi lo applica, così un batch rifiutato
non cambia la partizione. È semplice grazie al [writer unico](/current/decisions/concurrency.md)
della partizione.

| Errore | Eccezione |
|---|---|
| Posizione fuori dai limiti della partizione in `insert`/`set` | `IllegalArgumentException` alla chiamata: dal nodo nella modalità diretta, dall'handle con il buffer, e il nodo la controlla di nuovo al commit |

Le coordinate `NaN` o infinite sono rifiutate già alla costruzione di
`Point2`/`Point3`, e un raggio infinito alla costruzione di
`Circle2`/`Sphere3`, con `IllegalArgumentException`.

## Fallimenti e salute

Gli errori delle tabelle precedenti sono rifiuti: arrivano prima di
qualsiasi modifica. Un’eccezione dopo i controlli, mentre il nodo applica
una scrittura o il batch o prepara il commit, è un [fallimento](/current/architecture/partition-failures.md):
la partizione annulla tutte le scritture non confermate e conserva
l’ultimo commit e lo snapshot in attesa. I tipi sono in `common`.

| Caso | Risultato per il chiamante |
|---|---|
| Fallimento con un’eccezione | `PartitionFailureException`, con il resoconto `PartitionFailure` in `failure()` e l’eccezione originale come causa |
| Fallimento con un `Error`, per esempio `OutOfMemoryError` | L’`Error` così com’è, con l’eventuale fallimento del ripristino tra le eccezioni soppresse |
| Ripristino fallito | Esito `RECOVERY_REQUIRED` nel resoconto; `health()` diventa `RecoveryRequired` e scritture e commit sono rifiutati finché `rollback()`, `claim` o `release` non ripristinano la partizione |

Il resoconto ha operazione (`WRITE` per una scrittura della modalità
diretta, `COMMIT`, anche per l’applicazione del batch, `CLAIM`,
`RELEASE`, `ROLLBACK`),
struttura (`SLOTS`, `INDEX`, `METADATA`, `UNKNOWN`), esito
(`WRITES_DISCARDED` o `RECOVERY_REQUIRED`), tick dell’ultimo commit,
causa ed eventuale fallimento del ripristino. `health()` si legge da
qualsiasi thread, anche dopo la rimozione e fino alla distruzione.
