---
type: Reference
title: Panoramica di BADSPACE
description: Panoramica di progettazione di BADSPACE, un database spaziale real-time embedded per giochi, e delle sue decisioni architetturali principali.
tags: [badspace, game-dev, design]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-25T12:00:00Z }
sources:
  - id: badspace-notes
    resource: ../badspace.md
    title: "BADSPACE: Database spaziale real-time per gioco — note di progettazione"
    author: human:someone
    last_modified: 2026-09-25T18:30:23Z
---

# Cos'è BADSPACE

BADSPACE è un database spaziale real-time pensato per essere usato
all'interno di un server di gioco, o in casi d'uso analoghi, che tiene
traccia della posizione delle entità e risponde a query di prossimità
(range, k-nearest, raycasting) mentre la simulazione di gioco procede.[^badspace-notes]

# Decisioni principali

- [Tipo di strumento](/decisions/tool-type.md) — libreria embedded in
  prima battuta, pensata per evolvere in seguito verso un servizio gRPC;
  prototipi in Java, linguaggio finale
  ancora aperto tra C, C++ e Rust.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — ancora
  aperta: grid, quadtree/octree o R-tree.
- [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni
  parallele dietro un'interfaccia comune.
- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — strutture dati separate, query di prossimità unificate.
- [Concorrenza](/decisions/concurrency.md) — ancora aperta:
  single-writer vs multi-writer.
- [API da esporre](/decisions/api-surface.md) — rimandata.
- [Persistenza](/decisions/persistence.md) — rimandata.

# Processo

Lo sviluppo procede per [prototipi minimali](/process/development-approach.md):
ogni prototipo è accompagnato da un piccolo gioco di test per validare
le decisioni sul campo, invece di progettare tutto a priori.

[^badspace-notes]: Note di progettazione di BADSPACE
