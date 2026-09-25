---
type: Design Decision
title: Indicizzazione spaziale
description: Trade-off ancora aperto tra grid, quadtree/octree e R-tree per l'indicizzazione spaziale di BADSPACE, rimandato in attesa di prototipazione.
tags: [badspace, design, spatial-indexing]
status: draft
generated: { by: claude-code/claude-sonnet-5, at: 2026-09-25T10:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-25T18:30:23Z
---

# Opzioni considerate

Più strutture possibili, con trade-off diversi:[^badspace-notes]

- **Grid uniforme** — semplice, O(1) su densità regolare.
- **Quadtree / octree** — si adatta a densità non uniformi.
- **R-tree** — buono per bounding box eterogenei.

# Stato

Nessuna struttura è stata ancora scelta. La libreria dovrebbe poter
supportare più tipi di indicizzazione in futuro, ma questa flessibilità
è rimandata a una fase successiva — i primi prototipi useranno
qualunque struttura sia più semplice per validare il resto del design.

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — la scelta dell'indice interagisce con come le strutture statiche e
  dinamiche vengono costruite e aggiornate.

[^badspace-notes]: Note di progettazione di BADSPACE
