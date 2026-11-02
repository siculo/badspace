---
type: Architecture Primitive
title: Commit della partizione
description: "Commit cumulativi nel tick corrente: conferma dello stato, pubblicazione al tick successivo, sequenza interna per il copy-on-write e strutture transazionali con il loro ripristino."
tags: [badspace, architecture, partitioning, transactions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-02T14:54:29Z }
---

# Primitiva

`commit()` conferma lo stato completo del writer di una partizione,
con le posizioni e i metadati delle entità. È una transazione limitata alla
partizione; il [salvataggio su disco](/current/decisions/persistence.md)
è un’operazione separata.

# Commit e tick

Il commit prepara lo stato del [tick](/current/architecture/logical-time.md)
corrente T. Lo snapshot `S(T)` diventa acquisibile solo quando il tick
della partizione avanza oltre T. I tre diagrammi mostrano come questa
regola si applica a frequenze diverse di scrittura e commit.

## Esempio temporale su più tick

Il tempo procede **da sinistra a destra**. I segni verticali delimitano
i tick; le frecce dall’alto indicano commit completati. Le barre sotto
l’asse mostrano gli intervalli di scrittura; gli snapshot diventano
acquisibili al confine indicato dalla linea tratteggiata.

Le distanze sono illustrative e la durata interna del commit non è
mostrata. Ogni esempio parte da `S(9)` vuoto, con lo stesso proprietario
per tutta la durata. A, B e C sono entità; nell’ultimo esempio A indica
un batch di scritture.

### Un commit e un commit senza nuove scritture

Il commit di A nel tick 10 prepara `S(10)`, visibile dall’inizio del
tick 11. Il commit senza nuove scritture nel tick 11 è valido e produce
`S(11)` con lo stesso contenuto, visibile dal tick 12.

```text
        Tick 10            Commit A     Tick 11             Commit      Tick 12
           |                   |           |                   |           |
           |                   v           |                   v           |
tempo -----+-------------------*-----------+-------------------*-----------+---->
           :                               :                               :
           :  +-------------+              :                               :
           :  | Scritture A |              :                               :
           :  +-------------+              :                               :
           v                               v                               v
     "S(9) vuoto"                     "S(10): A"                      "S(11): A"
```

### Più commit nello stesso tick

I commit sono cumulativi: il secondo conserva A e aggiunge B. Solo il
contenuto dell’ultimo commit del tick diventa acquisibile. C, scritta
dopo quel commit, resta non confermata fino al commit del tick 11.

```text
        Tick 10   Commit    Commit      Tick 11             Commit      Tick 12
           |         |         |           |                   |           |
           |         v         v           |                   v           |
tempo -----+---------*---------*-----------+-------------------*-----------+---->
           :                               :                               :
           :  +-----+   +-----+   +-----+  :                               :
           :  |  A  |   |  B  |   |  C  |  :                               :
           :  +-----+   +-----+   +-----+  :                               :
           v                               v                               v
     "S(9) vuoto"                   "S(10): A e B"                 "S(11): A, B e C"
```

### Scritture su più tick prima del commit

Il writer completa il batch A nel tick 12. Nei tick 10 e 11 non fa
commit: i loro snapshot ripetono il contenuto precedente. Le scritture
restano nello stato del writer e diventano visibili insieme in `S(12)`,
dal tick 13.

La barra lunga rappresenta una serie di scritture, non una singola
chiamata che trattiene il lock durante gli avanzamenti del tick.

```text
     Tick 10               Tick 11               Tick 12     Commit    Tick 13
        |                     |                     |           |         |
        |                     |                     |           v         |
tempo --+---------------------+---------------------+-----------*---------+--------->
        :                     :                     :                     :
        :  +-------------------------------------------------+            :
        :  |             Scritture del batch A               |            :
        :  +-------------------------------------------------+            :
        :                     :                     :                     :
        v                     v                     v                     v
   "S(9) vuoto"          "S(10) vuoto"         "S(11) vuoto"       "S(12): batch A"
```

## Regole d’uso

Il software decide quando chiamare `commit()` e se attendere il tick
successivo prima di continuare. La chiamata non avanza il tick, non
attende il successivo e permette altre scritture e commit nello stesso
tick. Un server a frame può fare un commit per tick; un’elaborazione a
batch può distribuirsi su più tick, come nell’ultimo diagramma.

Tutti leggono attraverso snapshot pubblicati e
[acquisiti](/current/architecture/snapshot-retention.md#acquisizione-e-rilascio),
anche il writer: il commit non gli dà accesso al proprio stato ancora
in attesa. Ogni acquisizione vede un commit completo, mai uno stato
intermedio. Gli snapshot precedenti restano acquisibili finché sono nella
finestra di conservazione. Snapshot logici con lo stesso contenuto
possono condividerlo, senza una copia per ogni tick.

Il lock della partizione ordina commit, avanzamento del tick e cambi
di proprietà. Un commit concorrente all’avanzamento appartiene al tick
che si chiude oppure a quello nuovo; non modifica uno snapshot già
pubblicato. Non ci sono transazioni tra partizioni né conflitti tra
writer validi della stessa partizione: la
[proprietà](/current/architecture/partition-ownership.md) ne ammette uno solo.

Se cambia il proprietario nello stesso tick, il successore conserva i
commit precedenti senza poterli ancora leggere. Il limite è descritto
nella [visibilità nel tick del passaggio](/current/architecture/partition-ownership.md#visibilità-nel-tick-del-passaggio).

## Sequenza dei commit

`commit()` non riceve né restituisce un numero di commit. Gli indici
copy-on-write distinguono però i commit con una **sequenza interna alla
partizione**, che cresce anche con più commit nello stesso tick. Il
contatore non esce dal nodo e non è condiviso tra partizioni.

## Strutture transazionali

Lo stato del writer di una partizione è diviso in tre strutture: gli
slot con ID e posizioni delle entità, l’indice spaziale, che fa
riferimento agli slot, e i metadati applicativi. Ognuna è un
**componente transazionale**: segue lo stesso contratto per partecipare
ai commit, mentre le operazioni di modifica restano proprie di ciascuna.

- **Preparazione.** A ogni commit lo storage chiede a ogni struttura uno
  snapshot completo e immutabile del suo stato di lavoro, con la stessa
  sequenza del commit. Le scritture successive non lo modificano.
- **Ripristino.** Quando deve scartare le scritture non confermate, oggi
  al [cambio di proprietà](/current/architecture/partition-ownership.md#cambio-di-proprietà),
  lo storage riporta ogni struttura al contenuto dello snapshot
  dell’ultimo commit, o allo stato vuoto prima del primo. Lo snapshot non
  cambia, e dopo il ripristino la struttura accetta nuove scritture e
  nuovi commit.

Le strutture non conoscono il tick né il proprietario: sequenza dei
commit, ultimo stato confermato e snapshot in attesa sono dello storage.

L’ordine del ripristino è fisso: **slot, indice, metadati**. Gli slot
tornano con la stessa numerazione dello snapshot, quindi l’indice può
tornare al proprio snapshot dello stesso commit, che parla di quegli
slot, e ricostruire le mappe ausiliarie solo del writer. L’indice non
riceve notifiche per le scritture scartate. Nell’esempio, con il
commit C1 e tre scritture non confermate:

```text
 "commit C1"           "dopo le scritture"          "dopo il ripristino"
+------------+        +------------------+         +------------+
| 0: A (1,1) |        | 0: C (9,9)       |         | 0: A (1,1) |
| 1: B (5,5) | -----> | 1: B (6,6)       | ------> | 1: B (5,5) |
| 2: C (9,9) |        | 2: D (3,3)       |         | 2: C (9,9) |
+------------+        +------------------+         +------------+
                       "remove(A): C va nello slot 0"
                       "insert(D), move(B)"
```

Gli slot tornano uguali a quelli di C1; l’indice riprende le sue celle
di C1, che contengono gli slot 0, 1 e 2 con la stessa numerazione; i
metadati riprendono la mappa di C1.

# Nel prototipo

Lo storage prepara uno snapshot di slot, indice e metadati al commit;
un commit successivo nello stesso tick lo sostituisce. L’avanzamento
pubblica il nuovo catalogo degli snapshot con una scrittura atomica.

Il contratto delle strutture è l’interfaccia
`TransactionalComponent<S>` del package `badspace.service.transaction`,
con `prepareCommit(long)` e `restore(S)`; la implementano
`MutableSlots2`/`MutableSlots3`, gli indici attraverso
`SpatialIndex2`/`SpatialIndex3` e `MutableMetadata`. Le strutture fanno
il proprio ripristino:

- gli slot copiano ID, coordinate e tabella ID → slot dello snapshot; le
  pagine cambiate dopo il commit restano segnate, quindi il commit
  successivo le copia come dopo una scrittura;
- la grid uniforme e la griglia di quadtree o di octree riprendono la
  versione della mappa persistente delle celle e ricalcolano dalle celle
  la posizione di ogni slot nella sua cella o foglia; la scansione
  lineare non ha niente da ripristinare, perché legge gli slot;
- i metadati riprendono la versione della loro mappa persistente.

Senza scritture dopo l’ultimo commit gli slot e gli indici non fanno
niente, e i metadati riprendono solo la radice: il cambio di proprietà
senza scritture non dipende dal numero di entità. Con delle scritture
gli slot e gli indici costano O(n).

Una suite di test comune (`TransactionalComponentContract`) verifica
ogni struttura con un adattatore che crea la struttura, applica scritture
casuali e confronta il contenuto, non l’identità degli oggetti: slot in
ordine di slot, metadati per entità, per gli indici gli slot e i
risultati di alcune query fisse. Gli scenari sono gli snapshot immutabili
dopo nuove scritture, il ripristino dopo inserimenti, spostamenti e
rimozioni, quello allo stato vuoto e quello senza scritture, il
ripristino ripetuto, il commit senza scritture e i commit dopo un
ripristino, uguali a quelli di una struttura che ha avuto solo le
scritture non scartate. Per la griglia di quadtree e di octree la suite
controlla anche la struttura interna dopo ogni ripristino. Gli indici
sono provati insieme agli slot di prova dei loro test, che si
ripristinano prima dell’indice come nello storage.
Per strutture e garanzie, vedere
[isolamento delle letture](/current/decisions/read-isolation.md) e
[conservazione degli snapshot](/current/architecture/snapshot-retention.md#nel-prototipo).

# Correlati

- [Nucleo delle partizioni](/current/architecture/minimal-core.md) — garanzie del livello base.
- [Tick logico](/current/architecture/logical-time.md) — avanzamento e pubblicazione.
- [Metadati applicativi](/current/architecture/entity-metadata.md) — confermati insieme alle posizioni.
- [Contratto dell’API](/current/api/operations.md#commit) — chiamata ed errori.
