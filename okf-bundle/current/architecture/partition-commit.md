---
type: Architecture Primitive
title: Commit della partizione
description: "Commit cumulativi nel tick corrente: scritture accumulate nel writer e inviate in un batch, conferma dello stato, pubblicazione al tick successivo, sequenza interna per il copy-on-write e strutture transazionali con il loro ripristino."
tags: [badspace, architecture, partitioning, transactions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-05T10:14:34Z }
---

# Primitiva

`commit()` manda al nodo le scritture del writer di una partizione e le
conferma: lo stato confermato è completo, con le posizioni e i metadati
delle entità. È una transazione limitata alla
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
restano nell'handle del writer e diventano visibili insieme in `S(12)`,
dal tick 13.

La barra lunga rappresenta una serie di scritture nell'handle, che non
chiamano il nodo e non trattengono il lock della partizione durante gli
avanzamenti del tick: il lock lo prende solo il commit.

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

## Scritture accumulate nel writer

Le scritture del writer restano nel suo
[handle](/current/api/operations.md#proprietà-e-handle-del-writer),
nel processo del software, e non chiamano il nodo: `commit()` le manda
tutte insieme, in una sola chiamata, con il token. Il nodo applica il
batch all'**ultimo commit** della partizione, anche se aspetta ancora
l'avanzamento del tick, non allo snapshot che il writer ha letto, e lo
conferma nello stesso passo, sotto il lock della partizione. Il
comportamento è lo stesso per un nodo locale e per un nodo remoto
futuro.

```text
  writer (handle)                                      nodo
  +-----------------------+                            +-------------------------+
  | insert, set, remove,  |                            |                         |
  | metadati: nessuna     | "commit(token, batch)"     | applica il batch        |
  | chiamata al nodo      |                            | all'ultimo commit e lo  |
  |                       | -------------------------->| conferma nel tick       |
  | buffer: una modifica  |                            | corrente, in un passo   |
  | finale per entità     |                            |                         |
  +-----------------------+                            +-------------------------+
```

Il diagramma mostra un solo commit; i controlli e i fallimenti sono più
sotto e nei [fallimenti della partizione](/current/architecture/partition-failures.md).

Il buffer non tiene la lista delle chiamate: per ogni entità tiene la
**modifica finale** rispetto alla base, cioè l'ultimo commit. Il
risultato è sempre quello delle chiamate eseguite una alla volta, nel
loro ordine, sulla base: `insert` genera l'ID e lo restituisce subito,
prima del commit; `set` sposta l'entità o la reinserisce con lo stesso
ID; `remove` la toglie con i metadati applicativi e non fa niente su
un'entità assente; una scrittura dei metadati su un'entità che in quel
momento non c'è non fa niente. Una `set` conserva i metadati non
modificati, e per ogni chiave resta solo l'ultima scrittura, che sia un
valore o una cancellazione. L'ordine delle chiamate conta solo nella
composizione: al nodo non arriva una sequenza, e il batch non deve
conoscere o copiare il contenuto della base.

| Chiamate nel writer | Effetto da trasferire |
|---|---|
| `set(A, p1)`, `set(A, p2)` | A presente in p2 |
| `set(A, p1)`, `remove(A)` | A assente, senza metadati |
| `remove(A)`, `set(A, p2)` | A presente in p2, con azzeramento dei vecchi metadati |
| Due scritture sulla stessa chiave | Solo l'ultima modifica |
| Scrittura di metadati, poi `remove(A)` | A assente; scritture dei metadati scartate |
| `remove(A)`, scrittura di metadati | A assente; la scrittura non fa niente |
| Scrittura di metadati, poi `set(A, p)` | A presente in p; la scrittura vale solo se A era nella base |
| `set(A, p)`, scrittura di metadati | A presente in p con la scrittura, qualunque sia la base |

Il caso rimozione e reinserimento richiede di conservare nel batch
l'azzeramento dei metadati precedenti: la sola posizione finale non basta.

Il writer non conosce la base, quindi per ogni entità il batch dice cosa
fare in entrambi i casi. La modifica finale di un'entità ha quattro parti,
applicate in quest'ordine:

1. **rimozione**, se nel buffer c'è una `remove`: l'entità della base,
   se c'è, esce con i suoi metadati;
2. **posizione**, se dopo l'ultima `remove` c'è una `set`: l'entità si
   sposta conservando i metadati, oppure si aggiunge senza metadati;
3. **metadati della base**: le scritture fatte prima di ogni `set` o
   `remove` dell'entità, quando la sua presenza non era nota; valgono
   solo se l'entità era nella base;
4. **metadati dopo la `set`**: valgono sempre, perché l'entità c'è.

Ogni gruppo tiene una sola scrittura per chiave; una scrittura dopo la
`set` sostituisce quella della stessa chiave che dipende dalla base.
Le parti 1 e 3 non stanno mai insieme, perché la `remove` scarta le
scritture precedenti, e la 4 richiede una posizione.

```text
   "put(A, k)"      "set(A, p)"      "put(A, j)"
        |                |                |
        v                v                v
  +------------+   +------------+   +------------+
  | metadati   |   | posizione  |   | metadati   |
  | della base |   | p          |   | dopo la    |
  | "k"        |   |            |   | set: "j"   |
  +------------+   +------------+   +------------+

  A nella base:     A in p con i vecchi metadati, k e j
  A non nella base: A in p con j soltanto
```

Il diagramma mostra una sola entità senza `remove`; le parti si
applicano da sinistra a destra.

Le modifiche di entità diverse sono indipendenti; non si introducono
vincoli tra entità o transazioni tra partizioni.

Ridurre le modifiche prima dell'applicazione evita aggiornamenti ripetuti
dell'indice per la stessa entità. Il costo si sposta verso il buffer del
client e il commit; il beneficio sui nodi locali non è ancora misurato.

L'handle controlla subito i limiti delle posizioni: una scrittura fuori
dai limiti non entra nel buffer. Token, stato e salute della partizione
li controlla il nodo al commit, insieme di nuovo ai limiti e alla
presenza di una sola modifica per entità; un batch rifiutato non cambia
la partizione. Dopo un commit riuscito il buffer è vuoto; dopo un commit
che fallisce il buffer conserva le scritture. `rollback()` e `close()`
scartano il buffer, poi vanno al nodo per il ripristino e per il
rilascio; tra un commit e l'altro il nodo non ha scritture del writer da
scartare.

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
  sequenza del commit. Le scritture successive non lo modificano. La
  preparazione non cambia la base della struttura, cioè lo snapshot da
  cui riparte il commit successivo.
- **Accettazione.** Solo quando tutte le strutture hanno preparato il
  proprio snapshot, e lo storage ha costruito quello della partizione,
  ogni struttura lo accetta come nuova base. L’accettazione è un passo
  minimo che non può fallire, quindi il commit è **tutto o niente**.
- **Abbandono.** Se una preparazione fallisce, lo storage abbandona gli
  snapshot già preparati: le strutture ne liberano le risorse senza
  toccare i dati condivisi con altri snapshot.
- **Ripristino.** Quando deve scartare le scritture non confermate, al
  [cambio di proprietà](/current/architecture/partition-ownership.md#cambio-di-proprietà),
  al [rollback](/current/architecture/partition-failures.md#rollback) o dopo un fallimento,
  lo storage riporta ogni struttura al contenuto dello snapshot
  dell’ultimo commit, o allo stato vuoto prima del primo. Lo snapshot non
  cambia, e dopo il ripristino la struttura accetta nuove scritture e
  nuovi commit.

Le strutture non conoscono il tick né il proprietario: sequenza dei
commit, ultimo stato confermato e snapshot in attesa sono dello storage.

Un commit che fallisce durante la preparazione non conferma niente:
ultimo stato confermato e snapshot in attesa restano quelli di prima e
sono ancora pubblicati al cambio di tick. Il commit fallito usa comunque
un numero della sequenza, che non torna indietro. Poi la partizione
annulla le scritture non confermate e segnala il fallimento, come per
ogni [fallimento del writer](/current/architecture/partition-failures.md).

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

Il buffer delle scritture è `WriteBuffer2`/`WriteBuffer3` in `common`,
che compone le scritture in un `EntityWrite2`/`EntityWrite3` per
entità; l'handle del writer ne ha uno. I test lo confrontano con
l'esecuzione delle chiamate una alla volta, anche su 5000 sequenze
casuali di `set`, `remove` e scritture dei metadati su basi casuali. Il
contratto del nodo ha `commit(WriteToken, List<EntityWrite2>)` al posto
delle singole scritture; lo storage applica le modifiche e prepara il
commit sotto lo stesso lock. I test del nodo scrivono attraverso un
piccolo client dei test con un buffer per token, come l'handle; i
benchmark delle scritture misurano slot e indice senza il commit,
scrivendo direttamente nello storage del nodo locale, perché restino
confrontabili con le misure precedenti.

Lo storage prepara uno snapshot di slot, indice e metadati al commit;
un commit successivo nello stesso tick lo sostituisce. L’avanzamento
pubblica il nuovo catalogo degli snapshot con una scrittura atomica.

Il contratto delle strutture è l’interfaccia
`TransactionalComponent<S>` del package `badspace.service.transaction`,
con `prepareCommit(long)`, `acceptCommit(S)`, `abandonCommit(S)` e
`restore(S)`; la implementano
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

Per indici e metadati la preparazione rende già di sola lettura i nodi
copy-on-write dello snapshot, quindi accettazione e abbandono non fanno
niente: uno snapshot abbandonato si libera quando nessuno lo riferisce.
Per gli slot l’accettazione azzera i segni delle modifiche e rende lo
snapshot la base da condividere; l’abbandono toglie lo snapshot dagli
utilizzatori del suo buffer delle coordinate. Un buffer preso dal pool
torna nel pool senza commit, perché un ripristino successivo può
riportare indietro pagine senza segnarle, e il commit dopo ci copia
tutte le pagine; un buffer condiviso con la base resta alla base.
Lo snapshot in attesa che un commit dello stesso tick sostituisce
restituisce il suo buffer solo dopo l’accettazione: un commit fallito lo
lascia intatto, e il buffer passa al commit successivo.

Senza scritture dopo l’ultimo commit gli slot e gli indici non fanno
niente, e i metadati riprendono solo la radice: il cambio di proprietà
senza scritture non dipende dal numero di entità. Con delle scritture
gli slot e gli indici costano O(n).

Una suite di test comune (`TransactionalComponentContract`) verifica
ogni struttura con un adattatore che crea la struttura, applica scritture
casuali e confronta il contenuto, non l’identità degli oggetti: slot in
ordine di slot, metadati per entità, per gli indici gli slot e i
risultati di alcune query fisse. Gli scenari sono gli snapshot immutabili
dopo nuove scritture, anche quelli più vecchi dello snapshot ripristinato,
che il ripristino condivide con lo stato di lavoro, il ripristino dopo inserimenti, spostamenti e
rimozioni, quello allo stato vuoto e quello senza scritture, il
ripristino ripetuto, il commit senza scritture, i commit dopo un
ripristino, uguali a quelli di una struttura che ha avuto solo le
scritture non scartate, lo snapshot accettato come nuova base e la
preparazione abbandonata, dopo la quale il ripristino torna alla base
e i commit successivi hanno tutte le scritture. Per la griglia di quadtree e di octree la suite
controlla anche la struttura interna dopo ogni ripristino. Gli indici
sono provati insieme agli slot di prova dei loro test, che si
ripristinano prima dell’indice come nello storage.

I test dello storage, in 2D e 3D, fanno fallire la preparazione degli
slot, con un pool dei buffer che fallisce, e quella dell’indice, con un
indice dei test che fallisce: lo snapshot in attesa resta intatto e
viene pubblicato, le scritture restano, il commit ripetuto le conferma.
Un altro test abbandona un commit con più pagine di slot cambiate, poi
il cambio di proprietà riporta indietro gli slot e il commit successivo
riprende dal pool il buffer abbandonato: le coordinate sono quelle
giuste anche nelle pagine che non cambia.

Per strutture e garanzie, vedere
[isolamento delle letture](/current/decisions/read-isolation.md) e
[conservazione degli snapshot](/current/architecture/snapshot-retention.md#nel-prototipo).

# Correlati

- [Nucleo delle partizioni](/current/architecture/minimal-core.md) — garanzie del livello base.
- [Tick logico](/current/architecture/logical-time.md) — avanzamento e pubblicazione.
- [Metadati applicativi](/current/architecture/entity-metadata.md) — confermati insieme alle posizioni.
- [Fallimenti e salute della partizione](/current/architecture/partition-failures.md) — annullamento delle scritture e recupero.
- [Contratto dell’API](/current/api/operations.md#commit) — chiamata ed errori.
