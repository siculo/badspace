---
type: Protocol
title: Ribilanciamento e split
description: Operazioni in blocco sulle partizioni realizzate trasferendo l'esclusività di scrittura invece di migrare le entità una per una.
tags: [badspace, partitioning, protocol]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Meccanismo

Usano il trasferimento dell'esclusività della [proprietà della
partizione](/architecture/partition-ownership.md), non la
[migrazione](/mechanisms/entity-migration.md) entità per entità:

- **Ribilanciamento:** invece di copiare migliaia di entità si passa
  l'intera partizione a un altro writer, senza copie.
- **Split:** si crea una partizione vuota posseduta da A, la si
  riempie, poi se ne cede l'esclusività a un altro writer.

Il numero di generazione garantisce che il writer precedente non possa
più scrivere dopo il passaggio.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md) — lo
  sbilanciamento del carico resta un problema della strategia scelta.
