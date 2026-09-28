---
type: Reference
title: Aggregazione delle query su più partizioni
description: Una query su più partizioni equivale a n query con risultati aggregati secondo il tipo di query (range, k-nearest, raycast) e deduplicati per ID ed epoca.
tags: [badspace, partitioning, queries]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Principio

Una query su un intorno di un punto equivale a n query sulle n
partizioni scelte dal software, con risultati da
aggregare. Due aspetti distinti:

- **Su quali partizioni interrogare**: dipende dalla strategia, quindi
  la decide il software.
- **Come aggregare**: dipende solo dal tipo di query. È generica e la
  offre l'[API comune](/architecture/layers.md).

Non richiede primitive speciali del livello base.

# Aggregazione per tipo di query

- **range**: unione dei risultati;
- **k-nearest**: ogni partizione restituisce i suoi k, poi si tengono i
  k migliori del totale; la deduplica va fatta prima di tenere i k
  migliori;
- **raycast, primo hit**: il minimo per distanza. Interrogando le
  partizioni in sequenza, la distanza del primo hit trovato accorcia il
  raggio per le successive;
- **raycast, tutti gli hit**: fusione ordinata per distanza.

# Deduplica

Durante una [migrazione](/mechanisms/entity-migration.md) un'entità può
comparire in due partizioni. Con [ID univoci](/decisions/entity-ids.md)
si deduplica per ID: vince la copia con l'epoca più alta, e una copia
"in uscita" perde se esiste anche l'altra.

# Costo

Con indici logaritmici, n query su partizioni più piccole costano poco
più di una query su una partizione unica. Il fan-out pesa con molte
partizioni o con partizioni remote, dove comanda la più lenta; la
strategia del software serve a limitarlo.

# Punti aperti

- Copie in uscita con posizione diversa da quella in B: una delle due
  può restare fuori dai risultati e sfuggire alla deduplica. Per il
  raycast al primo hit, un hit su una copia in uscita non dovrebbe
  accorciare il raggio per le altre partizioni.

# Correlati

- [Letture coerenti](/mechanisms/consistent-reads.md) — per aggregare
  risultati riferiti allo stesso tick.
