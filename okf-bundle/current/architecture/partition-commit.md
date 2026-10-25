---
type: Architecture Primitive
title: Commit della partizione
description: "Commit cumulativi nel tick corrente: conferma dello stato, pubblicazione al tick successivo e sequenza interna per il copy-on-write."
tags: [badspace, architecture, partitioning, transactions]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T15:16:14Z }
---

# Primitiva

Le scritture del writer sono confermate da **commit**: ogni commit è
una transazione limitata alla partizione. I lettori vedono solo stati
confermati, mai uno stato intermedio. La conferma non scrive su disco: il salvataggio
è un’operazione separata.

Non esistono transazioni tra partizioni. Con un solo writer (vedi
[proprietà della partizione](/current/architecture/partition-ownership.md)) il
commit non richiede gestione dei conflitti tra writer né abort per
concorrenza. Il lock della partizione lo ordina rispetto all’avanzamento
del tick e ai cambi di proprietà.

# Commit e tick

Il commit **avviene in un [tick](/current/architecture/logical-time.md)** e non
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
  snapshot](/current/architecture/snapshot-retention.md#commit-e-tick)). Un commit
  concorrente all'avanzamento è ordinato rispetto a esso: finisce nel
  tick che si chiude o in quello nuovo, e non cambia mai uno snapshot già
  pubblicato.

Il commit conferma lo **stato cumulativo** del writer: non azzera le
scritture già confermate. Con più commit nello stesso tick, l’ultimo
include anche gli effetti dei precedenti, salvo le successive modifiche
o rimozioni. I reader vedono solo lo stato dell’ultimo commit del tick,
non gli stati intermedi dei singoli commit.

Questo vale anche se cambia il proprietario nel tick: il nuovo writer
conserva i commit precedenti, ma non può leggerli finché non vengono
pubblicati. Il limite e le sue conseguenze per il software sono descritti
nella [visibilità nel tick del passaggio](/current/architecture/partition-ownership.md#visibilità-nel-tick-del-passaggio).

Per esempio, nel tick 10 il writer inserisce A e fa commit, poi
inserisce B e fa un secondo commit. Dopo `setTick(11)`, `S(10)` contiene
A e B. Se prima dell’avanzamento inserisce anche C senza un altro
commit, C resta nello stato del writer ma non entra in `S(10)`.

I [diagrammi temporali](#esempio-temporale-su-più-tick) mostrano questi
casi con i tick disposti da sinistra a destra.

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

Lo stato confermato si salva con un’operazione di
[persistenza](/current/decisions/persistence.md), dopo la pubblicazione.

## Esempio temporale su più tick

Nei tre esempi il tempo procede **da sinistra a destra**, lungo un unico
asse. I segni verticali con l’etichetta del tick ne indicano l’inizio:
tra due segni consecutivi si svolge un tick. Le frecce dall’alto segnano
il completamento dei commit; le barre azzurre sotto l’asse rappresentano
gli intervalli di scrittura. La durata interna del commit non è mostrata.

I riquadri verdi sotto i confini dei tick indicano lo snapshot che
diventa acquisibile in quel momento. Le linee tratteggiate allineano
la pubblicazione all’avanzamento del tick. Tra un commit e il confine
successivo, il nuovo stato è ancora in attesa: resta leggibile lo
snapshot pubblicato prima, non quello appena confermato.

Le distanze sono illustrative, non misure di prestazioni: il tick è
logico e non impone una durata reale. All’inizio di ciascun esempio è
già pubblicato `S(9)`, vuoto, e il proprietario resta lo stesso.
Gli snapshot precedenti restano acquisibili solo finché sono nella
finestra di conservazione.

### Un commit e un commit senza nuove scritture

Nel tick 10 il writer inserisce A e fa commit. Fino all’inizio del tick
11 resta visibile lo snapshot vuoto; poi diventa acquisibile `S(10)` con
A. Nel tick 11 un commit senza nuove scritture conferma lo stesso stato:
`S(11)` diventa acquisibile all’inizio del tick 12 e ha ancora A.

```mermaid
block-beta
    columns 12
    tick10["Tick 10"] space:2 commitA["Commit A"] tick11["Tick 11"] space:2 commitSame["Commit"] tick12["Tick 12"] space:3
    start10((" ")) space:2 pointA((" ")) start11((" ")) space:2 pointSame((" ")) start12((" ")) space:2 axisEnd[" "]
    space writeA["Scritture A"]:2 space:9
    snapshot9["S(9)<br/>vuoto"] space:3 snapshot10["S(10)<br/>con A"] space:3 snapshot11["S(11)<br/>con A"] space:3
    start10 --- pointA
    pointA --- start11
    start11 --- pointSame
    pointSame --- start12
    start12 --> axisEnd
    commitA --> pointA
    commitSame --> pointSame
    tick10 --- start10
    tick11 --- start11
    tick12 --- start12
    start10 -.-> snapshot9
    start11 -.-> snapshot10
    start12 -.-> snapshot11
    classDef label fill:none,stroke:none,color:#222
    classDef point fill:#222,stroke:#222
    classDef write fill:#e8f0ff,stroke:#5276b5
    classDef snapshot fill:#eaf5ec,stroke:#54815c
    class tick10,tick11,tick12,commitA,commitSame,axisEnd label
    class start10,start11,start12,pointA,pointSame point
    class writeA write
    class snapshot9,snapshot10,snapshot11 snapshot
```

### Più commit nello stesso tick

Nel tick 10 il primo commit conferma A e il secondo conferma A e B.
Solo lo stato del secondo viene pubblicato all’inizio del tick 11.
Le scritture di C, successive al secondo commit, restano non confermate;
il writer le conferma nel tick 11 e C diventa visibile nel tick 12.

```mermaid
block-beta
    columns 15
    tick10["Tick 10"] space commitA["Commit"] space commitAB["Commit"] space tick11["Tick 11"] space:2 commitABC["Commit"] space:2 tick12["Tick 12"] space:2
    start10((" ")) space pointA((" ")) space pointAB((" ")) space start11((" ")) space:2 pointABC((" ")) space:2 start12((" ")) space axisEnd[" "]
    space writeA["Scritture A"] space writeB["Scritture B"] space writeC["Scritture C"] space:9
    snapshot9["S(9)<br/>vuoto"] space:5 snapshot10["S(10)<br/>A e B"] space:5 snapshot11["S(11)<br/>A, B e C"] space:2
    start10 --- pointA
    pointA --- pointAB
    pointAB --- start11
    start11 --- pointABC
    pointABC --- start12
    start12 --> axisEnd
    commitA --> pointA
    commitAB --> pointAB
    commitABC --> pointABC
    tick10 --- start10
    tick11 --- start11
    tick12 --- start12
    start10 -.-> snapshot9
    start11 -.-> snapshot10
    start12 -.-> snapshot11
    classDef label fill:none,stroke:none,color:#222
    classDef point fill:#222,stroke:#222
    classDef write fill:#e8f0ff,stroke:#5276b5
    classDef snapshot fill:#eaf5ec,stroke:#54815c
    class tick10,tick11,tick12,commitA,commitAB,commitABC,axisEnd label
    class start10,start11,start12,pointA,pointAB,pointABC point
    class writeA,writeB,writeC write
    class snapshot9,snapshot10,snapshot11 snapshot
```

### Scritture su più tick prima del commit

Il writer distribuisce il lavoro del batch A sui tick 10, 11 e 12 e fa
commit solo nel tick 12. I tick senza commit ripetono il contenuto
precedente: `S(10)` e `S(11)` restano vuoti. Il batch compare in `S(12)`,
acquisibile dall’inizio del tick 13.

La barra lunga rappresenta una serie di scritture del writer, non una
singola chiamata a BADSPACE che trattiene il lock attraverso gli
avanzamenti del tick.

```mermaid
block-beta
    columns 16
    tick10["Tick 10"] space:3 tick11["Tick 11"] space:3 tick12["Tick 12"] space commitBatch["Commit"] space tick13["Tick 13"] space:3
    start10((" ")) space:3 start11((" ")) space:3 start12((" ")) space pointBatch((" ")) space start13((" ")) space:2 axisEnd[" "]
    space writeBatch["Scritture del batch A"]:9 space:6
    snapshot9["S(9)<br/>vuoto"] space:3 snapshot10["S(10)<br/>vuoto"] space:3 snapshot11["S(11)<br/>vuoto"] space:3 snapshot12["S(12)<br/>batch A"] space:3
    start10 --- start11
    start11 --- start12
    start12 --- pointBatch
    pointBatch --- start13
    start13 --> axisEnd
    commitBatch --> pointBatch
    tick10 --- start10
    tick11 --- start11
    tick12 --- start12
    tick13 --- start13
    start10 -.-> snapshot9
    start11 -.-> snapshot10
    start12 -.-> snapshot11
    start13 -.-> snapshot12
    classDef label fill:none,stroke:none,color:#222
    classDef point fill:#222,stroke:#222
    classDef write fill:#e8f0ff,stroke:#5276b5
    classDef snapshot fill:#eaf5ec,stroke:#54815c
    class tick10,tick11,tick12,tick13,commitBatch,axisEnd label
    class start10,start11,start12,start13,pointBatch point
    class writeBatch write
    class snapshot9,snapshot10,snapshot11,snapshot12 snapshot
```

Il commit prepara lo stato del tick, mentre l’avanzamento lo pubblica.
I nomi `S(n)` indicano snapshot logici: contenuti uguali possono essere
condivisi, senza una copia per ogni tick.

## Sequenza dei commit

Gli indici copy-on-write hanno comunque bisogno di distinguere un commit
dal successivo, anche dentro lo stesso tick: usano un contatore interno
a ogni partizione, la **sequenza dei commit**, che non esce dal nodo (vedi [isolamento
delle letture](/current/decisions/read-isolation.md#gli-indici)).

# Nel prototipo

Il commit si chiude con `commit()`, senza numero (vedi [API da
esporre](/current/api/operations.md#commit)). Al commit lo storage
produce lo snapshot della partizione, con lo snapshot degli slot e
quello dell'indice, che le scritture successive non cambiano, e lo
tiene in attesa: lo pubblica l'avanzamento del tick, e un commit
successivo nello stesso tick lo sostituisce (vedi [conservazione degli
snapshot](/current/architecture/snapshot-retention.md#nel-prototipo)). Ogni
commit ha un numero nella sequenza dei commit, interno allo storage,
che va a `commit(n)` degli indici. I lettori leggono lo snapshot
attraverso un'[acquisizione](/current/architecture/snapshot-retention.md#acquisizione-e-rilascio),
e vedono solo commit completi. Anche il writer legge così: non
interroga il proprio stato non confermato (vedi
[letture](/current/api/operations.md#letture)). Lo storage non ha letture
dello stato del writer: chi deve leggere quello che ha scritto lo fa sugli
snapshot, e nei test si confermano le scritture e si avanza il tick.

# Correlati

- [Architettura minima](/current/architecture/minimal-core.md) — ruolo nel nucleo corrente.
- [Tick logico](/current/architecture/logical-time.md) — il tick in cui avviene
  il commit.
- [Isolamento delle letture](/current/decisions/read-isolation.md) — ogni commit
  prepara uno snapshot che i lettori leggono senza lock.
- I [metadati delle entità](/current/architecture/entity-metadata.md) cambiano
  nello stesso commit dei dati.
