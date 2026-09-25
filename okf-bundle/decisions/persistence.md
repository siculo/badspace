---
type: Design Decision
title: Persistenza
description: Strategia di persistenza per BADSPACE — snapshot periodici verso un DB esterno, senza scritture sincrone nel game loop — rimandata a una fase successiva.
tags: [badspace, design, persistence]
status: draft
generated: { by: claude-code/claude-sonnet-5, at: 2026-09-25T10:00:00Z }
sources:
  - id: badspace-notes
    resource: ../../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-25T18:30:23Z
---

# Stato

Aspetto importante ma rimandato: snapshot periodici dello stato,
eventualmente verso un DB esterno, senza scritture sincrone nel game
loop.[^badspace-notes]

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — una futura forma a
  servizio potrebbe centralizzare dove vengono scritti gli snapshot.

[^badspace-notes]: Note di progettazione di BADSPACE
