---
type: Reference
title: Architettura minima del layer delle partizioni
description: Le cinque primitive minime del layer delle partizioni e come i meccanismi (migrazione, letture coerenti, aggregazione, ribilanciamento) si costruiscono sopra di esse.
tags: [badspace, architecture, partitioning]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Obiettivo

Individuare l'insieme minimo di primitive del layer delle partizioni
che permetta di costruire, sopra, meccanismi diversi a seconda del caso
d'uso. Casi d'uso diversi hanno esigenze diverse, e il livello base
deve reggerli tutti senza far pagare a uno i costi dell'altro.

# Criterio

Nel livello base va solo ciò che **non si può costruire sopra** senza
rompere le garanzie. Tutto il resto è un meccanismo offerto dall'[API
comune](/architecture/layers.md), opzionale e configurabile.

Nessuna transazione tra partizioni: l'unica transazione è il
[commit](/architecture/partition-commit.md) dentro una singola
partizione. Con un solo writer non servono lock, gestione dei conflitti
né abort per concorrenza, che sono le parti costose di una
transazione.

# Primitive del livello base

1. [Proprietà della partizione](/architecture/partition-ownership.md) —
   single-writer con token di proprietà (fencing).
2. [Commit della partizione](/architecture/partition-commit.md) —
   commit atomico con contatore monotono.
3. [Metadati delle entità](/architecture/entity-metadata.md) — metadati
   di sistema e applicativi, scritture condizionate, con le
   [tombstone](/architecture/tombstones.md).
4. [Durabilità osservabile](/architecture/observable-durability.md) —
   ultimo commit persistito.
5. [Conservazione delle versioni](/architecture/version-retention.md) —
   ultimi k commit, k configurabile per partizione.

# Meccanismi sopra il livello base

| Meccanismo | Primitive usate |
|---|---|
| [Migrazione](/mechanisms/entity-migration.md) | 2, 3, 4 + un trasporto qualsiasi con reinvio |
| [Letture coerenti al tick N](/mechanisms/consistent-reads.md) | 2, 5 + tick globale o barriera |
| [Aggregazione](/mechanisms/query-aggregation.md) | nessuna primitiva speciale: fan-out e unione |
| [Ribilanciamento e split](/mechanisms/rebalancing-and-split.md) | 1 |
| Tick globale e barriera | servizio di coordinamento esterno, opzionale |

# Verifica sui casi d'uso

- **Aree non contigue** (per esempio sistemi solari separati da
  distanze enormi, dove ciò che sta al confine è irrilevante). Ogni
  partizione ha il suo tick; non servono tick globale, conservazione
  delle versioni (k = 0) né aggregazione. Il viaggio di una nave tra
  due sistemi usa solo la migrazione.
- **Zone contigue con confini.** Si aggiungono il tick globale, la
  conservazione delle versioni (k ≥ 1) sulle partizioni di confine e le
  letture coerenti con aggregazione.

# Punti aperti

- Forma concreta delle chiamate per commit, scritture condizionate,
  API dei metadati e gestione delle tombstone.
- Trasporto dei messaggi di migrazione: fornito dall'API o lasciato al
  software utilizzatore.
- Implementazione e costo della conservazione delle versioni.
- Servizio del tick globale e della barriera nella forma a cluster.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
