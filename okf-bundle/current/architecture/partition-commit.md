---
type: Architecture Primitive
title: Commit della partizione
description: "Commit cumulativi nel tick corrente: modalità di scrittura diretta e con buffer, scritture accumulate nel writer e inviate in un batch, conferma dello stato, pubblicazione al tick successivo, sequenza interna per il copy-on-write e strutture transazionali con il loro ripristino."
tags: [badspace, architecture, partitioning, transactions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-05T18:54:02Z }
---

# Primitiva

`commit()` conferma le scritture del writer di una partizione, già
arrivate al nodo o mandate insieme al commit, secondo la [modalità di
scrittura](#modalità-di-scrittura) del nodo: lo stato confermato è
completo, con le posizioni e i metadati delle entità. È una transazione limitata alla
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
restano non confermate, nel nodo o nell'handle secondo la [modalità di
scrittura](#modalità-di-scrittura), e diventano visibili insieme in
`S(12)`, dal tick 13.

La barra lunga rappresenta una serie di scritture, che non trattengono
il lock della partizione durante gli avanzamenti del tick: nella
modalità diretta ogni scrittura lo prende solo per il proprio lavoro,
con il buffer lo prende solo il commit.

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

Il lock della partizione ordina scritture, commit, avanzamento del tick
e cambi di proprietà. Un commit concorrente all’avanzamento appartiene al tick
che si chiude oppure a quello nuovo; non modifica uno snapshot già
pubblicato. Non ci sono transazioni tra partizioni né conflitti tra
writer validi della stessa partizione: la
[proprietà](/current/architecture/partition-ownership.md) ne ammette uno solo.

Se cambia il proprietario nello stesso tick, il successore conserva i
commit precedenti senza poterli ancora leggere. Il limite è descritto
nella [visibilità nel tick del passaggio](/current/architecture/partition-ownership.md#visibilità-nel-tick-del-passaggio).

## Modalità di scrittura

Il nodo decide quando gli arrivano le scritture del writer, con la sua
**modalità di scrittura** (`WriteMode`), che non cambia per tutta la vita
del nodo. L'handle del writer la segue:

- **diretta** (`DIRECT`): ogni scrittura dell'handle va subito al nodo,
  che la applica allo stato del writer senza confermarla; `commit()`
  conferma le scritture già arrivate. È la modalità del nodo nello
  stesso processo del software, e quella predefinita del nodo locale:
  l'handle non tiene un buffer, e ogni scrittura costa solo il lavoro
  del nodo;
- **con buffer** (`BUFFERED`): le scritture restano nell'handle,
  composte per entità, e `commit()` le manda al nodo in una sola
  chiamata, che le applica e le conferma nello stesso passo (vedi
  [scritture accumulate](#scritture-accumulate-nel-writer)). È la
  modalità pensata per il nodo remoto futuro: una chiamata per commit,
  qualunque sia il numero di scritture. Il nodo locale si può creare in
  questa modalità, per i test e per i confronti.

```text
  diretta                                 con buffer
  writer              nodo                writer              nodo
  "set(A, p)"  -----> applica             "set(A, p)"         |
  "remove(B)"  -----> applica             "remove(B)"         |
  "commit()"   -----> conferma            "commit()"   -----> applica e conferma
```

Il diagramma mostra le chiamate al nodo di un commit con due scritture;
nella modalità con buffer le scritture restano nell'handle fino al
commit.

**Le due modalità danno lo stesso risultato.** In entrambe le scritture
si applicano nel loro ordine all'**ultimo commit** della partizione,
anche se aspetta ancora l'avanzamento del tick, non allo snapshot che il
writer ha letto: nella modalità diretta il nodo applica ogni scrittura
allo stato di quel momento, con il buffer la composizione per entità dà
lo stesso effetto delle scritture eseguite una alla volta. Le stesse
scritture fanno gli stessi commit, e i reader vedono gli stessi
snapshot. Anche i rifiuti e i fallimenti seguono le stesse regole:

| Dopo | Partizione | Scritture non confermate |
|---|---|---|
| Scrittura rifiutata (solo diretta: token non corrente, partizione rimossa o in recupero, input non valido) | Non cambia | Restano quelle di prima |
| Scrittura fallita dopo i controlli (solo diretta) | Torna all'ultimo commit, o resta in recupero necessario | Scartate tutte |
| Commit riuscito | Ha le scritture nel commit del tick corrente | Nessuna |
| Commit rifiutato (token non corrente, partizione rimossa o in recupero, input non valido) | Non cambia | Restano, nel nodo o nel buffer |
| Commit fallito dopo i controlli (`PartitionFailureException` o `Error`) | Torna all'ultimo commit, o resta in recupero necessario | Scartate tutte |
| `rollback()` | Torna all'ultimo commit; è anche il recupero | Scartate tutte |
| `close()` | Rilasciata, all'ultimo commit | Scartate tutte |
| `removePartition()` riuscita | In rimozione, all'ultimo commit | Scartate tutte |

Nella modalità diretta le scritture non confermate sono nel nodo, e il
ripristino all'ultimo commit le scarta: al rollback, a un fallimento e
al cambio di proprietà. Con il buffer sono nell'handle: `rollback()` e
`close()` svuotano il buffer, poi vanno al nodo per il ripristino e per
il rilascio, e tra un commit e l'altro il nodo non ha scritture del
writer da scartare. In entrambi i casi le scritture di un writer che ha
perso la proprietà non arrivano alla partizione: il nodo le rifiuta,
alla scrittura o al commit che le porterebbe.

**Le differenze tra le due modalità sono due.**

- **Il momento di alcuni rifiuti.** Nella modalità diretta il nodo
  controlla token, stato e salute della partizione a ogni scrittura, e
  i fallimenti delle strutture arrivano con la scrittura che li causa.
  Con il buffer le scritture non chiamano il nodo, quindi alla chiamata
  l'handle controlla solo i limiti delle posizioni, e il resto arriva al
  writer con il `commit()`. Un controllo sul nodo a ogni scrittura
  riporterebbe una chiamata per scrittura, e non basterebbe comunque:
  proprietà e salute possono cambiare tra la scrittura e il commit, che
  deve controllarle di nuovo. Il writer che vuole saperlo prima può
  leggere `ownership()` e `health()` della partizione.
- **La durata del lock della partizione.** Nella modalità diretta ogni
  scrittura e il commit prendono il lock per il proprio lavoro, e un
  avanzamento del tick può passare tra due scritture: pubblica l'ultimo
  commit, quindi le scritture non confermate restano fuori dallo
  snapshot in entrambe le modalità. Con il buffer il commit tiene il
  lock per tutto il batch, e l'avanzamento del tick può aspettare fino
  alla sua durata (vedi [costi](#costi-del-buffer-e-del-commit)).

## Scritture accumulate nel writer

Nella modalità con buffer le scritture del writer restano nel suo
[handle](/current/api/operations.md#proprietà-e-handle-del-writer),
nel processo del software, e non chiamano il nodo: `commit()` le manda
tutte insieme, in una sola chiamata, con il token. Il nodo applica il
batch all'ultimo commit della partizione e lo conferma nello stesso
passo, sotto il lock della partizione.

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
client e il commit, e sui nodi locali supera il beneficio (vedi
[costi](#costi-del-buffer-e-del-commit)): per questo il nodo locale usa la
modalità diretta.

L'handle controlla subito i limiti delle posizioni: una scrittura fuori
dai limiti non entra nel buffer. Token, stato e salute della partizione
li controlla il nodo al commit, insieme di nuovo ai limiti, che
proteggono le celle degli indici; un batch rifiutato non cambia la
partizione e le scritture restano nel buffer. Il buffer manda al più
una modifica per entità, e il nodo non lo controlla: il contratto del
nodo è interno al sistema (vedi [fiducia negli
sviluppatori](/process/development-approach.md#fiducia-negli-sviluppatori)),
e più modifiche della stessa entità si applicano in ordine, come le
scritture della modalità diretta.

### Costi del buffer e del commit

Il piano `plans/buffered-writes.json` del modulo di benchmark misura un
tick con spostamenti, commit e avanzamento del tick in tre modi: gli
spostamenti preparati come batch e scritti nel nodo senza commit, con
la scrittura della modalità diretta, poi il commit (`commitTick`); lo
stesso tick attraverso le scritture del writer
(`commitTickWithTheWriter`, nelle due modalità con il parametro
`writeMode`); e gli spostamenti da soli (`movesWithoutCommit`). Misura
anche la memoria del buffer (`BufferSizes`) e l'attesa dell'avanzamento
del tick durante i commit (`moveTickDuringCommits`).

Risultati del 2026-11-05 sulla macchina dedicata, profilo rapido,
Temurin 25.0.5, tutto il piano in un solo run sul codice prima delle due
modalità, quando il writer aveva solo il buffer
(`prototype/benchmark/results/2026-11-05-1943-*`): la colonna «Tick dal
writer» è quindi la modalità con buffer, e il tick dal writer nella
modalità diretta è ancora da misurare. Allora `commitTick` scriveva
nello storage con lo stesso passo che ora fa la scrittura del nodo.
Distribuzione uniforme, senza reader, in millisecondi per tick. L'ultima colonna è il
tick di `main`, prima del refactoring, con le scritture inviate al nodo
una alla volta, misurato lo stesso giorno con OpenJDK 25.0.2
(`2026-11-05-commit-tick-main-quick.*`): le due JVM danno gli stessi
tempi, entro la variabilità, sullo stesso codice.

| Indice ed entità | In movimento | Spostamenti | Tick | Tick dal writer, con buffer | Tick di `main` |
|---|---|---|---|---|---|
| Scansione lineare, 100 000 | 10% | 0,30 | 0,36 | 0,66 | 0,29 |
| Scansione lineare, 100 000 | 100% | 3,5 | 3,8 | 8,1 | 2,7 |
| Scansione lineare, 1 000 000 | 1% | 0,42 | 1,1 | 1,5 | 0,98 |
| Scansione lineare, 1 000 000 | 10% | 9,3 | 9,9 | 24 | 8,1 |
| Scansione lineare, 1 000 000 | 100% | 123 | 119 | 242 | 98 |
| Grid uniforme 100, 1 000 000 | 10% | 15 | 23 | 32 | 24 |
| Grid uniforme 100, 1 000 000 | 100% | 200 | 201 | 317 | 220 |
| Griglia di quadtree 128, 1 000 000 | 1% | 2,0 | 5,2 | 5,1 | 4,8 |
| Griglia di quadtree 128, 1 000 000 | 10% | 38 | 54 | 62 | 58 |
| Griglia di quadtree 128, 1 000 000 | 100% | 459 | 550 | 634 | 558 |

- **Il percorso del writer costa da 1,1 a 3 volte quello di `main`.**
  Il tick dal writer, con buffer, batch e commit, dura da 1,5 a 3 volte
  il tick di `main` con la scansione lineare, da 1,25 a 1,8 volte con la
  grid uniforme e da 1,05 a 1,35 volte con la griglia di quadtree: la
  differenza pesa di più con gli indici economici e con i batch grandi.
  È il prezzo di un writer che non chiama il nodo a ogni scrittura, che
  conta soprattutto con un nodo remoto, dove ogni chiamata è un andata e
  ritorno in rete; per questo il nodo locale usa la modalità diretta.
- **Il buffer costa circa 25–140 ns per entità scritta**, la differenza
  tra il tick dal writer e il tick: di più con molte entità, che non
  stanno nella cache. Il costo viene dalla mappa per ID, dall'oggetto di
  ogni modifica e dalla lista del batch. La parte restante della
  differenza con `main` è l'applicazione del batch nello storage: gli
  spostamenti costano da 1,0 a 1,6 volte quelli di `main`, di più con la
  scansione lineare, dove lo spostamento in sé costa pochi nanosecondi.
- **Il buffer occupa circa 100–110 byte per entità** con le sole `set`,
  e circa 405–415 con 4 metadati per entità, in aggiunta alle entità e
  alle scritture che il writer ha già costruito. Il valore non cambia
  tra 1000 e 1 000 000 di entità.
- **Tre ottimizzazioni misurate.** Il nodo non controlla più che il
  batch abbia una sola modifica per entità: il controllo costruiva un
  insieme di tutti gli ID del batch e costava quasi quanto l'indice
  (`PartitionBenchmark.update` con batch da 100 costa senza da 0,43 a
  0,78 volte, `2026-11-05-update-with-check-quick.*` e
  `2026-11-05-update-without-check-quick.*`). Il buffer crea le mappe dei
  metadati di una modifica alla prima scrittura: la memoria con le sole
  `set` scende da circa 237 a circa 107 byte per entità, e il tick dal
  writer costa da 0,65 a 0,95 volte (`2026-11-05-*-lazy-maps-quick.*`).
  Lo storage cerca lo slot di ogni modifica una volta sola: il guadagno è
  dentro la variabilità (`2026-11-05-*-single-lookup-*`). Con la
  scansione lineare e 100 000 entità l'aggiornamento costa di nuovo
  circa 26 ns per entità, come nella [capacità degli aggiornamenti per
  tick](/current/indices/update-capacity.md).
- **Con il buffer il commit tiene il lock per tutto il batch.**
  Applicazione del batch e preparazione dello snapshot avvengono sotto il
  lock della partizione, e un avanzamento del tick che arriva durante un
  commit lo aspetta. Nella modalità diretta, come con le scritture di
  `main`, il lock si prende a ogni scrittura, e l'avanzamento può passare
  tra una scrittura e l'altra; il commit lo tiene solo per preparare lo
  snapshot. In entrambi i casi l'avanzamento pubblica solo l'ultimo
  commit, quindi lo snapshot ha un commit intero o niente.

`moveTickDuringCommits` misura la latenza di `setTick` mentre un writer
su un altro thread, non misurato, fa commit degli stessi batch uno dopo
l'altro, senza pause, come nella modalità con buffer: è il caso peggiore, con un commit sempre in corso
o appena finito quando arriva l'avanzamento. I valori sono la media
(modo `AverageTime`) e i percentili dei campioni (modo `SampleTime`),
dallo stesso run:

| Indice ed entità | In movimento | Media | Mediana | Massimo |
|---|---|---|---|---|
| Scansione lineare, 1 000 000 | 1% | 0,19 ms | 1 µs | 1,8 ms |
| Scansione lineare, 1 000 000 | 10% | 11 ms | 11 ms | 15 ms |
| Scansione lineare, 1 000 000 | 100% | 123 ms | 126 ms | 136 ms |
| Grid uniforme 100, 1 000 000 | 100% | 218 ms | 208 ms | 222 ms |
| Griglia di quadtree 128, 1 000 000 | 1% | 3,9 ms | 4,9 ms | 8,3 ms |
| Griglia di quadtree 128, 1 000 000 | 100% | 540 ms | 558 ms | 575 ms |
| Scansione lineare, 100 000 | 100% | 1,6 ms | 3,6 ms | 4,6 ms |

- **L'avanzamento aspetta al più circa il commit in corso.** Con batch
  grandi la media è vicina alla durata del tick misurato sopra, per
  esempio 123 ms contro 119 ms; con commit brevi l'avanzamento spesso
  passa tra due commit, e la mediana è di pochi microsecondi.
- **Il lock della partizione è equo**, e i thread lo prendono nell'ordine
  in cui lo aspettano. Con il lock non equo usato prima, il writer che
  chiedeva di nuovo il lock appena lasciato lo riprendeva prima
  dell'avanzamento già in attesa, e con commit brevi uno dopo l'altro
  l'avanzamento aspettava molti commit: in media da 90 a 300 ms, fino a
  circa un secondo, contro commit da 0,1 a qualche millisecondo
  (`2026-11-05-tick-during-commits-quick.*`, misurato con il controllo
  dei duplicati). Succedeva lo stesso con le scritture inviate una alla
  volta, che prendevano il lock a ogni chiamata. Il lock equo non ha un
  costo misurabile sui commit (`2026-11-05-1611-*`).
- Con le latenze lunghe il profilo rapido raccoglie pochi campioni, e
  mediana e massimo sono indicativi.

Con più partizioni sullo stesso nodo l'avanzamento fa avanzare per prime
quelle con il lock libero (vedi [tick logico](/current/architecture/logical-time.md#ordine-con-le-altre-operazioni)).
Il parametro `busyPartitions` di `moveTickDuringCommits` dà a più
partizioni un writer senza pause; i risultati confrontano l'avanzamento
sequenziale di prima con quello di adesso, a parità di lock equo, con il
codice che faceva ancora il controllo dei duplicati
(`prototype/benchmark/results/2026-11-05-tick-busy-partitions-*`),
100 000 entità, in millisecondi:

| Indice, in movimento | Partizioni occupate | Sequenziale: media, mediana | Libere per prime: media, mediana |
|---|---|---|---|
| Griglia di quadtree 128, 10% | 2 | 3,7, 2,8 | 3,5, 2,7 |
| Griglia di quadtree 128, 10% | 4 | 9,6, 9,9 | 8,4, 8,8 |
| Scansione lineare, 10% | 4 | 2,2, 1,9 | 1,7, 2,0 |

- **Con tutte le partizioni sempre occupate le due versioni costano
  circa lo stesso.** Quando l'avanzamento riprova le altre partizioni
  dopo un'attesa le trova di nuovo occupate, quindi le aspetta comunque
  una alla volta, al più un commit ciascuna: il totale cresce con il
  numero di partizioni occupate. Per aspettarle tutte nello stesso
  momento servirebbe un avanzamento in parallelo o rimandato al writer.
- **Il guadagno è per le partizioni libere al momento della scansione**,
  che non aspettano più i commit delle altre: è il caso comune, con poche
  partizioni che hanno un commit in corso. Una partizione occupata alla
  scansione può invece aspettare anche il commit di un'altra. La misura
  dà solo la durata dell'intero `setTick`; la garanzia la verificano due
  test con i commit fermi nell'indice (vedi [tick
  logico](/current/architecture/logical-time.md#nel-prototipo)).
- Con una sola partizione occupata le due versioni fanno la stessa cosa,
  e le differenze tra le medie sono variabilità: l'avanzamento passa
  subito se arriva tra due commit, o aspetta un commit intero, e la
  proporzione cambia da una misura all'altra; le mediane sono uguali.

Il profilo completo è da fare con lo stesso piano. Batch così grandi su
una sola partizione sono un caso limite: di solito le entità si dividono su più partizioni, e il
costo per partizione scende di conseguenza.

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

La modalità di scrittura è l'enum `WriteMode` in `common`, e il nodo la
dichiara con `writeMode()` nel suo contratto. `LocalPartitionNode2`/
`LocalPartitionNode3` è nella modalità diretta, salvo i costruttori che
ricevono la modalità. Il writer manda scritture, commit, rollback e
rilascio attraverso la sua **sessione di scrittura**, l'interfaccia
`WriteSession2`/`WriteSession3` in `common`: nasce con il token, e
`of(node, token, limits)` la crea nella modalità del nodo, con due
implementazioni package-private:

- `DirectWriteSession2`/`DirectWriteSession3` trasforma ogni chiamata in
  un batch, con un `EntityWrite2`/`EntityWrite3` per ogni `set`, per ogni
  rimozione e per ogni gruppo di scritture consecutive dei metadati di
  un'entità, e lo manda con `write(WriteToken, List<EntityWrite2>)` del
  nodo, che lo applica senza commit; senza modifiche non chiama il nodo.
  Il commit è `commit(token, List.of())`, e non tiene niente nel client;
- `BufferedWriteSession2`/`BufferedWriteSession3` tiene nodo, token e
  limiti della partizione, controlla i limiti alla chiamata, manda il batch al commit
  con `commit(WriteToken, List<EntityWrite2>)` e lo svuota dopo il
  successo; `rollback()` e `release()` lo svuotano prima di andare al
  nodo. La composizione è in `WriteBuffer2`/`WriteBuffer3`,
  package-private in `common`, che produce un `EntityWrite2`/
  `EntityWrite3` per entità; i test la confrontano con l'esecuzione delle
  chiamate una alla volta, anche su 5000 sequenze casuali di `set`,
  `remove` e scritture dei metadati su basi casuali.

Le due implementazioni dipendono solo dal contratto del nodo, quindi
valgono per ogni nodo. L'handle del writer ne ha una e aggiunge quello
che è dell'API: la generazione degli ID, le scorciatoie su una sola
entità e la politica di rimozione. Lo storage applica un batch allo
stato del writer con `apply`, che è la scrittura del nodo, e prepara lo
snapshot con `commit`; il commit con un batch fa i due passi sotto lo
stesso lock. Un fallimento di `apply` ha l'operazione `WRITE` nel
rapporto, uno del commit con il batch l'operazione `COMMIT`.
`EntityWrite2`/`EntityWrite3` ha i metodi di comodo `set`, `removal` e
`metadataOf` per i test e i client.

I test end-to-end dell'API e le classi di test del nodo su proprietà,
metadati, rollback e operazioni del nodo girano nelle due modalità
(`@ParameterizedClass` su `WriteMode`): l'equivalenza è verificata dagli
stessi test, e solo i rifiuti per token e stato si controllano alla
scrittura nella modalità diretta e al commit con il buffer. I test
unitari dell'API, con un nodo finto, controllano i batch della modalità
con buffer e le chiamate della modalità diretta. I test del
commit di un batch (`BatchCommit2Test`/`BatchCommit3Test`) usano un nodo
con buffer; gli altri test del nodo la modalità diretta. Tutti scrivono
con lo stesso `WriteSession2`/`WriteSession3`, uno per token. I
benchmark delle scritture misurano slot e indice senza il commit, con la
scrittura del nodo; il tick attraverso il writer si misura nelle due
modalità. L'esempio `write-modes` fa le stesse scritture su un nodo
diretto e su uno con buffer.

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
viene pubblicato, il batch è annullato, e lo stesso batch inviato di
nuovo si applica intero.
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
