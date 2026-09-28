---
type: Reference
title: Architettura a livelli
description: I tre livelli di BADSPACE partizionato — software, API comune di supporto, layer delle partizioni — e chi decide cosa.
tags: [badspace, architecture, partitioning, api]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Livelli

```
software (simulazione, monitoraggio, gioco, …)
        │   sceglie la strategia di partizionamento
        │
  API comune sopra le partizioni
        │   supporto per interagire con le partizioni + operazioni comuni
        │
  layer delle partizioni
            istanze indipendenti, single-writer, indice proprio
```

Lo strato intermedio è una **API di supporto**, non un livello che
decide al posto del software. È coerente con la scelta di
partire da una libreria embedded (vedi [tipo di
strumento](/decisions/tool-type.md)): l'API è la superficie pubblica
della libreria, esponibile in futuro via gRPC.

# Chi decide cosa

| Livello | Responsabilità |
|---|---|
| Software | strategia di partizionamento, mappa ID → partizione, quando migrare un'entità, quali partizioni interrogare |
| API comune | generazione degli [ID](/decisions/entity-ids.md), [aggregazione](/mechanisms/query-aggregation.md), creazione e configurazione delle partizioni (incluso l'indice), meccanismi come la [migrazione](/mechanisms/entity-migration.md) e le [letture coerenti](/mechanisms/consistent-reads.md) |
| Layer delle partizioni | le primitive dell'[architettura minima](/architecture/minimal-core.md) |

Le partizioni sono **esplicite**: una scrittura indica la partizione di
destinazione, una query l'insieme di partizioni da interrogare.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
- [API da esporre](/decisions/api-surface.md).
