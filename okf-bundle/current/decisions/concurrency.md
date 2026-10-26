---
type: Design Decision
title: Concorrenza
description: Concorrenza di BADSPACE risolta con un solo writer per partizione e più reader; il sistema nel suo insieme ha più writer senza lock granulari.
tags: [badspace, design, concurrency, partitioning]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Opzioni considerate

- **Single-writer** — un solo scrittore, tipicamente il ciclo principale del software (il game loop in un gioco), con
  lettori concorrenti su snapshot. Semplice e deterministico.
- **Multi-writer** — lock granulari o strutture lock-free. Più
  scalabile, ma molto più complesso da mantenere coerente.

# Decisione

**Un solo writer per partizione**, con più reader. Con il
[partizionamento del DB](/current/decisions/partitioning.md) ogni partizione
resta single-writer, semplice e deterministica, ma il sistema nel suo
insieme ha più writer senza lock granulari: il trade-off tra
single-writer e multi-writer è di fatto risolto.

L'esclusività di scrittura si può acquisire, rilasciare e trasferire,
con un numero di generazione che blocca i writer non più proprietari
(vedi [proprietà della partizione](/current/architecture/partition-ownership.md)).
Le scritture sono raggruppate in [commit](/current/architecture/partition-commit.md)
che non richiedono gestione dei conflitti tra writer validi.
Il lock della partizione ordina comunque scritture, commit, cambi di
proprietà e avanzamenti del tick.

I reader non bloccano mai il writer: il writer non aspetta query,
acquisizioni o persistenza. L'avanzamento del
[tick](/current/architecture/logical-time.md) arriva invece da un altro thread,
e si serializza con le operazioni del writer della stessa partizione
con un lock per partizione: il writer può aspettare un avanzamento, che
è breve, ma mai un reader. Le acquisizioni degli snapshot appartengono
al nodo, e un reader lento allunga solo la vita degli snapshot in
memoria (vedi [conservazione degli
snapshot](/current/architecture/snapshot-retention.md#vita-di-uno-snapshot)).

# Correlati

- Il [tipo di strumento](/current/decisions/tool-type.md) — partire da una
  libreria embedded in-process mantiene questa decisione locale per ora.
- Come i reader vedono solo commit completi mentre il writer scrive:
  [isolamento delle letture](/current/decisions/read-isolation.md), con snapshot
  preparati a ogni commit e pubblicati all'avanzamento del tick.
