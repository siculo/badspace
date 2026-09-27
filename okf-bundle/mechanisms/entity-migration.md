---
type: Protocol
title: Migrazione di entità tra partizioni
description: Handoff asincrono di proprietà tra partizioni con writer diversi — entità congelata, batch per tick, ACK dopo persistenza, rimozione condizionata — senza transazioni né lock.
tags: [badspace, partitioning, migration, protocol]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Problema

Spostare una o più entità da una partizione A a una partizione B
gestite da writer diversi, che fanno numerose scritture a ogni tick,
in modo efficiente e affidabile e senza transazioni.

# Idea

Lo spostamento non è una transazione tra due partizioni ma un
**passaggio di proprietà** fatto con messaggi, allineato al tick e
ripetibile senza effetti collaterali. Nessuno scrive mai nella
partizione di un altro. Lo spostamento lo decide il writer della
partizione di origine.

È il pattern *outbox + consumer idempotente*: consegna almeno una volta
più idempotenza, senza two-phase commit né lock sulla partizione di
destinazione.

Primitive usate: [commit](/architecture/partition-commit.md),
[metadati e scritture condizionate](/architecture/entity-metadata.md),
[tombstone](/architecture/tombstones.md),
[durabilità osservabile](/architecture/observable-durability.md).

# Protocollo

| Tick | Writer A (origine) | Writer B (destinazione) |
|---|---|---|
| t | Raccoglie le entità da spostare verso B in un solo batch, le marca "in uscita", incrementa l'epoca. **Attende che il commit sia persistito**, poi manda a B il batch con lo stato completo, eventualmente modificato. | — |
| t+1 | — | All'inizio del tick, prima delle scritture normali, applica i batch ricevuti con inserimento condizionato. **Quando il commit è persistito** manda ad A un ACK con l'ID del batch e il tick di applicazione. |
| > tick dell'ACK | Rimuove le entità **solo se sono ancora in uscita verso B con la stessa epoca**, lasciando una tombstone. Manda a B un messaggio REMOVED. | — |

La latenza dipende dalla durabilità: l'ACK può arrivare dopo t+1 se la
persistenza di B è più lenta di un tick.

# Regole

- **Entità congelata.** Dalla marcatura "in uscita" le entità non sono
  più modificabili in A: ogni scrittura su di esse restituisce un
  errore. Il writer di A ha chiesto lo spostamento e sa di non doverle
  più aggiornare. Non serve inoltrare scritture a B.
- **Stato spedito.** Il batch contiene lo stato che l'entità avrà in B,
  che A può **modificare al momento dell'invio**: dati e metadati
  applicativi, per esempio per cambiare il tag di responsabilità o
  azzerare una riserva. La copia che resta in A è quella congelata; in
  B vince comunque la copia spedita, che ha l'epoca più alta. I
  metadati di sistema li imposta il protocollo.
- **Reinvio.** Finché non riceve l'ACK, A reinvia il batch. Non serve
  una inbox persistente: sono durevoli le due estremità, cioè la
  marcatura in A e l'inserimento in B.
- **Inserimento condizionato.** Secondo la regola delle
  [tombstone](/architecture/tombstones.md): inserisce se l'ID è assente
  o l'epoca in arrivo è maggiore, altrimenti rifiuta e risponde con un
  ACK "superato".
- **Ordine.** Tra una coppia di partizioni i messaggi arrivano
  nell'ordine di invio.
- **Mai zero copie, al più due.** A rimuove solo dopo un ACK di una
  scrittura durevole: si preferisce il duplicato, che si può eliminare,
  alla perdita, che non si può recuperare.
- **Doppioni in lettura.** L'[aggregazione](/mechanisms/query-aggregation.md)
  deduplica per ID: vince la copia con l'epoca più alta, e una copia
  "in uscita" perde se esiste anche l'altra.
- **Mappa ID → partizione.** Il software utilizzatore la aggiorna già
  al tick t.

# Casi di guasto

- **Crash di A prima della persistenza della marcatura.** Il batch non
  è mai partito: al ripristino le entità sono ancora di A.
- **Crash di A dopo l'invio.** Al ripristino A ritrova le entità in
  uscita e riprende a reinviare.
- **Crash di B prima dell'ACK.** A ha ancora le entità e fa fede: il
  reinvio le riporta a B.
- **Catene (A→B→C) e rimbalzi (A→B→A).** L'epoca cresce strettamente
  lungo la catena dei proprietari e stabilisce sempre la copia valida;
  le tombstone impediscono le resurrezioni.

# Efficienza

- Un solo messaggio per coppia di partizioni per tick, qualunque sia il
  numero di entità spostate, più un ACK.
- B applica le migrazioni in un punto preciso del suo tick: non si
  intrecciano con le sue scritture normali e non servono lock.

# Quando usare invece il trasferimento dell'esclusività

Far prendere ad A l'esclusività di scrittura su B per fare rimozione e
inserimento da solo è sconsigliato per le migrazioni di routine:
bloccherebbe il writer di B, che scrive molto in ogni tick. Il
trasferimento dell'esclusività serve per le operazioni in blocco: vedi
[ribilanciamento e split](/mechanisms/rebalancing-and-split.md).

# Correlati

- La primitiva di comodo "sposta da A a B" dell'[API
  comune](/architecture/layers.md) è l'implementazione di questo
  protocollo.
- [Letture coerenti](/mechanisms/consistent-reads.md) — evitano che un
  lettore non veda l'entità durante lo spostamento.
