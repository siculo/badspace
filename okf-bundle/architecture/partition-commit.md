---
type: Architecture Primitive
title: Commit della partizione
description: Le scritture del writer sono confermate da commit atomici, unica transazione del sistema; il commit non ha un numero visibile e avviene nel tick corrente della partizione, l'ultimo commit del tick definisce lo snapshot del tick, le scritture non confermate passano al tick successivo, e lo snapshot diventa visibile all'avanzamento del tick.
tags: [badspace, architecture, partitioning, transactions]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-16T10:08:30Z }
---

# Primitiva

Le scritture del writer sono confermate da **commit**: ogni commit è
una transazione limitata alla partizione. I lettori vedono solo stati
confermati, mai uno stato intermedio, e con la [durabilità
osservabile](/architecture/observable-durability.md) lo stato
confermato è anche durevole.

Non esistono transazioni tra partizioni. Con un solo writer (vedi
[proprietà della partizione](/architecture/partition-ownership.md)) il
commit non richiede lock, gestione dei conflitti né abort per
concorrenza.

# Commit e tick

Il commit **avviene in un [tick](/architecture/logical-time.md)** e non
ha un numero visibile: `commit()` conferma le scritture fatte fino a
quel momento e usa il tick corrente della partizione.

- **Più commit nello stesso tick** sono ammessi: l'ultimo commit del
  tick definisce lo snapshot `S(T)` del tick.
- **Senza commit** durante il tick T, `S(T)` ha lo stesso stato dello
  snapshot precedente.
- **Le scritture non confermate** a fine tick restano nello stato del
  writer e passano al tick successivo: un writer può impiegare più tick
  per completarle, e le conferma con il commit di un tick successivo.
  Non entrano mai in uno snapshot finché non sono confermate.
- **Visibilità.** `S(T)` diventa acquisibile solo all'avanzamento del
  tick, non alla fine del commit (vedi [conservazione degli
  snapshot](/architecture/snapshot-retention.md#commit-e-tick)). Un commit
  concorrente all'avanzamento è ordinato rispetto a esso: finisce nel
  tick che si chiude o in quello nuovo, e non cambia mai uno snapshot già
  pubblicato.

Il commit conferma lo **stato cumulativo** del writer: non azzera le
scritture già confermate. Con più commit nello stesso tick, l’ultimo
include anche gli effetti dei precedenti, salvo le successive modifiche
o rimozioni. I reader vedono solo lo stato dell’ultimo commit del tick,
non gli stati intermedi dei singoli commit.

Per esempio, nel tick 10 il writer inserisce A e fa commit, poi
inserisce B e fa un secondo commit. Dopo `setTick(11)`, `S(10)` contiene
A e B. Se prima dell’avanzamento inserisce anche C senza un altro
commit, C resta nello stato del writer ma non entra in `S(10)`.

`commit()` non fa avanzare il tick, non attende il tick successivo e
non impedisce nuove scritture nello stesso tick. È il software del
writer a scegliere quando fare commit e se attendere il tick successivo
prima di continuare. Se scrive e conferma in continuazione, per ogni
tick i reader possono acquisire solo lo stato dell’ultimo commit.

Nel caso tipico di un server a frame il writer fa un commit per tick.
Un uso a batch, per esempio per dati di posizione aggiornati a
intervalli, è lo stesso schema con un tempo di scrittura più lungo: il
writer distribuisce le scritture del batch su più tick e chiama
`commit()` alla fine; i tick senza commit ripetono lo stato precedente.

La persistenza dello stato confermato può arrivare dopo (vedi
[durabilità osservabile](/architecture/observable-durability.md)).

## Decisione ribaltata

Prima il livello base conosceva il commit e non il tick: il commit era
identificato da un contatore monotono scelto dal software, al più un
commit per tick, e il significato del contatore (tick locale, tick
globale, batch) lo stabilivano i livelli superiori. Lo snapshot di un
commit era acquisibile alla fine del commit. La scelta è ribaltata con
il [tick logico](/architecture/logical-time.md#perché-il-tick-sta-nel-livello-base):
con uno snapshot per ogni tick, il numero del commit non serve più, e
la pubblicazione avviene in un momento preciso.

Gli indici copy-on-write hanno comunque bisogno di distinguere un commit
dal successivo, anche dentro lo stesso tick: usano un contatore interno,
la **sequenza dei commit**, che non esce dal nodo (vedi [isolamento
delle letture](/decisions/read-isolation.md#gli-indici)).

# Nel prototipo

Il commit si chiude con `commit()`, senza numero (vedi [API da
esporre](/decisions/api-surface.md#commit)). Al commit lo storage
produce lo snapshot della partizione, con lo snapshot degli slot e
quello dell'indice, che le scritture successive non cambiano, e lo
tiene in attesa: lo pubblica l'avanzamento del tick, e un commit
successivo nello stesso tick lo sostituisce (vedi [conservazione degli
snapshot](/architecture/snapshot-retention.md#nel-prototipo)). Ogni
commit ha un numero nella sequenza dei commit, interno allo storage,
che va a `commit(n)` degli indici. I lettori leggono lo snapshot
attraverso un'[acquisizione](/architecture/snapshot-retention.md#acquisizione-e-rilascio),
e vedono solo commit completi; il writer vede le proprie scritture anche
prima del commit (vedi [letture del writer e dei
reader](/decisions/api-surface.md#letture-del-writer-e-dei-reader)).

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 2.
- [Tick logico](/architecture/logical-time.md) — il tick in cui avviene
  il commit.
- [Isolamento delle letture](/decisions/read-isolation.md) — ogni commit
  prepara uno snapshot che i lettori leggono senza lock.
- I [metadati delle entità](/architecture/entity-metadata.md) cambiano
  nello stesso commit dei dati.
