---
type: Architecture Primitive
title: Durabilità osservabile
description: Ogni partizione espone l'ultimo commit persistito, così le conferme verso altre partizioni si danno solo per scritture durevoli.
tags: [badspace, architecture, partitioning, persistence]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Primitiva

La partizione espone l'**ultimo [commit](/architecture/partition-commit.md)
persistito**. Chi deve confermare qualcosa ad altri può farlo solo
quando la scrittura è durevole.

# Uso

Nella [migrazione](/mechanisms/entity-migration.md):

- A persiste la marcatura "in uscita" **prima** di spedire il batch,
  così dopo un crash sa di doverlo reinviare;
- B manda l'ACK solo **dopo** che l'inserimento è persistito,
  altrimenti un crash di B dopo la rimozione in A lascerebbe zero copie.

La latenza della migrazione dipende quindi dalla frequenza della
[persistenza](/decisions/persistence.md).

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 4.
