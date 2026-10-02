---
type: Spatial Index
title: Scansione lineare
description: Indice senza struttura che legge tutte le entità della partizione a ogni query; è il riferimento per i test di correttezza e per i benchmark degli altri indici.
tags: [badspace, spatial-indexing, linear-scan]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-02T13:43:31Z }
---

# Come funziona

La scansione lineare non ha strutture dati proprie: le query leggono
direttamente lo storage della partizione.

- **Inserimento, aggiornamento, rimozione:** non fanno nulla
  nell'indice. Il costo è quello dello storage.
- **Range query:** controlla ogni entità e tiene quelle dentro la
  regione, bordo incluso.
- **K-nearest:** calcola la distanza di ogni entità e tiene le `k` più
  vicine, ordinate per distanza e poi per ID.

| Operazione | Costo |
|---|---|
| Aggiornamenti | O(1), nessun lavoro nell'indice |
| Range query | O(n) |
| K-nearest | O(n log k) |
| Memoria | nessuna oltre lo storage |

# Ruolo nel progetto

- È il **riferimento di correttezza**: i test di ogni indice confrontano
  i suoi risultati con quelli della scansione lineare, sugli stessi dati
  e sulle stesse query (vedi [indicizzazione
  spaziale](/decisions/spatial-indexing.md): tutti gli indici devono
  dare gli stessi risultati).
- È il **riferimento dei benchmark**: le misure degli altri indici si
  leggono come rapporti rispetto alla scansione lineare.
- È una scelta reale per **partizioni piccole** o con **molti
  aggiornamenti e poche query**, dove il costo di mantenere una
  struttura non si ripaga.

# Limiti delle coordinate

I limiti sono quelli del tetto globale, `[-2^60, 2^60]` (vedi [limiti
delle coordinate](/decisions/spatial-indexing.md#limiti-delle-coordinate)).

# Problematiche

Nessuna di correttezza. I punti coincidenti e le distribuzioni non
uniformi non cambiano il costo, che dipende solo dal numero di entità.

# Decisioni

- Prima implementazione dell'interfaccia comune degli indici, in 2D e
  3D (`LinearScanIndex2`/`LinearScanIndex3`, `IndexConfig.LinearScan`).
- Misure di riferimento con il profilo rapido salvate nel repository;
  quelle con il profilo completo sono ancora da fare.

# Correlati

- [Grid uniforme](/indices/uniform-grid.md) — il primo indice spaziale
  confrontato con la scansione lineare.
