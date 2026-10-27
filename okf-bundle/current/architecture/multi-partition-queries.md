---
type: Reference
title: "Query su più partizioni"
description: "Insieme di snapshot dello stesso tick su partizioni scelte dal software: acquisizione, fallimenti come esito, rilascio e aggregazione di range e k-nearest."
tags: [badspace, partitioning, queries]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-27T20:31:26Z }
---

# Principio

Una query su più partizioni equivale a una query su ciascuna, con i
risultati da aggregare. Due aspetti sono distinti:

- **quali partizioni interrogare, e a quale tick**: dipende dalla
  strategia di partizionamento, quindi lo decide il software;
- **come aggregare**: dipende solo dal tipo di query, quindi lo offre
  l’[API comune](/current/architecture/layers.md).

Non servono primitive nuove del livello base: ogni partizione resta
indipendente, con il suo indice, e l’aggregazione lavora sugli
[snapshot acquisiti](/current/architecture/snapshot-retention.md#acquisizione-e-rilascio)
delle singole partizioni. Partizioni con indici diversi, per esempio una
di entità statiche con una grid e una di entità dinamiche con la
scansione lineare, si interrogano insieme: tutti gli indici danno gli
stessi risultati.

# Insieme di snapshot

L’**insieme di snapshot** (`SnapshotSet2`/`SnapshotSet3`) acquisisce lo
snapshot dello stesso tick N su una lista di partizioni, indicate con i
loro handle, e offre le query su tutti gli snapshot acquisiti. Lo crea
lo spazio con `acquire(partitions, tick)`, solo con partizioni proprie.
È `AutoCloseable`: `close()` rilascia tutte le acquisizioni.

È un’utility generica: non sceglie N e non decide se un risultato
parziale basta. Acquisisce quello che può e restituisce, insieme agli
snapshot, i **fallimenti**: le partizioni di cui lo snapshot di N non è
disponibile, ciascuna con il motivo e l’eccezione. Il chiamante decide
cosa farne: accettare il risultato parziale, riprovare con un altro
tick o rinunciare.

Il diagramma mostra un insieme al tick 4 su tre partizioni, di cui una
creata al tick 5; le partizioni pubblicano ciascuna per conto suo, e
l’insieme le acquisisce una dopo l’altra.

```text
                     "space.acquire([A, B, C], 4)"
                                   |
            +----------------------+----------------------+
            |                      |                      |
            v                      v                      v
     +-------------+        +-------------+        +-------------+
     | A: S(4)     |        | B: S(4)     |        | C: creata   |
     | acquisito   |        | acquisito   |        | al tick 5   |
     +-------------+        +-------------+        +-------------+
            |                      |                      |
            v                      v                      v
      "snapshots()"          "snapshots()"          "failures()"
                                                  "BEFORE_CREATION"
```

Le query dell’insieme (`findInRegion`, `findNearest`) vedono solo A e
B. Con `isComplete()` il chiamante sa se manca qualche partizione.

## Che cosa è un fallimento

Un fallimento è un caso in cui lo snapshot non è disponibile. Il suo
motivo dice perché dalla partizione non si hanno dati al tick N, e
riunisce due eccezioni del nodo:

- `SnapshotNotAvailableException`, con lo stesso motivo: non ancora
  pubblicato, fuori dalla finestra, prima della creazione, dalla
  rimozione in poi. Dalla rimozione in poi vale finché la partizione è
  ritirata, cioè finché ha ancora snapshot nella finestra;
- `UnknownPartitionException`, con il motivo «partizione sconosciuta»:
  il nodo non conosce la partizione. Il nodo non distingue una
  partizione distrutta da una mai esistita (vedi [rimozione della
  partizione](/current/architecture/partition-removal.md#distruzione)),
  e il motivo non dice più di quello che il nodo sa. Con un handle
  creato dallo spazio, che viene sempre da una partizione creata, vuol
  dire che la partizione è distrutta.

Il motivo dell’eccezione del nodo descrive solo lo snapshot di una
partizione che il nodo conosce; il motivo del fallimento descrive la
partizione vista dall’insieme, quindi è un tipo a parte.

Ogni altro errore è un difetto o un uso sbagliato, non un esito: ferma
la chiamata, che prima rilascia gli snapshot già acquisiti e poi
propaga l’errore. I parametri si controllano prima di acquisire
qualunque cosa: lista o elementi nulli, opzioni nulle, tick negativo,
partizione di un altro spazio e partizione ripetuta nella lista. Una
partizione di un altro spazio è un uso sbagliato, non un fallimento:
lo spazio la rifiuta con `IllegalArgumentException` anche quando segue
partizioni valide, senza acquisire niente. Il controllo usa l’identità
dello spazio, non la sua configurazione. Il controllo del tick è lo stesso del
nodo, condiviso in `common`. Una lista vuota dà un insieme vuoto e
completo.

## Tick e coerenza

Tutte le partizioni dell’insieme usano lo stesso tick N, scelto dal
chiamante. Gli snapshot acquisiti sono quindi riferiti allo stesso tick
logico, e con il [gTick](/current/architecture/logical-time.md) passato
a `setTick` dello spazio sono una fotografia dello stesso istante.
Scegliere N resta compito del chiamante: la propagazione del tick non è
atomica tra le partizioni, quindi durante un avanzamento lo snapshot di
N può essere già pubblicato su alcune partizioni e non ancora su altre,
e uno snapshot vecchio può uscire dalla finestra di una partizione con
k piccolo. In questi casi l’insieme dà un fallimento, non attende né
riprova.

Lo stesso spazio garantisce il tempo comune: spazi diversi avanzano in
modo indipendente, e lo stesso numero di tick non indica lo stesso
istante. Non garantisce invece che le coordinate di partizioni diverse
siano confrontabili: scegliere partizioni che hanno senso insieme resta
compito del software.

# Aggregazione per tipo di query

- **Range** (`findInRegion`): unione dei risultati, in ordine non
  definito, come sulla singola partizione.
- **k-nearest** (`findNearest(point, count)`): ogni snapshot restituisce
  i suoi `count` più vicini; l’insieme li ordina per distanza dal punto e
  a parità di distanza per ID, come la query su una partizione, e tiene i
  primi `count`. Con un `count` negativo fallisce con
  `IllegalArgumentException`, anche senza snapshot.

Un’entità sta in una sola partizione, quindi l’aggregazione non
deduplica. Le query dopo il rilascio o la scadenza di un’acquisizione
falliscono come sulla view dello snapshot.

# Costo

Con indici logaritmici, n query su partizioni più piccole costano poco
più di una query su una partizione unica. Le acquisizioni e le query si
fanno una dopo l’altra, nello stesso thread; il fan-out pesa con molte
partizioni, e la strategia del software serve a limitarlo.

# Nel prototipo

`SnapshotSet2`/`SnapshotSet3` sono nel modulo `api`, accanto alle view
degli snapshot; li crea `Space2`/`Space3`, perché la funzione statica
che acquisisce è interna al package. `acquire(partitions, tick)` dello
spazio usa la scadenza di default, `acquire(partitions, tick, options)`
quella delle opzioni, uguale per tutte le acquisizioni. Il risultato espone `tick()`, `snapshots()` e
`failures()` nell’ordine della lista e `isComplete()`; il fallimento è
il record `SnapshotSet2.Failure`/`SnapshotSet3.Failure`, con la
partizione, il motivo `reason()` e l’eccezione `error()`. Il motivo è
l’enum `FailureReason`, comune a 2D e 3D: `NOT_YET_PUBLISHED`,
`OUT_OF_RETENTION`, `BEFORE_CREATION`, `AFTER_REMOVAL` e
`UNKNOWN_PARTITION`. `close()` va avanti anche se un
rilascio fallisce e poi lancia il primo errore.

I test del modulo `api` verificano acquisizioni, fallimenti, rilascio e
aggregazione con i nodi simulati; i test di integrazione confrontano le
query su tre partizioni con indici diversi (scansione lineare, grid
uniforme, griglia di alberi) con quelle su una partizione che contiene
le stesse entità, e verificano i fallimenti reali, compresa una
partizione ritirata e una distrutta, e il rifiuto di una partizione di
un altro spazio senza acquisizioni rimaste. L’esempio
`multi-partition-queries` mostra una partizione statica e una dinamica
interrogate insieme e una partizione creata dopo il tick richiesto.

# Correlati

- [Contratto dell’API](/current/api/operations.md#query-su-più-partizioni):
  firme ed errori.
- In futuro: raycast, deduplica durante migrazione e split, e letture
  coerenti con la scelta di N (vedi [aggregazione
  futura](/future/mechanisms/query-aggregation.md)).
