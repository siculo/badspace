---
type: Design Decision
title: Concorrenza
description: Concorrenza di BADSPACE risolta con un solo writer per partizione e più reader; il sistema nel suo insieme ha più writer senza lock granulari.
tags: [badspace, design, concurrency, partitioning]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T23:20:14Z }
---

# Opzioni considerate

- **Single-writer** — un solo scrittore, tipicamente il ciclo principale del software (il game loop in un gioco), con
  lettori concorrenti su snapshot. Semplice e deterministico.
- **Multi-writer** — lock granulari o strutture lock-free. Più
  scalabile, ma molto più complesso da mantenere coerente.

# Decisione

**Un solo writer per partizione**, con più reader. Con il
[partizionamento del DB](/decisions/partitioning.md) ogni partizione
resta single-writer, semplice e deterministica, ma il sistema nel suo
insieme ha più writer senza lock granulari: il trade-off tra
single-writer e multi-writer è di fatto risolto.

L'esclusività di scrittura si può acquisire, rilasciare e trasferire,
con un numero di generazione che blocca i writer non più proprietari
(vedi [proprietà della partizione](/architecture/partition-ownership.md)).
Le scritture sono raggruppate in [commit](/architecture/partition-commit.md)
che, con un solo writer, non richiedono lock né gestione dei conflitti.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — partire da una
  libreria embedded in-process mantiene questa decisione locale per ora.
- Come i reader vedono solo commit completi mentre il writer scrive è
  ancora da decidere: [isolamento delle letture](/decisions/read-isolation.md).
- Le operazioni che coinvolgono più partizioni si costruiscono sopra
  senza transazioni: [migrazione](/mechanisms/entity-migration.md) e
  [letture coerenti](/mechanisms/consistent-reads.md).
