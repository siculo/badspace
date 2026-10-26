---
type: Reference
title: Estensioni dell’aggregazione delle query
description: Aggregazione futura delle query su più partizioni, oltre a range e k-nearest già disponibili - raycast al primo hit e a tutti gli hit, deduplica per ID ed epoca durante split e migrazione.
tags: [badspace, partitioning, queries]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-26T12:00:00Z }
---

# Stato e prerequisiti

Questo documento descrive sviluppi futuri. L’insieme di snapshot dello
stesso tick e l’aggregazione di range e k-nearest sono già disponibili:
si consultano nelle [query su più
partizioni](/current/architecture/multi-partition-queries.md), che
spiegano anche il principio (il software sceglie le partizioni, l’API
aggrega secondo il tipo di query).

# Raycast

Arriva con il raycast sulla singola partizione, che richiede di
riprendere il lavoro sugli indici:

- **primo hit**: il minimo per distanza. Interrogando le partizioni in
  sequenza, la distanza del primo hit trovato accorcia il raggio per le
  successive;
- **tutti gli hit**: fusione ordinata per distanza.

# Deduplica

Oggi un’entità sta in una sola partizione, quindi l’aggregazione non
deduplica. Durante una [migrazione](/future/mechanisms/entity-migration.md)
o uno [split](/future/mechanisms/rebalancing-and-split.md) un’entità può
comparire in due partizioni. Con [ID univoci](/current/decisions/entity-ids.md)
si deduplica per ID; per il k-nearest la deduplica va fatta prima di
tenere i k migliori. Con la migrazione vince la copia con l’epoca più
alta, e una copia "in uscita" perde se esiste anche l’altra; con il solo
split basta l’ID.

# Punti aperti

- Copie in uscita con posizione diversa da quella in B: una delle due
  può restare fuori dai risultati e sfuggire alla deduplica. Per il
  raycast al primo hit, un hit su una copia in uscita non dovrebbe
  accorciare il raggio per le altre partizioni.

# Correlati

- [Letture coerenti](/future/mechanisms/consistent-reads.md) — per
  scegliere il tick N comune e riprovare quando esce dalla finestra di
  una partizione.
