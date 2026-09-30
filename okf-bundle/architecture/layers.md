---
type: Reference
title: Architettura a livelli
description: I tre livelli di BADSPACE partizionato — software, API comune di supporto, layer delle partizioni ospitato dai nodi — chi decide cosa e dove passa il futuro confine di rete.
tags: [badspace, architecture, partitioning, api]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-30T08:29:03Z }
---

# Livelli

```
┌ processo del software ───────────────────────────────────┐
│ software (simulazione, monitoraggio, gioco, …)           │
│         │   sceglie la strategia di partizionamento      │
│         │   e il nodo di ogni partizione                 │
│   API comune sopra le partizioni (libreria)              │
│         │   supporto per interagire con le partizioni    │
│         │   + operazioni comuni                          │
└─────────┼────────────────────────────────────────────────┘
          │   contratto dei nodi: chiamata locale oggi, gRPC domani
  layer delle partizioni, ospitato da uno o più nodi
            istanze indipendenti, single-writer, indice proprio
```

Lo strato intermedio è una **API di supporto**, non un livello che
decide al posto del software. È una **libreria** che resta nel processo
del software (vedi [tipo di strumento](/decisions/tool-type.md)). Il
futuro confine di rete passa **sotto** l'API, tra l'API e i
[nodi](#nodi) che ospitano le partizioni: identificatori, aggregazione e
migrazione devono vedere più partizioni, quindi stanno sopra.

# Chi decide cosa

| Livello | Responsabilità |
|---|---|
| Software | strategia di partizionamento, nodo su cui creare ogni partizione, mappa ID → partizione, quando migrare un'entità, quali partizioni interrogare |
| API comune | generazione degli [ID](/decisions/entity-ids.md) di entità e partizioni, [aggregazione](/mechanisms/query-aggregation.md), creazione e configurazione delle partizioni (incluso l'indice), meccanismi come la [migrazione](/mechanisms/entity-migration.md) e le [letture coerenti](/mechanisms/consistent-reads.md) |
| Layer delle partizioni | le primitive dell'[architettura minima](/architecture/minimal-core.md), offerte dai nodi |

Le decisioni sul ciclo di vita delle istanze (quali e quante
partizioni, nodi e istanze dell'API) stanno sopra i meccanismi che le
realizzano: vedi il principio "Istanze dentro un sistema"
nell'[architettura minima](/architecture/minimal-core.md#principi).

Le partizioni sono **esplicite**: una scrittura indica la partizione di
destinazione, una query l'insieme di partizioni da interrogare.

# Nodi

Un **nodo** è un processo, locale o remoto, che ospita una o più
partizioni. Le partizioni di uno spazio possono stare su nodi diversi,
anche su macchine diverse: il software sceglie su quale nodo creare
ogni partizione.

- **Contratto a identificatori.** Tra processi non si passano
  riferimenti a oggetti: il contratto dei nodi usa l'ID della partizione
  e gli ID delle entità. È la forma del futuro servizio gRPC; oggi
  l'implementazione è una chiamata nello stesso processo.
- **Proxy nell'API.** L'oggetto partizione visto dal software è un
  proxy che conosce il proprio nodo e l'ID della propria partizione.
- **Il nodo si fida degli ID.** Gli ID di entità e partizioni li genera
  l'API; il nodo non li genera né verifica che siano unici altrove.
- **Un nodo serve un solo spazio**, per ora: così gli ID delle
  partizioni, univoci nello spazio, non collidono sul nodo.

## Punti aperti

- Nodi condivisi tra spazi: richiederebbero ID di partizione globali,
  per esempio in stile Snowflake (vedi [ID](/decisions/entity-ids.md)).
- Chiamate a lotti ed errori remoti nel contratto dei nodi.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
- [API da esporre](/decisions/api-surface.md).
