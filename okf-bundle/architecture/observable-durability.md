---
type: Architecture Primitive
title: Durabilità osservabile
description: Ogni partizione espone l'ultimo tick persistito, così le conferme verso altre partizioni si danno solo per scritture durevoli.
tags: [badspace, architecture, partitioning, persistence]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-19T14:17:50Z }
---

# Primitiva

La partizione espone l'**ultimo tick persistito**: il tick n del più
recente snapshot `S(n)` salvato, cioè dell'ultimo stato confermato da
[commit](/architecture/partition-commit.md) che è durevole. Il commit
non ha un numero visibile, quindi lo stato persistito si indica con il
[tick](/architecture/logical-time.md). Chi deve confermare qualcosa ad altri può farlo solo
quando la scrittura è durevole.

# Introduzione nel prototipo

L’esposizione dell’ultimo tick persistito è rimandata ai protocolli che
richiedono conferme durevoli e al recupero coerente dei nodi. Il primo
blocco di [persistenza](/decisions/persistence.md) salva e carica il
contenuto delle singole partizioni e ne misura i costi; non richiede
ancora questa primitiva.

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
