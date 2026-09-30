---
type: Design Decision
title: API da esporre
description: La superficie API pubblica di BADSPACE ha già un elenco di operazioni di base su entità, metadati, partizioni e commit; il prototipo definisce la forma delle operazioni sulle entità (record per le coordinate, operazioni batch, scritture tutto-o-niente) e delle query di range e k-nearest su una partizione, mentre il resto è da definire; il partizionamento fissa già partizioni esplicite, generazione degli ID, aggregazione, scritture condizionate e API dei metadati.
tags: [badspace, design, api, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-30T16:25:38Z }
---

# Stato

Le operazioni di base sono individuate (insert, remove, update
posizione, query di range, k-nearest, raycasting e le operazioni su
metadati, partizioni e commit). Il prototipo definisce la forma delle
operazioni di scrittura e lettura per ID sulle entità e delle query di
range e k-nearest su una partizione (vedi [Forma delle chiamate nel
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

Nel prototipo le query scandiscono tutte le entità della partizione:
è la misura di riferimento per i benchmark e il risultato atteso nei
test degli indici spaziali (vedi [Indicizzazione
spaziale](/decisions/spatial-indexing.md)). Il raycast non c'è ancora:
per entità puntiformi la sua forma dipende dagli usi e va decisa a
parte.

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
