---
type: Design Decision
title: Isolamento delle letture
description: I reader di una partizione vedono solo commit completi grazie a snapshot preparati a ogni commit, pubblicati con una scrittura atomica all'avanzamento del tick e letti attraverso un'acquisizione controllata; gli slot delle entità si copiano per intero a ogni commit, gli indici sono strutture persistenti copy-on-write.
tags: [badspace, design, concurrency, partitioning, transactions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T16:00:00Z }
---

# Problema

Ogni partizione ha [un solo writer e più reader](/decisions/concurrency.md).
I reader devono vedere storage e indice in uno stato coerente: per il
[commit della partizione](/architecture/partition-commit.md), solo
**commit completi**, mai uno stato intermedio.

Vincoli:

- **L'unità di isolamento è il commit, non la singola scrittura.** Due
  scritture dello stesso commit diventano visibili insieme.
- **Il writer è il ciclo principale del software** (il game loop): un
  reader lento non deve poter ritardare il tick.
- **Il writer legge le proprie scritture** durante il commit, per
  esempio per fare query sullo stato appena modificato.

# Decisione

Ogni commit produce uno **snapshot** della partizione, che i reader
leggono senza lock. Lo snapshot ha due parti, trattate in modo diverso:

- **Slot delle entità** (ID e coordinate): **copia completa** al commit.
- **Indici**: **strutture persistenti copy-on-write**; ogni commit copia
  solo i nodi che ha cambiato e condivide gli altri con gli snapshot
  precedenti.

Al commit il writer pubblica **una sola radice** `(slot_N, indice_N)`
con una scrittura atomica. Slot e indice vanno sempre insieme: l'indice
parla di slot, e uno slot ha senso solo nel suo snapshot.

Il risultato: i reader non si bloccano mai, non bloccano mai il writer,
e uno snapshot resta leggibile finché ha acquisizioni valide o query in
corso (vedi [Accesso dei reader](#accesso-dei-reader)).

# Perché due soluzioni diverse

Slot e indici cambiano con frequenze molto diverse.

- **Le coordinate cambiano a ogni movimento.** Gli slot non seguono né
  lo spazio né l'attività (dipendono dall'ordine di inserimento e dallo
  spostamento dell'ultima entità nelle rimozioni), quindi le entità che
  si muovono in un tick sono sparse su tutto l'array. Se si muove una
  frazione p delle entità e una pagina ha B slot, la frazione di pagine
  toccate è circa 1 − (1 − p)^B: con p = 1% e B = 256 è già il 92%. Una
  struttura persistente a pagine copierebbe quasi tutto, con in più
  l'indirezione nelle letture: conviene la copia completa, che è il caso
  migliore per la banda di memoria.
- **L'indice cambia solo quando un'entità cambia cella o foglia**: un
  movimento dentro la stessa cella o foglia non tocca l'indice. Le
  modifiche sono molto più rade, e qui la copia dei soli nodi toccati
  conviene davvero.

# Il commit

1. Il writer scrive sui suoi array mutabili degli slot e su una versione
   **transient** dell'indice: un nodo si copia alla prima modifica del
   commit, le modifiche successive dello stesso commit vanno sulla
   copia. Il writer vede così le proprie scritture.
2. Al commit gli array degli slot si copiano in un buffer per lo
   snapshot, e i nodi copiati nel commit diventano immutabili.
3. La nuova radice si pubblica con una scrittura atomica
   all'avanzamento del [tick](/architecture/logical-time.md): il commit
   prepara lo snapshot del tick, che diventa visibile solo quando il
   tick avanza. Con più commit nello stesso tick si pubblica l'ultimo.
4. Gli snapshot fuori dalla finestra di [conservazione degli
   snapshot](/architecture/snapshot-retention.md), senza acquisizioni
   valide né query in corso, si liberano: i loro buffer tornano disponibili, e i nodi dell'indice
   non più usati da nessuno snapshot tornano liberi.

Per sapere quando uno snapshot non è più usato serve un contatore dei
reader per snapshot o l'epoch-based reclamation: è lo stesso principio
della *grace period* di RCU descritto nelle [letture
coerenti](/mechanisms/consistent-reads.md).

**Nel prototipo** il commit prepara lo snapshot della partizione
(snapshot degli slot e snapshot dell'indice) e lo tiene in attesa;
l'avanzamento del tick lo mette nel catalogo degli snapshot, un oggetto
immutabile con il tick e gli snapshot della finestra, e pubblica il
catalogo nuovo con la scrittura di un campo `volatile`: chi legge il
campo vede anche tutto quello che è stato scritto prima, quindi il
catalogo precedente o tutto quello nuovo, mai un tick nuovo con la
finestra vecchia (vedi [conservazione degli
snapshot](/architecture/snapshot-retention.md#nel-prototipo)). Anche il
tick della partizione si legge dal catalogo pubblicato, quindi da ogni
thread. Una partizione nuova pubblica al primo avanzamento uno snapshot
vuoto, con l'indice vuoto della scansione lineare, che con slot vuoti
vale per ogni indice. Il writer legge il proprio stato, i reader gli
snapshot pubblicati attraverso le acquisizioni, con una view controllata
(vedi [API da
esporre](/decisions/api-surface.md#letture-del-writer-e-dei-reader) e
[Accesso dei reader](#accesso-dei-reader)): lo snapshot interno non
esce dal nodo. La partizione rileva quando uno snapshot finisce (vedi
[Vita di uno snapshot](/architecture/snapshot-retention.md#vita-di-uno-snapshot));
la liberazione della memoria la fa per ora il GC.

# Accesso dei reader

Gli snapshot pubblicati hanno due usi distinti:

- **uso interno** del nodo: lo storage, il catalogo della
  [conservazione degli snapshot](/architecture/snapshot-retention.md)
  e, in futuro, la [persistenza](/decisions/persistence.md) tengono
  riferimenti agli snapshot senza passare dalle acquisizioni;
- **accesso dei reader**: un reader legge uno snapshot solo attraverso
  un'[acquisizione](/architecture/snapshot-retention.md#acquisizione-e-rilascio).
  Lo snapshot interno non esce mai dal nodo: il reader riceve una view
  che controlla l'acquisizione all'inizio di ogni lettura, quindi dopo
  il rilascio o la scadenza non c'è modo di interrogarla.

La verifica dell'acquisizione e la presa di un riferimento stabile allo
snapshot sono un'unica operazione atomica (l'ammissione della query).
Dopo l'ammissione la query non tiene lock: legge lo snapshot immutabile
come prima, e se il rilascio o la scadenza arrivano nel frattempo la
query finisce e il suo risultato è valido.

Restano invariati la pubblicazione atomica, il copy-on-write e la
lettura delle proprie scritture da parte del writer. Dopo la rimozione
della partizione le acquisizioni già fatte restano leggibili (vedi
[conservazione degli
snapshot](/architecture/snapshot-retention.md#rimozione-della-partizione)).

# Tutti i dati delle entità

Con gli snapshot vale una regola generale: **ogni dato associato a
un'entità entra nello snapshot**. Non solo ID e coordinate, ma anche i
[metadati](/architecture/entity-metadata.md) di sistema e applicativi,
le [tombstone](/architecture/tombstones.md) e qualunque dato si
aggiungerà in futuro. Un dato fuori dallo snapshot sarebbe visibile ai
reader prima del commit, o cambierebbe sotto un reader che legge uno
snapshot vecchio.

Per ogni dato si sceglie come copiarlo, in base a quanto spesso cambia:
**copia completa** se cambia spesso ed è sparso, come le coordinate,
oppure **copy-on-write** se le modifiche sono rade. Fanno eccezione solo
le strutture che servono alle scritture e che nessuna query legge (vedi
[Gli indici](#gli-indici)).

# Gli slot

Oggi lo storage ha tre parti (i metadati e le tombstone, quando ci
saranno, seguono la stessa regola):

| Struttura | Cambia con | Nello snapshot |
|---|---|---|
| coordinate | ogni movimento | copia completa |
| ID | solo inserimenti e rimozioni | copia solo se il commit ha inserimenti o rimozioni, altrimenti condivisa con lo snapshot precedente |
| mappa ID → slot | solo inserimenti e rimozioni | come gli ID: una tabella hash ad indirizzamento aperto su array primitivi si copia come gli slot; in alternativa una mappa hash persistente (HAMT) |

**Nel prototipo** la mappa ID → slot è una tabella ad indirizzamento
aperto (`SlotTable`): un array di ID e uno di slot, sondaggio lineare,
al massimo mezza piena. La rimozione sposta indietro le chiavi che
seguono, quindi non ci sono tombstone; un posto vuoto ha slot -1, così
ogni valore può essere un ID. La copia sono due array.

- Al commit lo storage crea uno snapshot immutabile degli slot, con
  array lunghi quanto gli slot occupati. Le coordinate si copiano
  sempre; ID e tabella si copiano solo se il commit ha avuto
  inserimenti o rimozioni, altrimenti lo snapshot li condivide con
  quello precedente.
- Lo snapshot degli slot e quello dell'indice formano lo snapshot della
  partizione (`StorageSnapshot`, interna al service: commit, slot e indice), con le
  letture `getAll`, `findInRegion` e `findNearest`.
- Le coordinate di ogni snapshot stanno in un buffer preso da un pool
  della partizione, come descritto sotto. Per ora il commit copia
  sempre tutte le coordinate nel buffer; la copia delle sole pagine
  cambiate arriva in un passo successivo. ID e tabella ID → slot
  restano fuori dal pool: si copiano solo con inserimenti o rimozioni.

**Riuso dei buffer.** I buffer degli snapshot stanno in un pool. Un
buffer che si riusa contiene uno snapshot vecchio, quindi basta copiare
le pagine cambiate da allora: per ogni pagina si tiene il commit
dell'ultima modifica, e si copiano le pagine modificate dopo lo
snapshot del buffer. Non è mai peggio della copia completa, e con poche
entità in movimento è molto meglio. Nelle partizioni di entità statiche
(vedi [entità statiche e
dinamiche](/decisions/static-vs-dynamic-entities.md)) gli slot non
cambiano mai e lo snapshot non copia niente.

**Reader lento.** Se un reader tiene a lungo un'acquisizione, il buffer
dello snapshot resta occupato e il writer ne prende un altro dal pool, o ne alloca uno
nuovo: il writer non aspetta mai, cresce solo la memoria.

**Nel prototipo** il pool è per partizione, perché la copia delle sole
pagine cambiate richiede che il buffer contenga uno snapshot vecchio
della stessa partizione. È una coda che non blocca: il thread che fa
finire uno snapshot, uno qualsiasi, vi rimette il suo buffer, e il
writer ne prende uno a ogni commit. Un buffer torna nel pool in due
casi:

- alla [fine dello snapshot](/architecture/snapshot-retention.md#vita-di-uno-snapshot):
  fuori dalla finestra, senza acquisizioni valide né query in corso;
- subito, se un commit dopo nello stesso tick sostituisce lo snapshot
  prima della pubblicazione, perché nessun reader l'ha mai visto.

Un buffer nuovo è lungo quanto le coordinate del writer, con la loro
capacità libera, quindi lo snapshot ha la sua dimensione, separata
dalla lunghezza del buffer; un buffer troppo piccolo per le entità del
commit viene scartato. La distruzione della partizione svuota il pool:
il buffer di uno snapshot torna nel pool prima che scenda il conteggio
degli snapshot non finiti, così il thread che porta il conteggio a zero
svuota il pool dopo tutte le restituzioni, e nessun buffer vi rientra
dopo. Un test ferma un reader mentre restituisce il buffer, con un pool
dei test passato allo storage alla creazione come il lock, e fa finire
l'ultimo snapshot da un altro reader.
Una conseguenza: uno snapshot si legge solo con un utilizzo
(un'acquisizione o una query ammessa), perché dopo la sua fine il buffer
passa a un altro commit. I test controllano il riuso con l'identità dei
buffer e con il numero di buffer nel pool, esposto per l'ispezione.

# Gli indici

- Gli indici vanno scritti come strutture persistenti con transient:
  ogni nodo sa in quale commit è stato creato, e il writer lo modifica
  sul posto solo se è del commit corrente, altrimenti lo copia insieme
  al percorso fino alla radice.
- Anche le mappe delle celle (cella → lista di slot nella grid uniforme,
  cella → radice nella griglia di quadtree) sono persistenti e fanno
  parte dello snapshot.
- Nello snapshot entra **solo quello che leggono le query**. Le
  strutture che servono solo alle scritture, come la mappa inversa
  slot → (foglia, posizione), restano mutabili e private del writer.
- I nodi pubblicati non si modificano più: la memoria dei nodi (arena)
  non può spostarsi mentre i reader la leggono, e un nodo liberato
  torna disponibile solo quando nessuno snapshot lo usa.

I requisiti per gli alberi sono nel [modulo degli
alberi](/indices/tree-module.md#snapshot-copy-on-write).

**Nel prototipo** ogni indice ha `commit(n)`, chiamato dallo storage
quando la partizione chiude il commit n, che restituisce uno snapshot
dell'indice (`IndexSnapshot`). Le query dello snapshot ricevono gli slot
dello stesso commit, quindi lo snapshot non dipende dallo storage del
writer. Con il commit senza numero, n è la **sequenza dei
commit**: un contatore interno di ogni partizione che cresce a ogni
commit, anche nello stesso tick, e non esce dal nodo. Non è condiviso
tra le partizioni, quindi i writer di partizioni diverse non si
coordinano. Gli indici non cambiano.

- La [scansione lineare](/indices/linear-scan.md) non ha strutture: la
  suo snapshot legge solo gli slot.
- La [grid uniforme](/indices/uniform-grid.md#snapshot-al-commit) e la
  [griglia di quadtree e di
  octree](/indices/grid-quadtree.md#implementazione-nel-prototipo)
  tengono le celle in una HAMT con transient (`HashTrie`) e copiano
  celle e nodi alla prima modifica dopo un commit.

Lo storage combina lo snapshot dell'indice con quello degli slot
(vedi [Gli slot](#gli-slot)) e pubblica lo snapshot della partizione
(vedi [Il commit](#il-commit)).

# Snapshot passati

Ogni snapshot è già uno stato completo della partizione: la
[conservazione degli snapshot](/architecture/snapshot-retention.md)
significa tenere vive le radici degli snapshot dei tick nella
finestra, gli ultimi k tick: tick consecutivi senza commit condividono
la stessa radice. Gli indici condividono i nodi non cambiati; gli slot
costano una copia per commit conservato. Per le [letture
coerenti](/mechanisms/consistent-reads.md) il lettore acquisisce
esattamente lo snapshot del tick N.

# Costi

- **Tempo del writer per commit:** la copia degli slot cambiati (al
  massimo tutti) più la copia dei nodi dell'indice toccati, una sola
  volta per nodo nel commit.
- **Memoria:** una copia degli slot per ogni snapshot vivo (quelli dei
  tick nella finestra, uno per commit, quello preparato nel tick
  corrente e quelli usciti dalla finestra ma ancora acquisiti), più i nodi dell'indice non condivisi.

Per 1 000 000 di entità gli slot sono circa 24 MB in 2D e 32 MB in 3D;
la copia completa costa qualche ms, da misurare, su un tick di circa
16 ms. È un **caso limite**, utile per spingere le ottimizzazioni e
vedere fin dove si arriva: in un uso realistico un numero così alto di
entità si divide su più [partizioni](/decisions/partitioning.md), e il
costo per partizione scende di conseguenza. Il totale da tenere
d'occhio è quello per nodo.

Nel prototipo Java la copia completa pesa anche sul GC, ma il
[linguaggio finale](/decisions/language.md) non sarà Java, e il riuso
dei buffer evita comunque le allocazioni a ogni tick.

# Punti aperti

- Misurare nel prototipo il costo della copia degli slot e della copia
  dei nodi dell'indice per commit, con diverse frazioni di entità in
  movimento.
- Mappa ID → slot: nel prototipo tabella copiata; da rivedere con le
  misure se la copia pesa con molte entità e inserimenti o rimozioni a
  ogni commit (alternativa HAMT).
- Contatori dei reader per snapshot o epoch-based reclamation nel
  [linguaggio finale](/decisions/language.md), senza GC.

# Correlati

- [Concorrenza](/decisions/concurrency.md) — un solo writer per
  partizione, più reader.
- [Commit della partizione](/architecture/partition-commit.md) — i
  reader vedono solo commit completi.
- [Conservazione degli snapshot](/architecture/snapshot-retention.md) e
  [letture coerenti](/mechanisms/consistent-reads.md) — letture al tick
  N su più partizioni.
- [Modulo degli alberi](/indices/tree-module.md) — alberi degli indici
  con snapshot copy-on-write.
- [Persistenza](/decisions/persistence.md) — uno snapshot pubblicato è
  immutabile e si può salvare senza fermare il writer.
