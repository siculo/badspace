---
type: Architecture Primitive
title: Tick logico
description: "Tempo logico di spazio, nodi e partizioni: avanzamento monotono, salti, pubblicazione degli snapshot e propagazione non atomica."
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-27T20:31:26Z }
---

# Primitiva

Il livello base ha un **tempo logico**: un contatore monotono, il
**tick**. BADSPACE non fa avanzare il tempo logico con un clock;
le scadenze delle acquisizioni usano invece il clock monotono del nodo. È il software a decidere quando il
tick avanza, con `setTick(t)`:

- con `t` maggiore del tick corrente `T`, il tempo avanza fino a `t`;
- con `t` uguale a `T` non succede niente (idempotente);
- con `t` minore di `T` la chiamata è rifiutata
  (`IllegalArgumentException`);
- sono ammessi i **salti**: non serve chiamare `setTick` per ogni tick
  intermedio.

Un nodo nuovo parte dal tick 0.

Ogni partizione usa il **proprio** tick per interpretare
[commit](/current/architecture/partition-commit.md), pubblicazione e
[conservazione degli snapshot](/current/architecture/snapshot-retention.md) e
[rimozione](/current/architecture/partition-removal.md): lo snapshot `S(n)` è lo
stato confermato alla fine del tick n, e diventa acquisibile solo quando
il tick della partizione è maggiore di n.

# Tick dello spazio, del nodo e della partizione

| Tick | Chi lo tiene | Come avanza |
|---|---|---|
| gTick | Il software | È il tick globale definito dal software, esterno a BADSPACE |
| Tick dello spazio | Lo spazio, nell'API | `Space.setTick(t)`, chiamato dal software, di solito con il gTick |
| Tick del nodo | Il [nodo](/current/architecture/layers.md#nodi) | Lo spazio chiama `setTick(t)` su ognuno dei nodi che ha creato, anche senza partizioni |
| Tick della partizione | La partizione, sul nodo | Il nodo propaga il suo `setTick(t)` a ogni partizione che ospita |

Lo spazio tiene il suo tick e l'insieme dei nodi che ha creato con
`createNode`. Un nodo nuovo nasce al tick 0; lo spazio gli dà subito il
proprio tick con `setTick` e solo dopo lo registra e restituisce l'handle:
il salto in avanti è ammesso, e se il tick è uguale non cambia niente. Da
quel momento il nodo riceve ogni avanzamento, anche prima della sua prima
partizione, che nasce al tick corrente del nodo. Un nodo appartiene a un
solo spazio, quindi il tick del nodo è quello del suo spazio; spazi
diversi nello stesso processo hanno nodi distinti e avanzano in modo
indipendente.

Il lock dello spazio ordina `setTick`, la registrazione di un nodo e la
creazione delle partizioni. La factory costruisce il nodo fuori dal lock,
perché nessun altro lo vede ancora; il nodo viene portato al tick e
registrato sotto lock, quindi non perde un avanzamento avvenuto mentre la
factory lavorava. Se la factory o il `setTick` del nodo falliscono, il
nodo non viene registrato. Il caricamento di una partizione non prende il
lock: il nodo è già registrato e riceve gli avanzamenti mentre legge il
salvataggio.

Tutte le partizioni di uno spazio ricevono lo stesso tick. Anche le aree
che non si coordinano tra loro usano
un tick comune, che non costa niente. Se servono davvero ritmi diversi,
si usano spazi diversi, ognuno con i suoi nodi.

La barriera tra writer, se serve, resta fuori da BADSPACE: è il software
a non far avanzare il gTick finché i writer non hanno finito.

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
  snapshot](/current/architecture/snapshot-retention.md#nel-prototipo)), e il
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
  recente prima di N.
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
degli snapshot](/current/architecture/snapshot-retention.md#nel-prototipo)). La
rimozione segue la [semantica con gli
stati](/current/architecture/partition-removal.md#nel-prototipo). Il lock per partizione
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
  insieme per identità i nodi creati con `createNode`, e chiama `setTick`
  su un nodo nuovo prima di registrarlo. Un `ReentrantLock` ordina
  `setTick`, la registrazione dei nodi e `createPartition` dello spazio.
  `Partition` ha `tick()` e `creationTick()`.

I test verificano tick iniziale 0, avanzamento con salti, `setTick` con
lo stesso tick, rifiuto all'indietro senza cambiamenti, creazione al tick
del nodo, nodo nuovo portato al tick dello spazio, avanzamenti ricevuti
da un nodo senza partizioni, un solo `setTick` per nodo anche con più
partizioni, e la creazione di partizioni concorrente a una serie di
avanzamenti: alla fine nessuna partizione è rimasta indietro. Senza il
lock quest'ultimo test fallisce. Altri test, con latch e senza sleep,
verificano che un avanzamento durante la factory raggiunga il nodo nuovo,
che un avanzamento aspetti la registrazione, che un caricamento non
blocchi gli avanzamenti, e che due spazi con nodi distinti e gli stessi
generatori avanzino in modo indipendente.

# Correlati

- [Commit della partizione](/current/architecture/partition-commit.md) — il
  commit avviene in un tick.
- [Conservazione degli snapshot](/current/architecture/snapshot-retention.md) —
  uno snapshot per ogni tick, finestra di k tick.
- [Rimozione della partizione](/current/architecture/partition-removal.md) —
  rimozione effettiva al primo avanzamento del tick.
- [Architettura minima](/current/architecture/minimal-core.md) — ruolo nel nucleo corrente.
