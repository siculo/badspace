---
type: Design Decision
title: API da esporre
description: La superficie API pubblica di BADSPACE (insert, remove, update posizione, query di range/k-nearest/raycasting) è rimandata a una fase successiva.
tags: [badspace, design, api]
status: draft
generated: { by: claude-code/claude-sonnet-5, at: 2026-09-25T10:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-22T00:00:00Z
---

# Stato

Punto rimandato a una fase successiva: quali operazioni offrire (es.
insert, remove, update posizione, query di range, k-nearest,
raycasting) e con quale forma.[^badspace-notes]

# Correlati

- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  e [indicizzazione spaziale](/decisions/spatial-indexing.md)
  vincolano ciò che l'API può esporre in modo efficiente.

[^badspace-notes]: Note di progettazione di BADSPACE
