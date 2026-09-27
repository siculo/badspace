---
type: Design Decision
title: Indicizzazione spaziale
description: L'indice spaziale si sceglie per partizione; resta aperto il trade-off tra grid, quadtree/octree e R-tree, rimandato in attesa di prototipazione.
tags: [badspace, design, spatial-indexing, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Opzioni considerate

Più strutture possibili, con trade-off diversi:

- **Grid uniforme** — semplice, O(1) su densità regolare.
- **Quadtree / octree** — si adatta a densità non uniformi.
- **R-tree** — buono per bounding box eterogenei.

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

Nessuna struttura è stata ancora scelta. Il supporto a più tipi di
indicizzazione diventa parte del modello a partizioni, ma i primi
prototipi useranno qualunque struttura sia più semplice per validare il
resto del design.

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — la scelta dell'indice interagisce con come le strutture statiche e
  dinamiche vengono costruite e aggiornate.
- Le modifiche ai [metadati delle entità](/architecture/entity-metadata.md)
  non toccano l'indice.
