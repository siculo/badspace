---
type: Protocol
title: Letture coerenti su più partizioni
description: Letture su più partizioni riferite allo stesso tick N tramite gTick e snapshot di ogni tick, acquisito esattamente in ogni partizione; la rimozione differita è un'alternativa più economica che garantisce solo che ogni entità si veda almeno una volta.
tags: [badspace, partitioning, consistency, protocol]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato e prerequisiti

Questo documento descrive uno sviluppo futuro, non una funzionalità disponibile nel prototipo. Le primitive già implementate si consultano nella [documentazione corrente](/current/index.md).

# Problema

Un processo che legge da più partizioni (per esempio le entità vicine
a un punto prossimo al confine tra due zone) può riferirsi a istanti
diversi per ciascuna. Durante una
[migrazione](/future/mechanisms/entity-migration.md), un lettore che
interroga B prima dell'inserimento e A dopo la rimozione non vede
l'entità da nessuna parte.

# Soluzione: lettura al tick N

- Il software fa avanzare tutte le partizioni coinvolte con lo stesso
  tick, il **gTick**, passato a `setTick` dello spazio (vedi [tick
  logico](/current/architecture/logical-time.md)).
- Ogni tick completato ha il suo snapshot in ogni partizione, quindi il
  lettore acquisisce **esattamente `S(N)`** in ogni partizione, senza
  cercare il commit più recente prima di N: un tick senza commit ha lo
  stesso stato del precedente (vedi [conservazione degli
  snapshot](/current/architecture/snapshot-retention.md)).
- Il lettore sceglie N non superiore a `T − 1` di ciascuna partizione e
  non inferiore a `T − k`. La propagazione del tick non è atomica tra
  partizioni: durante un avanzamento alcune partizioni sono già al tick
  nuovo e altre no, e N deve stare nella finestra di tutte.
- k e l'eventuale barriera vanno dimensionati in tick, in modo che il
  ritardo massimo tra le partizioni coinvolte, più la durata
  dell'acquisizione su tutte, resti entro la finestra.
- L'acquisizione è atomica: se nel frattempo N esce dalla finestra,
  fallisce e il lettore riprova con un N più recente. Il lettore
  rilascia le acquisizioni alla fine della lettura. Una partizione
  ritirata dà ancora gli snapshot dei tick prima della sua rimozione;
  per i tick dalla rimozione in poi non esiste più (vedi [rimozione della
  partizione](/current/architecture/partition-removal.md)).
- Ogni partizione pubblica per conto suo: la vista coerente nasce dalla
  scelta dello stesso N, non da una pubblicazione atomica tra
  partizioni. La barriera sta sopra il livello base.

Un'operazione che verifica e acquisisce in un solo passo, dicendo perché
uno snapshot non è disponibile, è un punto aperto da decidere qui (vedi
[conservazione degli
snapshot](/future/architecture/snapshot-retention.md#punti-aperti)).

Poiché nella migrazione A rimuove in un tick strettamente successivo a
quello dell'inserimento in B, in ogni snapshot del tick N l'entità è presente in
almeno una delle due partizioni.

# Alternativa: rimozione differita

Qui "rimozione differita" è la rimozione di un'**entità** dalla
partizione di origine, ritardata durante la migrazione. Non va confusa
con la [rimozione della partizione](/current/architecture/partition-removal.md),
che diventa effettiva al tick successivo.

Il buco non dipende dall'ordine delle scritture, che l'ACK già
garantisce, ma dalla **durata della lettura**: il lettore non vede E se
legge B all'istante T1, prima dell'inserimento (Ti), e A all'istante
T2, dopo la rimozione (Tr). Succede quando

    T2 − T1 > Tr − Ti

cioè quando la lettura su più partizioni dura più dell'intervallo tra
inserimento e rimozione. Invece degli snapshot conservati si può
quindi:

- **differire la rimozione**: A rimuove E almeno D dopo l'inserimento
  in B; nel modello a tick, B inserisce al tick t+1 e A rimuove al tick
  t+1+k;
- **limitare la durata delle letture**: una lettura su più partizioni
  deve concludersi entro D (non attraversare più di k confini di tick).
  Se la scadenza viene superata, i risultati si scartano e la lettura
  si ripete, oppure si segnalano come possibilmente incompleti. Questa
  scadenza riguarda la lettura su più partizioni ed è diversa dalla
  [scadenza di un'acquisizione](/current/architecture/snapshot-retention.md#scadenza).

Con la scadenza la garanzia è certa, non probabilistica: una lettura
lenta non diventa mai una lettura silenziosamente incompleta. È lo
stesso principio della *grace period* di RCU e dell'epoch-based
reclamation: non si elimina finché possono esistere lettori iniziati
prima.

# Confronto

| | Rimozione differita | Lettura al tick N |
|---|---|---|
| Entità che sparisce | risolto, se le letture durano meno di D | risolto |
| Duplicati | più frequenti, già gestiti dalla deduplica per ID ed epoca | come nel caso base |
| Costo | quasi nullo: un ritardo in A e una scadenza sulle letture | snapshot conservati e coordinamento sul tick N |
| Vista coerente nel tempo tra partizioni | **no** | **sì** |

La rimozione differita garantisce solo che **ogni entità si veda almeno
una volta**. Se al consumatore serve una fotografia coerente
(collisioni tra entità di partizioni diverse, fisica, replay
deterministico) serve la lettura al tick N.

# Correlati

- [Aggregazione dei risultati](/future/mechanisms/query-aggregation.md).
- [Architettura minima](/future/architecture/minimal-core.md) — nei casi con
  aree non contigue questo meccanismo non serve.
