---
type: Architecture Primitive
title: Proprietà della partizione
description: Ogni partizione ha un solo writer; l'esclusività si acquisisce, rilascia e trasferisce con un numero di generazione (fencing token) che blocca i writer non più proprietari.
tags: [badspace, architecture, partitioning, concurrency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Primitiva

Ogni partizione ha un solo writer e più reader. L'esclusività di
scrittura si può **acquisire, rilasciare e trasferire**.

Ogni passaggio incrementa un **numero di generazione** (fencing
token): la partizione rifiuta le scritture che portano una generazione
vecchia. Senza questo, un writer rallentato che ha perso la proprietà
potrebbe ancora scrivere.

# Uso

- Base della decisione sulla [concorrenza](/decisions/concurrency.md).
- Il trasferimento dell'esclusività serve alle operazioni in blocco
  ([ribilanciamento e split](/mechanisms/rebalancing-and-split.md)),
  non alle migrazioni di routine, che usano la
  [migrazione](/mechanisms/entity-migration.md).

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 1.
