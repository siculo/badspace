---
type: Design Decision
title: Entità statiche vs dinamiche
description: BADSPACE separa le entità statiche (build-once) da quelle dinamiche (aggiornate a ogni tick) a livello di struttura dati, unificando però le query di prossimità.
tags: [badspace, design, entities]
status: stable
generated: { by: claude-code/claude-sonnet-5, at: 2026-09-25T10:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-25T18:30:23Z
---

# Decisione

Separare le due categorie a livello di struttura dati:[^badspace-notes]

- Gli **statici** possono usare strutture costruite una volta e mai
  ricalcolate (build-once, query-many).
- I **dinamici** richiedono strutture economiche da aggiornare a ogni
  tick.

Le query di prossimità devono comunque poter interrogare entrambe le
categorie insieme.

# Correlati

- L'[indicizzazione spaziale](/decisions/spatial-indexing.md) — la
  struttura di indicizzazione scelta influisce su quanto sia economico
  aggiornare una struttura dinamica a ogni tick.

[^badspace-notes]: Note di progettazione di BADSPACE
