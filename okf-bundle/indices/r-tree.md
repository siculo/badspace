---
type: Spatial Index
title: R-tree
description: Albero di bounding box di dimensione variabile, adatto a geometria statica ed entità con estensione; non ancora prototipato.
tags: [badspace, spatial-indexing, r-tree]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T10:44:22Z }
---

# Come funziona

Ogni nodo ha un **bounding box** che contiene quelli dei suoi figli; le
foglie contengono le entità. A differenza di grid e quadtree, i box non
dividono lo spazio in parti fisse: dipendono dai dati e si possono
sovrapporre.

- Le query scendono solo nei nodi il cui box interseca la regione (range)
  o è abbastanza vicino (k-nearest best-first, come nel
  [quadtree](/indices/quadtree.md)).
- L'inserimento sceglie il figlio che si allarga meno; quando un nodo è
  pieno si divide con un'euristica (per esempio quadratica o R*-tree).
- Se tutti i dati sono noti all'inizio, l'albero si può costruire in
  blocco (bulk loading, per esempio STR), con box quasi senza
  sovrapposizioni.

# Problematiche

- **Aggiornamenti costosi:** spostare un'entità può allargare o
  restringere i box fino alla radice, e le sovrapposizioni crescono nel
  tempo. Esistono varianti per dati che si muovono, più complesse.
- **Non deterministico:** la forma dell'albero dipende dall'ordine degli
  inserimenti.
- **Utile soprattutto con entità estese:** con entità puntiformi i suoi
  vantaggi rispetto al quadtree sono minori.

# Decisioni

- **Prese:** nessuna; non è ancora prototipato.
- **Da prendere:** se serve, e in che forma. È il candidato naturale per
  le partizioni di **geometria statica** (costruita una volta, molte
  query) e per eventuali entità con estensione, legate alla decisione
  sul raycast.

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — le strutture build-once per gli statici.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — R-tree
  indicato per bounding box eterogenei.
