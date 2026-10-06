---
type: Design Decision
title: Isolamento delle letture
description: I reader di una partizione vedono solo commit completi grazie a snapshot pubblicati con una scrittura atomica; gli slot delle entità si copiano per intero a ogni commit, gli indici sono strutture persistenti copy-on-write.
tags: [badspace, design, concurrency, partitioning, transactions]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T07:42:53Z }
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
  solo i nodi che ha cambiato e condivide gli altri con le versioni
  precedenti.

Al commit il writer pubblica **una sola radice** `(slot_N, indice_N)`
con una scrittura atomica. Slot e indice vanno sempre insieme: l'indice
parla di slot, e uno slot ha senso solo nella sua versione.

Il risultato: i reader non si bloccano mai, non bloccano mai il writer,
e una versione resta valida finché qualcuno la usa.

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
3. La nuova radice si pubblica con una scrittura atomica.
4. Le versioni che nessun reader usa più (e che non servono alla
   [conservazione delle versioni](/architecture/version-retention.md))
   si liberano: i loro buffer tornano disponibili, e i nodi dell'indice
   non più usati da nessuna versione tornano liberi.

Per sapere quando una versione non è più usata serve un contatore dei
reader per versione o l'epoch-based reclamation: è lo stesso principio
della *grace period* di RCU descritto nelle [letture
coerenti](/mechanisms/consistent-reads.md).

# Gli slot

Lo storage ha tre parti:

| Struttura | Cambia con | Nello snapshot |
|---|---|---|
| coordinate | ogni movimento | copia completa |
| ID | solo inserimenti e rimozioni | copia solo se il commit ha inserimenti o rimozioni, altrimenti condivisa con la versione precedente |
| mappa ID → slot | solo inserimenti e rimozioni | come gli ID: una tabella hash ad indirizzamento aperto su array primitivi si copia come gli slot; in alternativa una mappa hash persistente (HAMT) |

**Riuso dei buffer.** I buffer degli snapshot stanno in un pool. Un
buffer che si riusa contiene una versione vecchia, quindi basta copiare
le pagine cambiate da allora: per ogni pagina si tiene il commit
dell'ultima modifica, e si copiano le pagine modificate dopo la
versione del buffer. Non è mai peggio della copia completa, e con poche
entità in movimento è molto meglio. Nelle partizioni di entità statiche
(vedi [entità statiche e
dinamiche](/decisions/static-vs-dynamic-entities.md)) gli slot non
cambiano mai e lo snapshot non copia niente.

**Reader lento.** Se un reader tiene una versione a lungo, il suo buffer
resta occupato e il writer ne prende un altro dal pool, o ne alloca uno
nuovo: il writer non aspetta mai, cresce solo la memoria.

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
  torna disponibile solo quando nessuna versione lo usa.

I requisiti per gli alberi sono nel [modulo degli
alberi](/indices/tree-module.md#versioni-copy-on-write).

# Versioni passate

Ogni snapshot è già una versione completa della partizione: la
[conservazione delle versioni](/architecture/version-retention.md) con
k > 0 significa tenere vive le ultime k radici. Gli indici condividono
i nodi non cambiati; gli slot costano una copia per versione
conservata. Per le [letture coerenti](/mechanisms/consistent-reads.md)
il lettore sceglie la radice del commit N.

# Costi

- **Tempo del writer per commit:** la copia degli slot cambiati (al
  massimo tutti) più la copia dei nodi dell'indice toccati, una sola
  volta per nodo nel commit.
- **Memoria:** una copia degli slot per ogni versione viva (l'ultima
  pubblicata, quelle tenute dai reader e le k conservate), più i nodi
  dell'indice non condivisi.

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
- Mappa ID → slot: tabella ad indirizzamento aperto copiata o HAMT.
- Contatori dei reader per versione o epoch-based reclamation.
- Il confine del commit nel prototipo, che oggi non c'è: ogni scrittura
  è visibile appena eseguita.

# Correlati

- [Concorrenza](/decisions/concurrency.md) — un solo writer per
  partizione, più reader.
- [Commit della partizione](/architecture/partition-commit.md) — i
  reader vedono solo commit completi.
- [Conservazione delle versioni](/architecture/version-retention.md) e
  [letture coerenti](/mechanisms/consistent-reads.md) — letture al tick
  N su più partizioni.
- [Modulo degli alberi](/indices/tree-module.md) — alberi degli indici
  con versioni copy-on-write.
- [Persistenza](/decisions/persistence.md) — uno snapshot pubblicato è
  immutabile e si può salvare senza fermare il writer.
