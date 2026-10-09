---
type: Architecture Primitive
title: Commit della partizione
description: Le scritture del writer sono raggruppate in commit atomici, unica transazione del sistema, identificati da un contatore monotono il cui significato (tick locale, tick globale, batch) è deciso sopra il livello base.
tags: [badspace, architecture, partitioning, transactions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-09T13:11:53Z }
---

# Primitiva

Le scritture del writer sono raggruppate in **commit**: ogni commit è
una transazione limitata alla partizione. I lettori vedono solo commit
completi, mai uno stato intermedio, e con la [durabilità
osservabile](/architecture/observable-durability.md) il commit è anche
durevole.

Non esistono transazioni tra partizioni. Con un solo writer (vedi
[proprietà della partizione](/architecture/partition-ownership.md)) il
commit non richiede lock, gestione dei conflitti né abort per
concorrenza.

# Commit e tick

Ogni commit è identificato da un **contatore monotono**. Il livello
base conosce il commit, non il tick: il significato del contatore lo
stabiliscono i livelli superiori.

Per partizione si fa **al più un commit per tick**, mentre un commit può
coprire più tick. La persistenza del commit può arrivare dopo (vedi
[durabilità osservabile](/architecture/observable-durability.md)).

Nel caso tipico si fa esattamente un commit per tick, e il contatore è:

- il tick locale della partizione, per partizioni indipendenti;
- il tick globale, quando servono [letture
  coerenti](/mechanisms/consistent-reads.md) su più partizioni.

Un uso non legato al gioco potrebbe invece fare commit a batch.

# Nel prototipo

Il commit si chiude con `commit(n)`, con il numero n scelto dal
software e maggiore dell'ultimo commit (vedi [API da
esporre](/decisions/api-surface.md#commit)). Al commit lo storage
produce la versione della partizione, con lo snapshot degli slot e la
versione dell'indice, che le scritture successive non cambiano (vedi
[isolamento delle letture](/decisions/read-isolation.md#gli-slot)). La
versione non è ancora pubblicata: il prototipo non ha ancora il confine
del commit, e ogni scrittura è visibile ai lettori appena eseguita.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 2.
- [Isolamento delle letture](/decisions/read-isolation.md) — ogni commit
  pubblica uno snapshot che i lettori leggono senza lock.
- I [metadati delle entità](/architecture/entity-metadata.md) cambiano
  nello stesso commit dei dati.
