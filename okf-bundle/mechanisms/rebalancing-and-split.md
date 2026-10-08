---
type: Protocol
title: Ribilanciamento e split
description: Operazioni in blocco sulle partizioni realizzate trasferendo l'esclusività di scrittura invece di migrare le entità una per una.
tags: [badspace, partitioning, protocol]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-08T07:45:42Z }
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

# Punti aperti

- **Doppioni durante lo split.** Mentre la partizione nuova si riempie,
  un'entità può stare in tutte e due le partizioni. Le letture su
  entrambe la deduplicano per ID, come nell'[aggregazione dei
  risultati](/mechanisms/query-aggregation.md#deduplica). Nel prototipo
  lo split viene prima della [migrazione](/mechanisms/entity-migration.md),
  quindi la deduplica per ID, senza epoca, deve bastare.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md) — lo
  sbilanciamento del carico resta un problema della strategia scelta.
