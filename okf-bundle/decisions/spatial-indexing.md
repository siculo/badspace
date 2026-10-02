---
type: Design Decision
title: Indicizzazione spaziale
description: L'indice spaziale si sceglie per partizione; scansione lineare e grid uniforme sono implementate, la griglia di quadtree è il prossimo indice, il confronto tra le strutture resta aperto.
tags: [badspace, design, spatial-indexing, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T10:44:22Z }
---

# Opzioni considerate

Più strutture possibili, con trade-off diversi:

- **[Grid uniforme](/indices/uniform-grid.md)** — semplice, O(1) su
  densità regolare.
- **[Quadtree / octree](/indices/quadtree.md)** — si adatta a densità
  non uniformi.
- **[Griglia di quadtree](/indices/grid-quadtree.md)** — grid in cui
  ogni cella è la radice di un quadtree o di un octree.
- **[R-tree](/indices/r-tree.md)** — buono per bounding box eterogenei.

Ogni indice ha un suo documento nella sezione [indici
spaziali](/indices/), con il funzionamento, le problematiche e le
decisioni prese o da prendere. La [scansione
lineare](/indices/linear-scan.md) è il riferimento per i test e i
benchmark.

# Indice per partizione

Con il [partizionamento del DB](/decisions/partitioning.md) l'indice
si sceglie **per partizione**, al momento della sua creazione.
Partizioni con profili omogenei possono usare l'indice più adatto, per
esempio:

- una struttura costruita una volta per la geometria statica;
- una grid per i proiettili;
- un quadtree per i giocatori.

Da valutare se una partizione può avere più di un indice.

# Stato

Il supporto a più tipi di indice è parte del modello a partizioni.
Nel prototipo sono implementate la scansione lineare e la grid
uniforme, in 2D e 3D; la griglia di quadtree è il prossimo indice. Il
quadtree con una sola radice è rimandato, e l'R-tree non è ancora
prototipato. Nessuna struttura è stata scelta come default: la scelta
dipende dai benchmark tra indici.

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — la scelta dell'indice interagisce con come le strutture statiche e
  dinamiche vengono costruite e aggiornate.
- Le modifiche ai [metadati delle entità](/architecture/entity-metadata.md)
  non toccano l'indice.
