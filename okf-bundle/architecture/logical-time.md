---
type: Architecture Primitive
title: Tick logico
description: Ogni spazio, nodo e partizione ha un tick logico, un contatore monotono che il software fa avanzare con setTick (idempotente se il valore è uguale, con salti ammessi, rifiutato all'indietro); la partizione usa il suo tick per commit, pubblicazione degli snapshot, conservazione e rimozione, e la propagazione dal nodo alle partizioni non è atomica.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T16:00:00Z }
---

# Primitiva

Il livello base ha un **tempo logico**: un contatore monotono, il
**tick**. BADSPACE non ha un clock. È il software a decidere quando il
tick avanza, con `setTick(t)`:

- con `t` maggiore del tick corrente `T`, il tempo avanza fino a `t`;
- con `t` uguale a `T` non succede niente (idempotente);
- con `t` minore di `T` la chiamata è rifiutata
  (`IllegalArgumentException`);
- sono ammessi i **salti**: non serve chiamare `setTick` per ogni tick
  intermedio.

Un nodo nuovo parte dal tick 0.

Ogni partizione usa il **proprio** tick per interpretare
[commit](/architecture/partition-commit.md), pubblicazione e
[conservazione degli snapshot](/architecture/snapshot-retention.md) e
[rimozione](/architecture/partition-removal.md): lo snapshot `S(n)` è lo
stato confermato alla fine del tick n, e diventa acquisibile solo quando
il tick della partizione è maggiore di n.

# Tick dello spazio, del nodo e della partizione

| Tick | Chi lo tiene | Come avanza |
|---|---|---|
| gTick | Il software | È il tick globale definito dal software, esterno a BADSPACE |
| Tick dello spazio | Lo spazio, nell'API | `Space.setTick(t)`, chiamato dal software, di solito con il gTick |
| Tick del nodo | Il [nodo](/architecture/layers.md#nodi) | Lo spazio chiama `setTick(t)` su ognuno dei nodi su cui ha creato partizioni |
| Tick della partizione | La partizione, sul nodo | Il nodo propaga il suo `setTick(t)` a ogni partizione che ospita |

Lo spazio tiene il suo tick e l'insieme dei nodi su cui ha creato
partizioni. Su un nodo nuovo chiama `setTick` con il tick dello spazio
prima di `createPartition`: il salto in avanti è ammesso, e se il tick è
uguale non cambia niente. Un nodo serve un solo spazio, quindi il tick
del nodo è quello del suo spazio.

Tutte le partizioni di uno spazio ricevono lo stesso tick. Anche le aree
che non si coordinano tra loro (vedi [casi d'uso](/use-cases.md)) usano
un tick comune, che non costa niente. Se servono davvero ritmi diversi,
si usano spazi diversi, ognuno con i suoi nodi.

La barriera tra writer, se serve, resta fuori da BADSPACE: è il software
a non far avanzare il gTick finché i writer non hanno finito (vedi
[letture coerenti](/mechanisms/consistent-reads.md)).

# Propagazione non atomica

La propagazione del tick dal nodo alle partizioni **non è atomica**:
partizioni diverse completano la transizione in momenti diversi, e
durante la propagazione il tick di una partizione può restare indietro
rispetto a quello del nodo. I reader che leggono più partizioni devono
tollerare per un momento tick correnti diversi.

Sulla **singola partizione**, invece, la transizione è atomica:
pubblicazione degli snapshot e applicazione della rimozione non si
vedono mai a metà.

# Ordine con le altre operazioni

`setTick` arriva dal thread che fa avanzare il tempo, di solito diverso
dal writer della partizione. L'avanzamento e le operazioni sulla stessa
partizione devono avere un ordine definito:

- **Lock per partizione** su operazioni del writer, `commit()`,
  `setTick` e `remove()`. Un commit accettato prima dell'avanzamento
  finisce nello snapshot del tick che si chiude; uno accettato dopo, nel
  tick nuovo. Un commit non cambia mai uno snapshot già pubblicato.
- **I reader restano senza lock**: l'acquisizione legge il catalogo
  pubblicato dall'ultimo avanzamento (vedi [conservazione degli
  snapshot](/architecture/snapshot-retention.md#nel-prototipo)), e il
  writer non aspetta mai i reader.
- **Creazione e avanzamento del nodo** hanno un ordine tra loro: una
  partizione creata mentre il nodo propaga un tick non deve restare
  indietro di un tick.

# Perché il tick sta nel livello base

Prima il livello base conosceva solo il commit, identificato da un
numero scelto dal software, e il tick restava ai livelli superiori.
La scelta è ribaltata per quattro motivi:

- **Acquisizione esatta.** Con uno snapshot per ogni tick, l'acquisizione
  al tick N trova esattamente lo stato di N, senza cercare il commit più
  recente prima di N. La [lettura coerente](/mechanisms/consistent-reads.md)
  su più partizioni si semplifica.
- **Conservazione nel tempo.** La finestra di conservazione si misura in
  tick, indipendentemente da quanto spesso il writer fa commit.
- **Pubblicazione in un momento preciso.** Lo snapshot di un tick diventa
  visibile all'avanzamento del tick: nessun reader vede lo stato del
  tick corrente.
- **Esistenza in un intervallo preciso.** Creazione e rimozione legate a
  un tick danno a ogni partizione un intervallo di esistenza
  `[creationTick, removalTick)`, su cui si basano le letture storiche.

# Nel prototipo

Il tick regola commit, pubblicazione e acquisizioni (vedi [conservazione
degli snapshot](/architecture/snapshot-retention.md#nel-prototipo)). La
rimozione segue la [semantica con gli
stati](/architecture/partition-removal.md#nel-prototipo). Il lock per partizione
è un `ReentrantLock` nello storage, preso da scritture, `commit()`,
richiesta di rimozione e `setTick`, mai dai reader.

- **Contratto del nodo** (`PartitionNode2`/`PartitionNode3`):
  `setTick(long)`, `tick()`, `tick(PartitionId)` e
  `creationTick(PartitionId)`; la creazione avviene al tick corrente del
  nodo.
- **Nodo locale**: il tick del nodo è un campo `volatile`, scritto da
  `setTick` e letto da ogni thread. Un `ReentrantLock` ordina `setTick` e
  `createPartition`: una partizione creata durante un avanzamento nasce
  prima, e allora avanza anche lei, o dopo, al tick nuovo. `setTick`
  avanza anche le partizioni rimosse, e in ogni partizione cerca le
  acquisizioni scadute.
- **Storage**: tick e tick di creazione stanno nel catalogo degli
  snapshot, pubblicato a ogni avanzamento; `setTick` dello storage
  pubblica lo snapshot in attesa e restituisce vero se finisce una
  partizione rimossa, e il nodo allora la toglie.
- **API**: `Space.setTick(long)` e `tick()`; lo spazio tiene in un
  insieme per identità i nodi su cui ha creato partizioni, e su un nodo
  nuovo chiama `setTick` con il suo tick prima della creazione. Un
  `ReentrantLock` ordina `setTick` e `createPartition` dello spazio.
  `Partition` ha `tick()` e `creationTick()`.

I test verificano tick iniziale 0, avanzamento con salti, `setTick` con
lo stesso tick, rifiuto all'indietro senza cambiamenti, creazione al tick
del nodo, nodo nuovo portato al tick dello spazio, un solo `setTick` per
nodo anche con più partizioni, e la creazione di partizioni concorrente a
una serie di avanzamenti: alla fine nessuna partizione è rimasta
indietro. Senza il lock quest'ultimo test fallisce.

# Correlati

- [Commit della partizione](/architecture/partition-commit.md) — il
  commit avviene in un tick.
- [Conservazione degli snapshot](/architecture/snapshot-retention.md) —
  uno snapshot per ogni tick, finestra di k tick.
- [Rimozione della partizione](/architecture/partition-removal.md) —
  rimozione effettiva al primo avanzamento del tick.
- [Architettura minima](/architecture/minimal-core.md) — primitiva 6.
