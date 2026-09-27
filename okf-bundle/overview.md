---
type: Reference
title: Panoramica di BADSPACE
description: Panoramica di progettazione di BADSPACE, un database spaziale real-time embedded per giochi, e delle sue decisioni architetturali principali.
tags: [badspace, game-dev, design]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Cos'è BADSPACE

BADSPACE è un database spaziale real-time pensato per essere usato
all'interno di un server di gioco, o in casi d'uso analoghi, che tiene
traccia della posizione delle entità e risponde a query di prossimità
(range, k-nearest, raycasting) mentre la simulazione di gioco procede.

# Decisioni principali

- [Tipo di strumento](/decisions/tool-type.md) — libreria embedded in
  prima battuta, pensata per evolvere in seguito verso un servizio gRPC;
  prototipi in Java, linguaggio finale
  ancora aperto tra C, C++ e Rust.
- [Partizionamento del DB](/decisions/partitioning.md) — partizioni
  indipendenti, single-writer, con indice proprio; strategia scelta dal
  software utilizzatore.
- [ID delle entità](/decisions/entity-ids.md) — Snowflake a 64 bit,
  univoci su tutte le partizioni.
- [Indicizzazione spaziale](/decisions/spatial-indexing.md) — scelta per
  partizione; strutture ancora aperte: grid, quadtree/octree o R-tree.
- [Supporto a 2D e 3D](/decisions/2d-3d-support.md) — implementazioni
  parallele dietro un'interfaccia comune.
- [Entità statiche vs dinamiche](/decisions/static-vs-dynamic-entities.md)
  — strutture dati separate, query di prossimità unificate; caso
  particolare di partizionamento.
- [Concorrenza](/decisions/concurrency.md) — un solo writer per
  partizione.
- [API da esporre](/decisions/api-surface.md) — da definire nel
  dettaglio.
- [Persistenza](/decisions/persistence.md) — rimandata.

# Architettura

BADSPACE è organizzato in [tre livelli](/architecture/layers.md):
software utilizzatore, API comune di supporto e layer delle partizioni.
Il layer delle partizioni offre un insieme minimo di primitive (vedi
[architettura minima](/architecture/minimal-core.md)), sopra le quali
si costruiscono i meccanismi:

- [migrazione di entità](/mechanisms/entity-migration.md);
- [letture coerenti](/mechanisms/consistent-reads.md) su più partizioni;
- [aggregazione delle query](/mechanisms/query-aggregation.md);
- [ribilanciamento e split](/mechanisms/rebalancing-and-split.md).

# Processo

Lo sviluppo procede per [prototipi minimali](/process/development-approach.md):
ogni prototipo è accompagnato da un piccolo gioco di test per validare
le decisioni sul campo, invece di progettare tutto a priori.
