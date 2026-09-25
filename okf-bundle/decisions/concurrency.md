---
type: Design Decision
title: Concorrenza
description: Trade-off ancora indeciso tra single-writer con lettori su snapshot e multi-writer per la gestione della concorrenza in BADSPACE.
tags: [badspace, design, concurrency]
status: draft
generated: { by: claude-code/claude-sonnet-5, at: 2026-09-25T10:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-22T00:00:00Z
---

# Opzioni considerate

Da valutare tra:[^badspace-notes]

- **Single-writer** — un solo scrittore, tipicamente il game loop, con
  lettori concorrenti su snapshot. Semplice e deterministico.
- **Multi-writer** — lock granulari o strutture lock-free. Più
  scalabile, ma molto più complesso da mantenere coerente.

# Stato

Nessuna decisione definitiva ancora.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — partire da una
  libreria embedded in-process mantiene questa decisione locale per ora.

[^badspace-notes]: Note di progettazione di BADSPACE
