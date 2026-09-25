---
type: Design Decision
title: Supporto a 2D e 3D
description: BADSPACE supporta 2D e 3D tramite implementazioni parallele Point2/Point3 e Quadtree/Octree dietro un trait comune.
tags: [badspace, rust, design, 2d3d]
status: stable
generated: { by: claude-code/claude-sonnet-5, at: 2026-09-25T10:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-22T00:00:00Z
---

# Decisione

Soluzione scelta: **implementazioni parallele** (`Point2`/`Point3`,
`Quadtree`/`Octree`) dietro un **trait comune** per le operazioni
condivise, invece di forzare una genericità unica su N
dimensioni.[^badspace-notes]

# Correlati

- L'[indicizzazione spaziale](/decisions/spatial-indexing.md) — le
  strutture duplicate per dimensione (quadtree/octree) restano una
  scelta aperta tra i tipi di indicizzazione.

[^badspace-notes]: Note di progettazione di BADSPACE
