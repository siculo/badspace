---
type: Design Decision
title: Entità statiche vs dinamiche
description: BADSPACE separa le entità statiche (build-once) da quelle dinamiche (aggiornate a ogni tick) a livello di struttura dati, come caso particolare di partizionamento, unificando però le query di prossimità.
tags: [badspace, design, entities, partitioning]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Decisione

Separare le due categorie a livello di struttura dati:

- Gli **statici** possono usare strutture costruite una volta e mai
  ricalcolate (build-once, query-many).
- I **dinamici** richiedono strutture economiche da aggiornare a ogni
  tick.

Le query di prossimità devono comunque poter interrogare entrambe le
categorie insieme.

# Come caso di partizionamento

Con il [partizionamento del DB](/decisions/partitioning.md) la
separazione diventa un caso particolare: due partizioni con indici
diversi, interrogate insieme tramite l'[aggregazione dei
risultati](/mechanisms/query-aggregation.md).

# Correlati

- L'[indicizzazione spaziale](/decisions/spatial-indexing.md) — la
  struttura di indicizzazione scelta influisce su quanto sia economico
  aggiornare una struttura dinamica a ogni tick.
