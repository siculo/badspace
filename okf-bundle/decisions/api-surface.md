---
type: Design Decision
title: API da esporre
description: La superficie API pubblica di BADSPACE ha già un elenco di operazioni di base su entità, metadati, partizioni e commit; il prototipo definisce la forma delle operazioni sulle entità (record per le coordinate, operazioni batch, scritture tutto-o-niente), delle query di range e k-nearest su una partizione della creazione e rimozione delle partizioni con la loro configurazione (indice e politica di rimozione), del tick (`setTick` sullo spazio), del commit (`commit()` senza numero, nel tick corrente), della proprietà della partizione (`ownership`, `claim` con la generazione attesa, handle del writer `AutoCloseable` con il token per scritture, commit e rimozione), delle letture (solo attraverso l'acquisizione degli snapshot, al tick con scadenza obbligatoria) e dei metadati applicativi (lettura per ID sulla view dello snapshot, scritture sull'handle del writer), mentre il resto è da definire; il partizionamento fissa già partizioni esplicite, generazione degli ID, aggregazione, scritture condizionate e API dei metadati.
tags: [badspace, design, api, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-19T12:00:00Z }
---

# Stato

Le operazioni di base sono individuate (insert, remove, update
posizione, query di range, k-nearest, raycasting e le operazioni su
metadati, partizioni e commit). Il prototipo definisce la forma delle
operazioni di scrittura e lettura per ID sulle entità, delle query di
range e k-nearest su una partizione, della creazione e rimozione delle
partizioni, del tick, del commit, della proprietà della partizione e
delle letture sugli snapshot conservati, e della lettura e scrittura
dei metadati applicativi (vedi [Forma delle chiamate nel
prototipo](#forma-delle-chiamate-nel-prototipo)), che resta
rivalutabile; la forma delle altre operazioni, raycast compreso, è
ancora da definire.

# Operazioni di base dell'API comune

Oltre ai meccanismi, l'API comune espone le operazioni di base su
entità, partizioni e commit. L'elenco non è chiuso: l'API potrà
evolvere aggiungendo il supporto a ulteriori meccanismi.

| Ambito | Operazione | Note |
|---|---|---|
| Entità | Creazione, modifica e rimozione | Su una o più entità per chiamata; gli [ID](/decisions/entity-ids.md) li genera l'API |
| Entità | Lettura per ID | Uno o più ID, su uno snapshot acquisito; la partizione la indica il software, che tiene la mappa ID → partizione |
| Entità | Ricerche per posizione | Range, k-nearest, raycast, su snapshot acquisiti di una o più partizioni (vedi [aggregazione](/mechanisms/query-aggregation.md)) |
| Metadati | Lettura e modifica | [Metadati](/architecture/entity-metadata.md) applicativi; quelli di sistema sono in sola lettura |
| Metadati | Scritture condizionate | Compare-and-set; sostituiscono le transazioni tra partizioni |
| Partizioni | Creazione e rimozione dinamica | Con scelta dell'indice e di k; è la base della scalabilità; la rimozione diventa effettiva al tick successivo (vedi [rimozione della partizione](/architecture/partition-removal.md)) |
| Partizioni | Proprietà | Leggere lo stato, prendere la proprietà con la generazione attesa (anche per passarla o revocarla) e rilasciarla; le scritture portano il [fencing token](/architecture/partition-ownership.md) (vedi [proprietà e handle del writer](#proprietà-e-handle-del-writer)) |
| Tick | `setTick(t)` | Fa avanzare il [tick logico](/architecture/logical-time.md) dello spazio, dei suoi nodi e delle loro partizioni; pubblica gli snapshot dei tick completati |
| Commit | `commit()` | Conferma le scritture nel tick corrente della partizione (vedi [commit](/architecture/partition-commit.md)); per un server a frame, alla fine del lavoro del tick |
| Commit | Ultimo tick persistito | [Durabilità osservabile](/architecture/observable-durability.md) |
| Commit | Acquisizione e rilascio degli snapshot | Al tick n, ultimo (`S(T − 1)`); con scadenza obbligatoria (vedi [acquisizione degli snapshot](#acquisizione-degli-snapshot)) |
| Commit | Lettura al tick N | Acquisizione al tick N su ogni partizione, con k abbastanza grande per il ritardo tra le partizioni (vedi [letture coerenti](/mechanisms/consistent-reads.md)) |

La mappa ID → partizione spetta al software, che decide dove creare le
entità e quando spostarle. È una scelta naturale quando tutte le entità
di un tipo stanno in una partizione; se ne usa più d'una, è il software
ad aggiornare la mappa a ogni migrazione.

# Forma delle chiamate nel prototipo

Forma provvisoria, da rivalutare con i prossimi prototipi (vedi
[approccio di sviluppo](/process/development-approach.md)).

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
dai record avviene al confine e costa poco rispetto a una chiamata
remota. Se servisse, si potrà aggiungere un overload con un array per
ogni coordinata senza cambiare il resto.

## Operazioni sulle entità

| Operazione | Forma | Note |
|---|---|---|
| Inserimento | `insert(Point)`, `insertAll(List<Point>)` | Sull'handle del writer; restituisce gli ID generati, nello stesso ordine delle posizioni; `insert(x, y[, z])` è una scorciatoia |
| Lettura per ID | `get(id)`, `getAll(long[])` | Sulla view dello snapshot; `get` restituisce un `Optional`; `getAll` restituisce le entità nell'ordine degli ID e salta quelli assenti |
| Aggiornamento | `update(id, Point)`, `updateAll(List<Entity>)` | Sull'handle del writer; cambia la posizione; se un ID compare più volte vale l'ultima posizione |
| Rimozione | `remove(id)`, `removeAll(long[])` | Sull'handle del writer, nell'interfaccia comune `PartitionWriter` |
| Numero di entità | `size()` | Sulla view dello snapshot |
| Limiti | `limits()` | Nell'interfaccia comune `Partition`: le coordinate ammesse per le entità, fissate dall'indice (vedi [limiti delle coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)) |
| Query di range | `findInRegion(Region)` | Sulla view dello snapshot; restituisce le entità dentro la regione o sul suo bordo, in ordine non definito |
| Query k-nearest | `findNearest(Point, count)` | Sulla view dello snapshot; restituisce al più `count` entità, ordinate per distanza dal punto e a parità di distanza per ID |
| Lettura dei metadati applicativi | `getMetadata(id)`, `getMetadataAll(long[])` | Sulla view dello snapshot, separata dalla lettura delle entità; `getMetadata` restituisce un `Optional` della mappa chiave → valore, vuoto se l'ID non è nello snapshot; `getMetadataAll` restituisce un `ApplicationMetadata` per ID nell'ordine degli ID, con la mappa vuota per un'entità senza metadati, e salta gli ID assenti |
| Scrittura condizionata | `updateIf(ConditionalWrite)`, `compareAndSetMetadata(id, key, expected, value)` | Sull'handle del writer: `updateIf` in `PartitionWriter2`/`PartitionWriter3`, perché porta posizioni, `compareAndSetMetadata` nell'interfaccia comune. `ConditionalWrite2`/`ConditionalWrite3` contiene condizioni (`MetadataCondition`: `ValueIs` o `Absent` su una chiave di un'entità), posizioni nuove di entità esistenti e scritture dei metadati; si applica solo se tutte le condizioni sono vere sullo stato del writer, scritture non confermate comprese, in un solo passo; restituisce le condizioni false nel loro ordine, lista vuota se applicata. `compareAndSetMetadata` restituisce `true` se ha scritto |
| Scrittura dei metadati applicativi | `putMetadata(id, key, value)`, `deleteMetadata(id, key)`, `updateMetadataAll(List<MetadataWrite>)` | Sull'handle del writer, nell'interfaccia comune `PartitionWriter`; `MetadataWrite` è `Put` o `Delete` su una chiave di un'entità; le scritture valgono nell'ordine della lista, quindi per la stessa chiave vince l'ultima; confermate dallo stesso commit delle posizioni; la rimozione dell'entità ne cancella i metadati |

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
Si possono aggiungere altre forme (capsula, frustum, poligono) senza
cambiare il contratto del nodo, ma solo le implementazioni.

Scelte di dettaglio:

- **Bordo incluso**: un punto sul bordo è dentro la regione, per tutte
  le forme.
- **Ordine del k-nearest**: per distanza e poi per ID, così il
  risultato è deterministico e si può confrontare tra indici diversi e
  nell'[aggregazione](/mechanisms/query-aggregation.md).
- **`count`**: con 0 il risultato è vuoto; un valore negativo è un
  errore (`IllegalArgumentException`), come un box con il minimo
  maggiore del massimo o un raggio negativo.
- **Una query per chiamata**: per ora il contratto del nodo non
  raccoglie più query in una sola chiamata; la scelta rientra nella
  forma delle chiamate per le query su più partizioni, ancora aperta.

Le query usano l'indice spaziale della partizione. Nel prototipo ci
sono la scansione lineare, la grid uniforme e la griglia di quadtree
(vedi [Indicizzazione spaziale](/decisions/spatial-indexing.md)): tutti
gli indici danno gli stessi risultati e cambiano solo in velocità e
memoria. La **scansione lineare** è il riferimento: il suo risultato è
quello atteso nei test degli altri indici, e le prestazioni degli altri
indici si misurano rispetto alla sua. Il raycast non c'è ancora: per
entità puntiformi la sua forma dipende dagli usi e va decisa a parte.

## Tick dello spazio

Il [tick logico](/architecture/logical-time.md) avanza con
`setTick(long t)`, che il software chiama sullo spazio, di solito con il
gTick. Lo spazio tiene il suo tick e i nodi su cui ha creato
partizioni, e lo propaga a ognuno; su un nodo nuovo chiama `setTick`
prima di creare la partizione.

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
proprietà separate: il generatore degli [ID](/decisions/entity-ids.md)
delle entità e il generatore degli [ID delle
partizioni](/decisions/entity-ids.md#id-delle-partizioni), univoci tra
spazi e mai riusati. Ogni generatore va condiviso da tutti gli spazi
del processo, quindi `SpaceConfig` non ha una configurazione di
default: si costruisce con `SpaceConfig.of(entityIds, partitionIds)`, e
`withEntityIds` e `withPartitionIds` ne fanno una copia con un
generatore cambiato. Come `PartitionConfig`, si estende quando arrivano
altre impostazioni dello spazio.

| Operazione | Forma | Note |
|---|---|---|
| Creazione | `createPartition(node, name)`, `createPartition(node, name, PartitionConfig)` | Crea una partizione vuota con il [nome](/decisions/entity-ids.md#nome-della-partizione) scelto dal software sul [nodo](/architecture/layers.md#nodi) scelto dal software, che deve servire solo questo spazio, al tick corrente: `creationTick()` della partizione; senza configurazione vale quella di default. La partizione restituisce il nome con `name()` |
| Rimozione | `removePartition()` sull'handle del writer | Ultima operazione del writer, con il token corrente (vedi [Rimozione della partizione](/architecture/partition-removal.md)) |

La **configurazione della partizione** (`PartitionConfig`) si sceglie
alla creazione e poi non cambia; la partizione la restituisce con
`config()`. Contiene:

- l'**indice** (`IndexConfig`), che fissa anche i [limiti delle
  coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate);
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
partizione](/architecture/partition-removal.md)). Il proxy e l'handle
non sanno quando avvengono il ritiro e la distruzione: lo stato lo
conosce il nodo, e gli passano le chiamate.

| Errore | Eccezione |
|---|---|
| Stato confermato non vuoto con `REQUIRE_EMPTY` (la partizione non cambia), o rimozione già chiesta | `IllegalStateException` |
| Scrittura o commit dopo la rimozione | `IllegalStateException` |
| k minore di 1 nella configurazione | `IllegalArgumentException` |
| Nome che non rispetta le regole, o ancora presente sul nodo | `IllegalArgumentException` |

Nel contratto del nodo la creazione riceve l'ID della partizione, il
suo nome, la configurazione dell'indice (`IndexConfig`, scansione lineare se manca)
e k (1 se manca), e crea la partizione al tick corrente del nodo;
fallisce con `IllegalArgumentException` se k è minore di 1 o se l'ID o
il nome sono ancora presenti sul nodo, anche con la partizione
ritirata. Il nome il nodo lo usa solo per la persistenza; tutte le
altre operazioni del contratto usano l'ID. La
rimozione ha due operazioni, una per le partizioni con lo stato
confermato vuoto e una che scarta anche le entità. Dopo la distruzione
l'ID è sconosciuto, e le operazioni falliscono con un errore generico,
senza distinguere una partizione mai esistita da una distrutta.
L'API passa al nodo l'indice e k della configurazione. Le due
operazioni di rimozione ricevono il token di scrittura.

## Proprietà e handle del writer

La semantica è nella [proprietà della
partizione](/architecture/partition-ownership.md). La partizione nasce
senza proprietario, alla generazione 0.

| Operazione | Forma | Note |
|---|---|---|
| Stato | `ownership()` sulla partizione | Il record `Ownership`: generazione e se la partizione ha un proprietario |
| Presa | `claim(long expectedGeneration)` sulla partizione | Restituisce `Optional<WriteToken>`: vuoto se la generazione non è più quella attesa; serve anche a passare e revocare la proprietà |
| Handle | `writer(WriteToken)` sulla partizione | Crea l'handle del writer per un token, anche ricevuto da un altro writer o da un coordinatore; non controlla la generazione, lo fa il nodo alla prima operazione |
| Token | `token()` sull'handle | Il token dell'handle |
| Rilascio | `close()` sull'handle; `release` nel contratto del nodo | Scarta le scritture non confermate; idempotente, non fa niente se la proprietà è già passata ad altri o la partizione è distrutta, quindi `close()` non fallisce mai |

L'**handle del writer** (`PartitionWriter2`/`PartitionWriter3`, con
l'interfaccia comune `PartitionWriter`) è `AutoCloseable` e porta il
token: ha le [scritture sulle entità](#operazioni-sulle-entità),
`commit()` e `removePartition()`. In un `try`-with-resources, se il
writer esce per un'eccezione prima del commit, `close()` scarta le sue
scritture come un rollback. Le letture non sono sull'handle: il writer
legge come ogni reader, con le [acquisizioni](#acquisizione-degli-snapshot).

`claim` restituisce il token e non l'handle, perché nei passaggi di
proprietà chi chiama `claim` spesso non è chi scriverà: nel passaggio
cooperativo A chiama `claim` con la generazione del suo token e manda il
token nuovo a B, che crea l'handle con `writer(token)`. Se `claim`
restituisse l'handle, chiuderlo per errore rilascerebbe la proprietà
appena passata.

**Locale e remoto.** Nel contratto del nodo il token è il record
serializzabile `WriteToken`, con l'ID della partizione e la generazione,
in `common` come `Acquisition`; lo stato è il record `Ownership`. Le
operazioni sono `ownership(PartitionId)`, `claim(PartitionId, long)` e
`release(WriteToken)`, e le scritture, `commit` e le due operazioni di
rimozione ricevono il token al posto dell'ID della partizione.

| Errore | Eccezione |
|---|---|
| Scrittura, commit o rimozione con un token che non è quello corrente | `OwnershipChangedException`, eccezione di dominio dedicata; la partizione non cambia |
| `claim` dalla rimozione in poi | `IllegalStateException` |
| Generazione attesa negativa | `IllegalArgumentException` |
| `writer(token)` con il token di un'altra partizione | `IllegalArgumentException` |
| Operazione su una partizione distrutta o mai esistita | Errore generico, come per le acquisizioni |

## Commit

Il [commit](/architecture/partition-commit.md) si chiude con
`commit()`, sull'handle del writer, senza numero: conferma
le scritture fatte fino a quel momento nel tick corrente della
partizione. Più commit nello stesso tick sono ammessi, e l'ultimo
definisce lo snapshot del tick; le scritture non confermate passano al
tick successivo.

Ogni commit conferma lo stato cumulativo del writer, compresi gli effetti
dei commit precedenti che non sono stati modificati o rimossi. Solo lo
stato dell’ultimo commit del tick diventa acquisibile all’avanzamento;
gli stati intermedi non si acquisiscono. `commit()` non avanza il tick,
non aspetta il successivo e permette di continuare a scrivere nello
stesso tick: decidere se attendere spetta al software del writer (vedi
l’esempio in [Commit e tick](/architecture/partition-commit.md#commit-e-tick)).

| Operazione | Forma | Note |
|---|---|---|
| Commit | `commit()` sull'handle | Conferma lo stato del writer nel tick corrente; un commit senza scritture è valido |
| Tick corrente | `tick()` sulla partizione | Il tick della partizione; sostituisce `lastCommit()` |
| Tick di creazione | `creationTick()` sulla partizione | Il tick in cui la partizione è stata creata: il primo snapshot è `S(creationTick)` |

| Errore | Eccezione |
|---|---|
| Commit dopo la rimozione | `IllegalStateException` |

Scelte di dettaglio:

- **Scritture fallite**: le scritture sono tutto-o-niente, quindi una
  scrittura fallita non cambia nemmeno lo stato del writer.
- **Rimozione con scritture non confermate**: è permessa, e le scritture
  si scartano; la politica di rimozione guarda lo stato confermato del
  tick corrente.
- **Contratto del nodo**: `commit(WriteToken)`, `tick(PartitionId)` e
  `creationTick(PartitionId)`. Il commit di più partizioni in una sola
  chiamata, per ridurre le chiamate remote, è rimandato ai gruppi di
  partizioni o al nodo gRPC.

### Letture

Il commit prepara lo snapshot del tick, e l'avanzamento del tick lo
pubblica (vedi [isolamento delle
letture](/decisions/read-isolation.md#il-commit)). Le letture passano
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
snapshot](/architecture/snapshot-retention.md#acquisizione-e-rilascio).
I reader leggono uno snapshot solo attraverso un'acquisizione.

| Operazione | Forma | Note |
|---|---|---|
| Al tick | `acquire(long tick, options)` | `S(tick)`, se `max(T − k, C) ≤ tick ≤ T − 1` e la partizione esisteva a quel tick |
| Ultimo | `acquireLatest(options)` | `S(T − 1)` |
| Lettura | `tick()`, `size()`, `get`, `getAll`, `findInRegion`, `findNearest` sulla view | Ogni lettura controlla l'acquisizione all'inizio |
| Rilascio | `close()` sulla view; `release` nel contratto del nodo | Idempotente e thread-safe |

Le selezioni `acquirePrevious`, `acquireAtOrBefore` e `acquireExact`
della prima forma non servono più: con uno snapshot per ogni tick,
"al tick o prima" ed "esatto" coincidono con `acquire(tick)`.

Le opzioni dicono la scadenza, che c'è sempre: la scadenza di default
oppure una durata positiva, non maggiore della scadenza massima,
misurata dal momento dell'acquisizione con il clock monotono del nodo.

Le opzioni sono il record `AcquireOptions` in `common`, con la durata
in nanosecondi: `AcquireOptions.defaultTimeout()` e
`AcquireOptions.timeout(Duration)`. Le costanti `DEFAULT_TIMEOUT`
(1 minuto) e `MAX_TIMEOUT` (1 ora) sono nel record, che rifiuta una
durata maggiore con `IllegalArgumentException` anche quando lo crea
il nodo; la scadenza massima diventerà una configurazione del nodo.
Nel contratto del nodo le selezioni ricevono sempre le opzioni;
nell'API ogni selezione ha anche la forma senza opzioni, che vale
`defaultTimeout()`.

**Locale e remoto.** Nell'API lo snapshot acquisito è una view
`AutoCloseable`, `AcquiredSnapshot2`/`AcquiredSnapshot3`, da usare con
`try`-with-resources; il suo `tick()` viene dall'identificatore e non
controlla l'acquisizione. Nel contratto del nodo un'acquisizione
restituisce un identificatore serializzabile, il record `Acquisition`
con l'ID della partizione, il numero dell'acquisizione e il tick
acquisito; le letture (`size`, `getAll`, `findInRegion`, `findNearest`)
ricevono l'identificatore e `release` lo chiude. Il numero lo sceglie
la partizione, parte da 1 e non si riusa. Le acquisizioni le tiene il
nodo, non il processo del reader, e la semantica è la stessa in locale
e attraverso gRPC.

| Errore | Eccezione |
|---|---|
| Snapshot non acquisibile: tick non ancora pubblicato (≥ T), fuori dalla finestra, prima della creazione, dalla rimozione in poi | `SnapshotNotAvailableException`, eccezione di dominio dedicata, con il motivo (vedi [punti aperti](/architecture/snapshot-retention.md#punti-aperti)); nessuna acquisizione creata |
| Tick negativo; durata zero o negativa | `IllegalArgumentException` |
| Lettura attraverso un'acquisizione rilasciata o scaduta | `IllegalStateException`, con lo stesso messaggio nei due casi: dopo l'eliminazione di un'acquisizione scaduta il nodo non sa più se era stata rilasciata |
| Acquisizione su una partizione distrutta o mai esistita | Errore generico: il nodo non distingue i due casi |
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
  il nodo non può fidarsi di una scadenza ricevuta dal reader. Un
  controllo anticipato nella view risparmierebbe solo una chiamata
  remota destinata a fallire: si valuta con il nodo gRPC.
- **Query in corso**: una query iniziata prima del rilascio o della
  scadenza finisce e il suo risultato è valido; i dati già letti
  restano validi anche dopo il rilascio.
- **`tick()`** resta un metadato: non si usa per scegliere lo snapshot
  da acquisire, perché tra i due passi la finestra può cambiare.
- **Dopo la rimozione della partizione** si acquisiscono ancora gli
  snapshot dei tick precedenti a `removalTick` che sono nella finestra,
  e le acquisizioni già fatte restano leggibili; il nodo trova la
  partizione per ID fino alla distruzione, e l'ID non torna mai.

### Salvataggio

La semantica è nella [persistenza](/decisions/persistence.md#chiamate).
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
| Partizione distrutta o mai esistita | Errore generico, come per l'acquisizione |
| Errore nella scrittura del file | `UncheckedIOException`; i salvataggi già presenti non cambiano. Un'eccezione dedicata si valuta con il nodo gRPC |

## Differenze nel codice attuale

- Il [nome della partizione](/decisions/entity-ids.md#nome-della-partizione)
  non c'è ancora: la creazione non lo riceve, la partizione non ha
  `name()`, e `SavedPartition`, le directory e l'intestazione dei file
  dei salvataggi usano ancora l'ID della partizione.

Per il resto il codice segue la forma descritta sopra per tick, commit,
proprietà e handle del writer, letture, salvataggi e rimozione delle
partizioni.

## Contratto del nodo

Il contratto del [nodo](/architecture/layers.md#nodi)
(`PartitionNode2`/`PartitionNode3`) espone solo le operazioni batch,
per ridurre il numero di chiamate remote quando tra l'API e i nodi ci
sarà il confine gRPC. Nell'API le operazioni su una sola entità sono
batch di un elemento.

## Scritture tutto-o-niente

Ogni scrittura batch è tutto-o-niente: il nodo prima valida tutto
l'input, poi lo applica, così una scrittura che fallisce non cambia la
partizione. È semplice grazie al [writer unico](/decisions/concurrency.md)
della partizione.

| Errore | Eccezione |
|---|---|
| ID inesistente in `update`/`remove`, in una scrittura dei metadati o in una scrittura condizionata, anche solo in una condizione | `NoSuchElementException` |
| ID già presente o duplicato in `insert`, ID duplicato in `remove` | `IllegalArgumentException` |
| Posizione fuori dai limiti della partizione in `insert`/`update`/`updateIf` | `IllegalArgumentException` |

Le coordinate `NaN` o infinite sono rifiutate già alla costruzione di
`Point2`/`Point3`, e un raggio infinito alla costruzione di
`Circle2`/`Sphere3`, con `IllegalArgumentException`.

Le [scritture condizionate](/architecture/entity-metadata.md#forma)
seguono la stessa regola: il batch si applica solo se tutte le
condizioni sono vere. Una condizione falsa non è un errore: la
partizione non cambia e la chiamata restituisce le condizioni fallite,
senza eccezione.

# Gruppi di partizioni

Punto aperto. Un **gruppo di partizioni** ha una configurazione
condivisa (indice, k, politica di rimozione), così non va ripetuta per
ogni partizione. È distinto dall'insieme di partizioni da interrogare
in una query, che il software sceglie caso per caso.

L'API dei gruppi si costruisce **sopra** l'API che lavora sulle singole
partizioni: le implementazioni di quest'ultima non ragionano sui
gruppi. Nel prototipo i gruppi vengono dopo i meccanismi su più
partizioni.

# Vincoli dal partizionamento

Il [partizionamento del DB](/decisions/partitioning.md) e l'[architettura
a livelli](/architecture/layers.md) fissano già alcuni elementi:

- **Partizioni esplicite**: una scrittura indica la partizione di
  destinazione, una query l'insieme di partizioni da interrogare.
- **Generazione degli [ID](/decisions/entity-ids.md)** a carico
  dell'API comune.
- **Creazione e configurazione delle partizioni**, incluso l'indice da
  usare per ciascuna e il [nodo](/architecture/layers.md#nodi) che la
  ospita, scelto dal software.
- **[Aggregazione](/mechanisms/query-aggregation.md)** dei risultati di
  query su più partizioni.
- **Commit e scritture condizionate** del livello base (vedi
  [architettura minima](/architecture/minimal-core.md)).
- **[API dei metadati](/architecture/entity-metadata.md#api-dei-metadati)**.
- Primitiva di comodo **"sposta da A a B"**, che implementa la
  [migrazione](/mechanisms/entity-migration.md).

Le responsabilità di dettaglio dell'API comune e la forma delle
chiamate restano da definire.

# Correlati

- Il [tipo delle coordinate](/decisions/coordinate-type.md) (`double`
  nei prototipi) e il [supporto a 2D e 3D](/decisions/2d-3d-support.md)
  (`Point2`/`Point3` e interfaccia comune) danno forma ai record delle
  coordinate.
- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  e [indicizzazione spaziale](/decisions/spatial-indexing.md)
  vincolano ciò che l'API può esporre in modo efficiente.
