---
type: Design Decision
title: API da esporre
description: La superficie API pubblica di BADSPACE ha già un elenco di operazioni di base su entità, metadati, partizioni e commit; il prototipo definisce la forma delle operazioni sulle entità (record per le coordinate, operazioni batch, scritture tutto-o-niente), delle query di range e k-nearest su una partizione della creazione e rimozione delle partizioni con la loro configurazione (indice e politica di rimozione), del commit (`commit(n)` con un numero scelto dal software, strettamente crescente) e delle letture dei reader (acquisizione con scadenza obbligatoria, lettura e rilascio delle versioni conservate), mentre il resto è da definire; il partizionamento fissa già partizioni esplicite, generazione degli ID, aggregazione, scritture condizionate e API dei metadati.
tags: [badspace, design, api, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T14:00:00Z }
---

# Stato

Le operazioni di base sono individuate (insert, remove, update
posizione, query di range, k-nearest, raycasting e le operazioni su
metadati, partizioni e commit). Il prototipo definisce la forma delle
operazioni di scrittura e lettura per ID sulle entità, delle query di
range e k-nearest su una partizione, della creazione e rimozione delle
partizioni, del commit e delle letture dei reader sull'ultimo commit
(vedi [Forma delle chiamate nel
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
| Entità | Lettura per ID | Uno o più ID; la partizione la indica il software, che tiene la mappa ID → partizione |
| Entità | Ricerche per posizione | Range, k-nearest, raycast, su una o più partizioni (vedi [aggregazione](/mechanisms/query-aggregation.md)) |
| Metadati | Lettura e modifica | [Metadati](/architecture/entity-metadata.md) applicativi; quelli di sistema sono in sola lettura |
| Metadati | Scritture condizionate | Compare-and-set; sostituiscono le transazioni tra partizioni |
| Partizioni | Creazione e rimozione dinamica | Con scelta dell'indice e di k; è la base della scalabilità |
| Partizioni | Proprietà | Acquisire, rilasciare e trasferire l'esclusività di scrittura con il [fencing token](/architecture/partition-ownership.md) |
| Commit | `commit(n)` | Chiude il [commit](/architecture/partition-commit.md) corrente con il numero n, scelto dal software; per un server a frame, alla fine del tick |
| Commit | Ultimo commit persistito | [Durabilità osservabile](/architecture/observable-durability.md) |
| Commit | Acquisizione e rilascio delle versioni | Ultima, precedente, al tick N o prima, esatta; con scadenza obbligatoria (vedi [acquisizione delle versioni](#acquisizione-delle-versioni)) |
| Commit | Lettura al tick N | Acquisizione al tick N o prima su ogni partizione; k ≥ 1 se il writer può essere avanti (vedi [conservazione delle versioni](/architecture/version-retention.md)) |

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
| Inserimento | `insert(Point)`, `insertAll(List<Point>)` | Restituisce gli ID generati, nello stesso ordine delle posizioni; `insert(x, y[, z])` è una scorciatoia |
| Lettura per ID | `get(id)`, `getAll(long[])` | `get` restituisce un `Optional`; `getAll` restituisce le entità nell'ordine degli ID e salta quelli assenti |
| Aggiornamento | `update(id, Point)`, `updateAll(List<Entity>)` | Cambia la posizione; se un ID compare più volte vale l'ultima posizione |
| Rimozione | `remove(id)`, `removeAll(long[])` | Nell'interfaccia comune `Partition`, insieme a `size()` |
| Limiti | `limits()` | Nell'interfaccia comune `Partition`: le coordinate ammesse per le entità, fissate dall'indice (vedi [limiti delle coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)) |
| Query di range | `findInRegion(Region)` | Restituisce le entità dentro la regione o sul suo bordo, in ordine non definito |
| Query k-nearest | `findNearest(Point, count)` | Restituisce al più `count` entità, ordinate per distanza dal punto e a parità di distanza per ID |

`Point`/`Entity`/`Region` stanno per `Point2`/`Entity2`/`Region2` in
`Partition2` e per `Point3`/`Entity3`/`Region3` in `Partition3`.

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
| Creazione | `createPartition(node)`, `createPartition(node, PartitionConfig)` | Crea una partizione vuota sul [nodo](/architecture/layers.md#nodi) scelto dal software, che deve servire solo questo spazio; senza configurazione vale quella di default |
| Rimozione | `removePartition(partition)` | La chiama il writer della partizione; dopo la rimozione la partizione non si può più usare |

La **configurazione della partizione** (`PartitionConfig`) si sceglie
alla creazione e poi non cambia; la partizione la restituisce con
`config()`. Oggi contiene:

- l'**indice** (`IndexConfig`), che fissa anche i [limiti delle
  coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate);
  il default è la scansione lineare;
- la **politica di rimozione**, con default `REQUIRE_EMPTY`;
- **k** (`retainedPastVersions`), il numero di versioni passate
  conservate oltre alla corrente, per l'[acquisizione delle
  versioni](#acquisizione-delle-versioni): ≥ 0, default 0.

`PartitionConfig.defaults()` dà la configurazione di default, e
`withIndex`, `withRemovalPolicy` e `withRetainedPastVersions` ne fanno
una copia con un valore cambiato. Seguendo lo [sviluppo
incrementale](/process/development-approach.md#sviluppo-incrementale),
la configurazione si estende quando arrivano concetti nuovi.

La politica di rimozione dice cosa succede alle entità quando la
partizione viene rimossa:

- `REQUIRE_EMPTY`: la partizione si rimuove solo se è vuota; le sue
  entità vanno prima rimosse o spostate;
- `DISCARD_ENTITIES`: la partizione si rimuove insieme alle sue entità.

| Errore | Eccezione |
|---|---|
| Partizione non vuota con `REQUIRE_EMPTY` (la partizione non cambia), o già rimossa | `IllegalStateException` |
| Partizione di un altro spazio | `IllegalArgumentException` |
| k negativo nella configurazione | `IllegalArgumentException` |

Nel contratto del nodo la creazione riceve l'ID della partizione, la
configurazione dell'indice (`IndexConfig`, scansione lineare se manca)
e k (0 se manca), e fallisce con `IllegalArgumentException` se k è
negativo o se l'ID è ancora presente sul nodo, anche con la partizione
segnata come rimossa; la rimozione ha due operazioni, una per le partizioni vuote e
una che scarta anche le entità. Le due operazioni segnano la partizione
come rimossa: dopo il segno scritture, commit, letture del writer e
nuove acquisizioni falliscono con `IllegalStateException`, e il nodo
toglie la partizione quando le sue versioni non hanno più utilizzi
(vedi [Rimozione della
partizione](/architecture/version-retention.md#rimozione-della-partizione));
dopo, l'ID è sconosciuto e le operazioni falliscono con
`IllegalArgumentException`. L'API passa al nodo l'indice e k della
configurazione. Il nodo passa k al catalogo delle versioni della
partizione, che tiene le ultime k + 1 versioni pubblicate (vedi
[Conservazione delle versioni](/architecture/version-retention.md#nel-prototipo)).

## Commit

Il [commit](/architecture/partition-commit.md) si chiude con
`commit(long n)`, nell'interfaccia comune `Partition`. Il numero n lo
sceglie il software, perché il significato del contatore lo stabiliscono
i livelli superiori: il tick locale, il tick globale o un numero di
batch. n deve essere **maggiore dell'ultimo commit**; sono ammessi i
salti, per esempio quando un commit copre più tick.

| Operazione | Forma | Note |
|---|---|---|
| Commit | `commit(long n)` | Chiude il commit corrente con il numero n; un commit senza scritture è valido |
| Ultimo commit | `lastCommit()` | Il numero dell'ultimo commit; una partizione appena creata è al commit 0 |

| Errore | Eccezione |
|---|---|
| n non maggiore dell'ultimo commit (la partizione non cambia) | `IllegalArgumentException` |
| Partizione già rimossa | `IllegalStateException` |

Scelte di dettaglio:

- **Scritture fallite**: le scritture sono tutto-o-niente, quindi una
  scrittura fallita non cambia nemmeno il commit corrente.
- **Rimozione con scritture non committate**: è permessa; la politica
  di rimozione guarda lo stato del writer, non l'ultimo commit.
- **Contratto del nodo**: `commit(PartitionId, long)` e
  `lastCommit(PartitionId)`. Il commit di più partizioni in una sola
  chiamata, per ridurre le chiamate remote, è rimandato ai gruppi di
  partizioni o al nodo gRPC.

Il [fencing token](/architecture/partition-ownership.md), quando
arriva, si aggiunge al commit come alle altre scritture. La forma è
provvisoria e si rivede con il tick globale e le [letture
coerenti](/mechanisms/consistent-reads.md).

### Letture del writer e dei reader

Il commit pubblica la versione della partizione (vedi [isolamento delle
letture](/decisions/read-isolation.md#il-commit)). Le letture sono di
due tipi:

| Lettura | Forma | Note |
|---|---|---|
| Del writer | `getAll`, `findInRegion`, `findNearest` sul proxy della partizione | Leggono lo stato del writer, scritture non committate comprese; le chiama solo il writer |
| Dei reader | Le [acquisizioni](#acquisizione-delle-versioni) su `Partition2`/`Partition3` e nel contratto del nodo | Una versione acquisita è immutabile e leggibile da ogni thread; prima del primo commit l'ultima versione è quella vuota del commit 0 |

Tutte le letture di una versione acquisita vedono lo stesso commit; due
acquisizioni dell'ultima versione possono invece dare commit diversi.
La versione interna della partizione non esce dal nodo: nel service lo
storage ha ancora `lastVersion()`, per uso interno. `lastCommit()` si
può chiamare da ogni thread, ma è solo un metadato.

### Acquisizione delle versioni

La semantica è nella [conservazione delle
versioni](/architecture/version-retention.md#acquisizione-e-rilascio).
I reader leggono una versione solo attraverso un'acquisizione. Nel
prototipo ci sono le quattro selezioni con le opzioni, le letture e il
rilascio.

| Operazione | Forma | Note |
|---|---|---|
| Ultima | `acquireLatest(options)` | La versione corrente, anche quella vuota del commit 0 |
| Precedente | `acquirePrevious(int offset, options)` | `offset` = 0 è l'ultima, `offset` = i l'i-esimo commit pubblicato prima |
| Al tick o prima | `acquireAtOrBefore(long tick, options)` | La versione acquisibile con il commit più grande ≤ tick |
| Esatta | `acquireExact(long commit, options)` | Solo la versione di quel commit |
| Lettura | `commit()`, `size()`, `get`, `getAll`, `findInRegion`, `findNearest` sulla view | Ogni lettura controlla l'acquisizione all'inizio |
| Rilascio | `close()` sulla view; `release` nel contratto del nodo | Idempotente e thread-safe |

Le opzioni dicono la scadenza, che c'è sempre: la scadenza di default
oppure una durata positiva, non maggiore della scadenza massima,
misurata dal momento dell'acquisizione con il clock monotono del nodo.

Nel prototipo le opzioni sono il record `AcquireOptions` in `common`,
con la durata in nanosecondi: `AcquireOptions.defaultTimeout()` e
`AcquireOptions.timeout(Duration)`. Le costanti `DEFAULT_TIMEOUT`
(1 minuto) e `MAX_TIMEOUT` (1 ora) sono nel record, che rifiuta una
durata maggiore con `IllegalArgumentException` anche quando lo crea
il nodo; la scadenza massima diventerà una configurazione del nodo.
Nel contratto del nodo le quattro selezioni ricevono sempre le
opzioni; nell'API ogni selezione ha anche la forma senza opzioni, che
vale `defaultTimeout()`.

**Locale e remoto.** Nell'API la versione acquisita è una view
`AutoCloseable`, `AcquiredVersion2`/`AcquiredVersion3`, da usare con
`try`-with-resources; il suo `commit()` viene dall'identificatore e non
controlla l'acquisizione. Nel contratto del nodo un'acquisizione
restituisce un identificatore serializzabile, il record `Acquisition`
con l'ID della partizione, il numero dell'acquisizione e il commit
acquisito; le letture (`size`, `getAll`, `findInRegion`, `findNearest`)
ricevono l'identificatore e `release` lo chiude. Il numero lo sceglie
la partizione, parte da 1 e non si riusa. Le acquisizioni le tiene il
nodo, non il processo del reader, e la semantica è la stessa in locale
e attraverso gRPC.

| Errore | Eccezione |
|---|---|
| Versione non acquisibile: fuori dalla finestra, commit mai pubblicato, nessun commit ≤ tick | `VersionNotAvailableException`, eccezione di dominio dedicata; nessuna acquisizione creata |
| `offset`, tick o commit negativi; durata zero o negativa | `IllegalArgumentException` |
| Lettura attraverso un'acquisizione rilasciata o scaduta | `IllegalStateException`, con lo stesso messaggio nei due casi: dopo l'eliminazione di un'acquisizione scaduta il nodo non sa più se era stata rilasciata |
| Acquisizione su una partizione rimossa | `IllegalStateException` |
| Rilascio o lettura con un identificatore mai emesso o di un'altra partizione, nel contratto del nodo | `IllegalArgumentException`; le altre acquisizioni non cambiano |
| Lettura con un identificatore di una partizione che il nodo non ha, nel contratto del nodo | `IllegalStateException`, come per un'acquisizione rilasciata; il rilascio non fa niente. Gli ID delle partizioni non si riusano, quindi il nodo non distingue una partizione mai esistita da una già tolta |
| Lettura con un identificatore il cui commit non è quello della versione acquisita, nel contratto del nodo | `IllegalArgumentException` |

Scelte di dettaglio:

- **Rilascio ripetuto**: il secondo rilascio della stessa acquisizione,
  il rilascio dopo la scadenza o dopo la rimozione della partizione non
  fa niente, quindi `close()` della view non fallisce mai.
- **Scadenza controllata dal nodo**: il record `Acquisition` non porta
  la scadenza e la view dell'API non la controlla. La scadenza è
  misurata con il clock del nodo, che in un altro processo non vale, e
  il nodo non può fidarsi di una scadenza ricevuta dal reader. Un
  controllo anticipato nella view risparmierebbe solo una chiamata
  remota destinata a fallire: si valuta con il nodo gRPC.
- **Query in corso**: una query iniziata prima del rilascio o della
  scadenza finisce e il suo risultato è valido; i dati già letti
  restano validi anche dopo il rilascio.
- **`lastCommit()`** resta un metadato: non si usa per scegliere la
  versione da acquisire, perché tra i due passi la finestra può
  cambiare.
- **Dopo la rimozione della partizione** le acquisizioni già fatte
  restano leggibili e il nodo le trova per ID; l'ID della partizione
  rimossa non torna mai.

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
| ID inesistente in `update`/`remove` | `NoSuchElementException` |
| ID già presente o duplicato in `insert`, ID duplicato in `remove` | `IllegalArgumentException` |
| Posizione fuori dai limiti della partizione in `insert`/`update` | `IllegalArgumentException` |

Le coordinate `NaN` o infinite sono rifiutate già alla costruzione di
`Point2`/`Point3`, e un raggio infinito alla costruzione di
`Circle2`/`Sphere3`, con `IllegalArgumentException`.

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
