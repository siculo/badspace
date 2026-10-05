---
type: Design Decision
title: Isolamento delle letture
description: Alternative, ancora da decidere, per far vedere ai reader di una partizione solo commit completi mentre il writer scrive (lock reader/writer, copia completa, strutture persistenti, lettura ottimistica, fasi del tick, due istanze con replay), con pro e contro di ciascuna.
tags: [badspace, design, concurrency, partitioning, transactions]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T23:20:14Z }
---

# Problema

Ogni partizione ha [un solo writer e più reader](/decisions/concurrency.md).
I reader devono vedere storage e indice in uno stato coerente: per il
[commit della partizione](/architecture/partition-commit.md), solo
**commit completi**, mai uno stato intermedio.

Nel prototipo non c'è ancora nessun meccanismo: lo storage non è
thread-safe e ogni scrittura (`insertAll`, `updateAll`, `removeAll`) è
visibile appena eseguita. Manca anche il confine del commit.

**Decisione: aperta.** Questo documento descrive le alternative.

# Vincoli

- **L'unità di isolamento è il commit, non la singola scrittura.** Due
  scritture dello stesso commit devono diventare visibili insieme:
  proteggere ogni chiamata da sola non basta.
- **Il writer è il ciclo principale del software** (il game loop). Il
  tick deve restare deterministico, ed è preferibile che un reader lento
  non possa ritardarlo.
- **Gli indici sono strutture mutabili e complesse** (grid, griglia di
  quadtree), che lavorano sugli slot dello storage; la rimozione sposta
  l'ultima entità nello slot libero. Copiarli o renderli immutabili
  costa molto: è questa la parte difficile, più dello storage.
- **Il writer deve poter leggere le proprie scritture** durante il
  commit, per esempio per fare query sullo stato appena modificato.

# Alternative

## 1. Lock reader/writer

Quando il writer scrive i reader non accedono, e viceversa (per esempio
con un `ReadWriteLock`).

Per rispettare il commit, il lock di scrittura va tenuto per tutto il
commit, cioè per quasi tutto il tick. **Variante:** il writer accumula
le operazioni in un buffer e le applica tutte insieme, sotto lock, al
momento del commit.

**Pro**
- La soluzione più semplice; nessuna memoria in più.
- Storage e indici restano come sono.

**Contro**
- Il writer aspetta i reader: una lettura lunga ritarda il tick, e il
  tempo del tick dipende dal carico dei reader.
- Senza buffer i reader restano bloccati per quasi tutto il tick.
- Con il buffer i reader sono bloccati durante l'applicazione, e il
  writer non vede le proprie scritture se non legge anche il buffer
  (le query dovrebbero unire indice e buffer).

## 2. Snapshot con copia completa

Il writer scrive sulla sua copia; al commit si copia lo stato e i
reader leggono l'ultima copia pubblicata.

**Pro**
- Concetto semplice: i reader non si bloccano mai e non bloccano il
  writer.
- Una copia è anche un punto di partenza naturale per la
  [persistenza](/decisions/persistence.md).

**Contro**
- Costo O(n) a ogni commit. Per 1 000 000 di entità in 2D gli array
  sono circa 24 MB; in più ci sono la mappa da ID a slot e soprattutto
  l'indice, da copiare o ricostruire. Con un tick di circa 16 ms non è
  realistico.
- Molta memoria allocata e liberata a ogni tick (pressione sul GC in
  Java).

## 3. Strutture persistenti (MVCC copy-on-write)

Storage e indici diventano strutture immutabili: ogni modifica copia
solo il percorso dalla radice al nodo cambiato. Ogni commit produce una
nuova radice, e i reader la prendono con una lettura atomica.

**Pro**
- I reader non si bloccano mai e non bloccano il writer.
- Le versioni passate costano poco: si sposa con la [conservazione delle
  versioni](/architecture/version-retention.md) (k > 0) e con le
  [letture coerenti](/mechanisms/consistent-reads.md).
- Una versione resta valida finché qualcuno la usa: niente attese.

**Contro**
- Tutti gli indici vanno riscritti; lo schema degli slot compatti con
  lo spostamento dell'ultima entità non funziona più così com'è.
- Ogni scrittura alloca nodi nuovi: costo maggiore delle scritture, già
  oggi il punto debole (vedi [capacità degli aggiornamenti per
  tick](/indices/update-capacity.md)), e pressione sul GC in Java.
- Le versioni vecchie vanno liberate quando nessun reader le usa più
  (GC in Java, reference counting o epoch-based reclamation in un
  linguaggio nativo).

## 4. Lettura ottimistica (seqlock)

Il writer incrementa un contatore all'inizio e alla fine di ogni
scrittura; i reader leggono senza lock e alla fine controllano che il
contatore non sia cambiato, altrimenti ripetono la lettura (per esempio
con `StampedLock.tryOptimisticRead`).

**Pro**
- Nessuna memoria in più; i reader non bloccano mai il writer.
- Costo quasi nullo per i reader quando non ci sono scritture.

**Contro**
- Con strutture mutabili un reader può vedere uno stato a metà (nodi
  divisi a metà, slot spostati) e fallire con un'eccezione o un ciclo
  prima del controllo: tutto il codice di lettura degli indici deve
  tollerare stati non coerenti.
- Rispettare il commit significa considerare "in scrittura" tutto il
  commit: le letture riuscirebbero solo tra un commit e l'altro.
- Le letture lunghe possono ripartire all'infinito.

## 5. Fasi del tick

Il tick si divide in una fase di scrittura e una fase di lettura,
separate da una barriera: i reader lavorano in parallelo solo quando il
writer non scrive. È l'alternativa 1 organizzata nel tempo.

**Pro**
- Semplice e deterministico; nessuna memoria in più; storage e indici
  restano come sono.
- Schema comune nei motori di gioco.

**Contro**
- Lega i reader al ritmo del tick: vale per i thread del gioco, non per
  reader esterni o con letture lunghe.
- Le due fasi non si sovrappongono: il tempo del tick è la somma di
  scrittura e letture.

## 6. Due istanze con replay (Left-Right)

Ogni partizione ha due istanze complete, storage e indice: una per i
reader (R) e una per il writer (W).

- Il writer scrive su W e registra le operazioni del commit.
- Al commit, una scrittura atomica fa diventare W la nuova istanza dei
  reader.
- Prima del commit successivo, il writer aspetta che nessun reader usi
  più la vecchia istanza, poi vi riapplica le operazioni registrate e
  la usa come nuova W.

È la stessa idea della *grace period* di RCU già descritta nelle
[letture coerenti](/mechanisms/consistent-reads.md): non si modifica
finché possono esserci reader iniziati prima.

**Pro**
- I reader non si bloccano mai: entrare e uscire da un'istanza costa un
  contatore.
- Il writer aspetta solo se un reader tiene la vecchia istanza per più
  di un tick, perché l'attesa avviene all'inizio del commit successivo.
- Storage e indici restano mutabili e single-thread come oggi; le
  interfacce degli indici non cambiano.
- Il writer vede le proprie scritture, perché legge da W.

**Contro**
- Memoria doppia per partizione.
- Ogni scrittura si applica due volte; la seconda è fuori dal commit ma
  sempre sul tempo del writer.
- Le due istanze devono restare identiche: storage e indici devono
  essere deterministici (stesse operazioni nello stesso ordine danno
  stessi slot e stessa struttura; nessun ordine che dipende, per
  esempio, dall'iterazione di una mappa hash). Va verificato con un
  test.
- Una lettura più lunga di un tick blocca il writer: serve una
  scadenza per le letture, o una terza istanza.
- Dà solo l'ultimo commit: per k > 0 la [conservazione delle
  versioni](/architecture/version-retention.md) va costruita a parte,
  per esempio con la copia delle sole entità modificate.

# Confronto

| | Chi aspetta | Costo per commit | Memoria | Indici | Il writer vede le sue scritture | Versioni passate |
|---|---|---|---|---|---|---|
| 1. Lock reader/writer | writer e reader a vicenda | nessuno (con buffer: applicazione sotto lock) | 1× | invariati | sì (con buffer: no) | no |
| 2. Copia completa | nessuno | O(n) | 2× e più | copia o ricostruzione | sì | costose |
| 3. Strutture persistenti | nessuno | allocazioni per ogni scrittura | 1× più versioni vive | da riscrivere | sì | economiche |
| 4. Lettura ottimistica | i reader ripetono | nessuno | 1× | da rendere tolleranti | sì | no |
| 5. Fasi del tick | a turno, per fase | nessuno | 1× | invariati | sì | no |
| 6. Due istanze con replay | writer solo per letture oltre un tick | replay delle operazioni | 2× | invariati, deterministici | sì | a parte |

# Punti aperti

- La scelta tra le alternative.
- Chi sono i reader: thread dello stesso processo che seguono il tick
  (AI, rete, rendering) o anche richieste esterne con letture lunghe.
- Quanto durano le letture, anche quelle aggregate su più partizioni
  (vedi [aggregazione delle query](/mechanisms/query-aggregation.md)).
- Se la memoria in più è accettabile, ed eventualmente configurabile
  per partizione come k.
- Come si combina con la [conservazione delle
  versioni](/architecture/version-retention.md) e con la
  [persistenza](/decisions/persistence.md).
- Il confine del commit nel prototipo, che serve con qualunque
  alternativa.

# Correlati

- [Concorrenza](/decisions/concurrency.md) — un solo writer per
  partizione, più reader.
- [Commit della partizione](/architecture/partition-commit.md) — i
  reader vedono solo commit completi.
- [Conservazione delle versioni](/architecture/version-retention.md) e
  [letture coerenti](/mechanisms/consistent-reads.md) — letture al tick
  N su più partizioni.
- [Modulo degli alberi](/indices/tree-module.md) — letture sicure da più
  thread negli indici.
