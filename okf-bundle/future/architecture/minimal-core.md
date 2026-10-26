---
type: Reference
title: "Primitive richieste dai meccanismi futuri"
description: "Dipendenze tra il nucleo corrente e i protocolli futuri: metadati di sistema, tombstone, durabilità e coordinamento."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Punto di partenza

Il [nucleo corrente](/future/architecture/minimal-core.md) offre proprietà,
commit, tempo logico, metadati applicativi e snapshot conservati. Le
primitive mancanti si introducono quando un protocollo ne ha bisogno.

# Estensioni del livello base

- [Metadati di sistema](/future/architecture/entity-metadata.md): epoca,
  stato e destinazione, con condizioni verificate atomicamente nella partizione.
- [Tombstone](/future/architecture/tombstones.md): memoria delle rimozioni
  per impedire che messaggi vecchi ricreino entità già eliminate.
- [Durabilità osservabile](/future/architecture/observable-durability.md):
  sapere quali scritture sono salvate prima di confermarle ad altri.

# Meccanismi sopra il livello base

| Meccanismo | Dipendenze e problemi risolti |
|---|---|
| [Aggregazione](/future/mechanisms/query-aggregation.md) | Fan-out di query e unione secondo il tipo, già [disponibili](/current/architecture/multi-partition-queries.md) per range e k-nearest; da aggiungere raycast e deduplica quando ci sono copie in migrazione o split |
| [Letture coerenti](/future/mechanisms/consistent-reads.md) | Acquisizione dello stesso tick su ogni partizione; eventuale barriera decisa dal software |
| [Migrazione](/future/mechanisms/entity-migration.md) | Commit, metadati di sistema, tombstone e conferme durevoli; trasporto con reinvio |
| [Ribilanciamento e split](/future/mechanisms/rebalancing-and-split.md) | Passaggio di proprietà e operazioni in blocco sui dati; gestione dei guasti da definire |

# Decisioni aperte

Forma delle chiamate sui metadati di sistema, trasporto dei messaggi di
migrazione e servizio di coordinamento di gTick e barriera. Il tick
del nucleo corrente non implica che questi meccanismi esistano già.
