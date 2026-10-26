---
type: Design Decision
title: Entità statiche vs dinamiche
description: BADSPACE separa le entità statiche (build-once) da quelle dinamiche (aggiornate a ogni tick) a livello di struttura dati, come caso particolare di partizionamento, unificando però le query di prossimità.
tags: [badspace, design, entities, partitioning]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato e prerequisiti

Questo documento descrive uno sviluppo futuro, non una funzionalità disponibile nel prototipo. Le primitive già implementate si consultano nella [documentazione corrente](/current/index.md).

# Decisione

Separare le due categorie a livello di struttura dati:

- Gli **statici** possono usare strutture costruite una volta e mai
  ricalcolate (build-once, query-many).
- I **dinamici** richiedono strutture economiche da aggiornare a ogni
  tick.

Le query di prossimità devono comunque poter interrogare entrambe le
categorie insieme.

# Come caso di partizionamento

Con il [partizionamento del DB](/current/decisions/partitioning.md) la
separazione diventa un caso particolare: due partizioni con indici
diversi, interrogate insieme con un [insieme di
snapshot](/current/architecture/multi-partition-queries.md), che già
aggrega range e k-nearest.

# Correlati

- L'[indicizzazione spaziale](/current/decisions/spatial-indexing.md) — la
  struttura di indicizzazione scelta influisce su quanto sia economico
  aggiornare una struttura dinamica a ogni tick.
