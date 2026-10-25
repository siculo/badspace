---
type: Protocol
title: Ribilanciamento e split
description: Operazioni in blocco sulle partizioni realizzate trasferendo l'esclusività di scrittura invece di migrare le entità una per una.
tags: [badspace, partitioning, protocol]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato e prerequisiti

Questo documento descrive uno sviluppo futuro, non una funzionalità disponibile nel prototipo. Le primitive già implementate si consultano nella [documentazione corrente](/current/index.md).

# Meccanismo

Usano il trasferimento dell'esclusività della [proprietà della
partizione](/current/architecture/partition-ownership.md), non la
[migrazione](/future/mechanisms/entity-migration.md) entità per entità:

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
  risultati](/future/mechanisms/query-aggregation.md#deduplica). Nel prototipo
  lo split viene prima della [migrazione](/future/mechanisms/entity-migration.md),
  quindi la deduplica per ID, senza epoca, deve bastare.

# Correlati

- [Partizionamento del DB](/current/decisions/partitioning.md) — lo
  sbilanciamento del carico resta un problema della strategia scelta.
