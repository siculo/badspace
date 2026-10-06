---
type: Protocol
title: Letture coerenti su più partizioni
description: Letture su più partizioni riferite allo stesso tick N tramite tick globale e conservazione delle versioni; la rimozione differita è un'alternativa più economica che garantisce solo che ogni entità si veda almeno una volta.
tags: [badspace, partitioning, consistency, protocol]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T16:41:33Z }
---

# Problema

Un processo che legge da più partizioni (per esempio le entità vicine
a un punto prossimo al confine tra due zone) può riferirsi a istanti
diversi per ciascuna. Durante una
[migrazione](/mechanisms/entity-migration.md), un lettore che
interroga B prima dell'inserimento e A dopo la rimozione non vede
l'entità da nessuna parte.

# Soluzione: lettura al tick N

- Tutte le partizioni coinvolte usano il **tick globale** come
  contatore dei [commit](/architecture/partition-commit.md).
- Il lettore sceglie N non superiore all'ultimo commit di ciascuna
  partizione e non inferiore al più vecchio conservato da ciascuna
  (vedi [conservazione delle versioni](/architecture/version-retention.md)).
- k e l'eventuale barriera vanno dimensionati in modo che il ritardo
  massimo tra le partizioni coinvolte non superi k tick.
- In ogni partizione il lettore legge lo **snapshot del commit N**:
  ogni commit pubblica uno snapshot completo della partizione (vedi
  [isolamento delle letture](/decisions/read-isolation.md)), e
  conservare k versioni vuol dire tenere vivi gli ultimi k snapshot.

Poiché nella migrazione A rimuove in un tick strettamente successivo a
quello dell'inserimento in B, in ogni commit N l'entità è presente in
almeno una delle due partizioni.

# Alternativa: rimozione differita

Il buco non dipende dall'ordine delle scritture, che l'ACK già
garantisce, ma dalla **durata della lettura**: il lettore non vede E se
legge B all'istante T1, prima dell'inserimento (Ti), e A all'istante
T2, dopo la rimozione (Tr). Succede quando

    T2 − T1 > Tr − Ti

cioè quando la lettura su più partizioni dura più dell'intervallo tra
inserimento e rimozione. Invece delle versioni conservate si può
quindi:

- **differire la rimozione**: A rimuove E almeno D dopo l'inserimento
  in B; nel modello a tick, B inserisce al tick t+1 e A rimuove al tick
  t+1+k;
- **limitare la durata delle letture**: una lettura su più partizioni
  deve concludersi entro D (non attraversare più di k confini di tick).
  Se la scadenza viene superata, i risultati si scartano e la lettura
  si ripete, oppure si segnalano come possibilmente incompleti.

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
| Costo | quasi nullo: un ritardo in A e una scadenza sulle letture | versioni conservate e coordinamento sul tick N |
| Vista coerente nel tempo tra partizioni | **no** | **sì** |

La rimozione differita garantisce solo che **ogni entità si veda almeno
una volta**. Se al consumatore serve una fotografia coerente
(collisioni tra entità di partizioni diverse, fisica, replay
deterministico) serve la lettura al tick N.

# Correlati

- [Aggregazione dei risultati](/mechanisms/query-aggregation.md).
- [Architettura minima](/architecture/minimal-core.md) — nei casi con
  aree non contigue questo meccanismo non serve.
