---
type: Design Decision
title: ID delle entità
description: Gli ID delle entità sono univoci su tutte le partizioni, generati dall'API comune in stile Snowflake a 64 bit, mai riusati e senza indicazione della partizione.
tags: [badspace, design, partitioning, ids]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Decisione

- Gli ID sono **univoci per tutte le partizioni**.
- La generazione è **esterna al layer delle partizioni** ed è
  responsabilità dell'[API comune](/architecture/layers.md), non del
  software.
- Schema scelto: **stile Snowflake**. Un ID a 64 bit composto da un
  identificativo del generatore (assegnato una volta sola) e un
  contatore locale: univoco senza coordinamento a runtime, regge anche
  il passaggio al cluster.
- Gli ID **non vanno riusati**, altrimenti un riferimento vecchio
  finisce su un'entità nuova. Con 64 bit si può semplicemente non
  riusarli mai.
- L'ID **non indica la partizione**: nessuno deve ricavare la partizione
  dall'ID, altrimenti una [migrazione](/mechanisms/entity-migration.md)
  rompe l'invariante.

La mappa ID → partizione è del software; l'API potrebbe
offrirla in futuro come aiuto opzionale.

# Punti aperti

- Assegnazione degli identificativi dei generatori, in particolare
  nella futura forma a cluster.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
- L'univocità degli ID è ciò che permette la deduplica
  nell'[aggregazione](/mechanisms/query-aggregation.md) e le
  [tombstone](/architecture/tombstones.md).
