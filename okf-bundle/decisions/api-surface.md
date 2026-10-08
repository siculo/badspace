---
type: Design Decision
title: API da esporre
description: La superficie API pubblica di BADSPACE ha già un elenco di operazioni di base su entità, metadati, partizioni e commit; il prototipo definisce la forma delle operazioni sulle entità (record per le coordinate, operazioni batch, scritture tutto-o-niente), delle query di range e k-nearest su una partizione e della creazione e rimozione delle partizioni con la politica di rimozione, mentre il resto è da definire; il partizionamento fissa già partizioni esplicite, generazione degli ID, aggregazione, scritture condizionate e API dei metadati.
tags: [badspace, design, api, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-08T07:45:42Z }
---

# Stato

Le operazioni di base sono individuate (insert, remove, update
posizione, query di range, k-nearest, raycasting e le operazioni su
metadati, partizioni e commit). Il prototipo definisce la forma delle
operazioni di scrittura e lettura per ID sulle entità, delle query di
range e k-nearest su una partizione e della creazione e rimozione delle
partizioni (vedi [Forma delle chiamate nel
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
| Commit | `commit()` | Chiude il [commit](/architecture/partition-commit.md) corrente; per un server a frame, alla fine del tick |
| Commit | Ultimo commit persistito | [Durabilità osservabile](/architecture/observable-durability.md) |
| Commit | Lettura al tick N | Sulle partizioni con k ≥ 1 (vedi [conservazione delle versioni](/architecture/version-retention.md)) |

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
alla costruzione il generatore degli [ID](/decisions/entity-ids.md) delle
entità, condiviso da tutti gli spazi del processo, e genera gli ID delle
sue partizioni.

| Operazione | Forma | Note |
|---|---|---|
| Creazione | `createPartition(node)`, `createPartition(node, RemovalPolicy)` | Crea una partizione vuota sul [nodo](/architecture/layers.md#nodi) scelto dal software, che deve servire solo questo spazio; senza politica vale `REQUIRE_EMPTY` |
| Rimozione | `removePartition(partition)` | La chiama il writer della partizione; dopo la rimozione la partizione non si può più usare |

La **politica di rimozione** si sceglie alla creazione e dice cosa
succede alle entità quando la partizione viene rimossa:

- `REQUIRE_EMPTY`: la partizione si rimuove solo se è vuota; le sue
  entità vanno prima rimosse o spostate;
- `DISCARD_ENTITIES`: la partizione si rimuove insieme alle sue entità.

| Errore | Eccezione |
|---|---|
| Partizione non vuota con `REQUIRE_EMPTY` (la partizione non cambia), o già rimossa | `IllegalStateException` |
| Partizione di un altro spazio | `IllegalArgumentException` |

Nel contratto del nodo la creazione riceve l'ID della partizione e la
configurazione dell'indice (`IndexConfig`, scansione lineare se manca);
la rimozione ha due operazioni, una per le partizioni vuote e una che
scarta anche le entità. Nell'API la scelta dell'indice e di k alla
creazione non c'è ancora: oggi le partizioni create dall'API usano la
scansione lineare.

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
