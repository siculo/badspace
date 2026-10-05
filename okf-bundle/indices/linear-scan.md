---
type: Spatial Index
title: Scansione lineare
description: Indice senza struttura che legge tutte le entità della partizione a ogni query; è il riferimento per i test di correttezza e per i benchmark degli altri indici.
tags: [badspace, spatial-indexing, linear-scan]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-05T21:59:03Z }
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

- **Test del box con salti.** `Box2.contains` e `Box3.contains` usano
  `&&`, quindi fanno un salto per ogni confronto. Con i dati uniformi il
  primo confronto è vero circa nella metà dei casi e il processore
  sbaglia spesso la previsione: nelle misure del profilo completo
  (2026-10-04, 100 000 entità) un range a box costa 340 µs, uno a
  cerchio, che fa solo calcoli e un confronto, 136 µs. Il riferimento è
  quindi più lento del necessario, e i rapporti delle query a box sono
  troppo favorevoli agli altri indici. Il test è lo stesso in tutti gli
  indici: la correzione (`&` al posto di `&&`) è da fare, insieme a
  quelle della [grid uniforme](/indices/uniform-grid.md#problematiche).

# Decisioni

- Prima implementazione dell'interfaccia comune degli indici, in 2D e
  3D (`LinearScanIndex2`/`LinearScanIndex3`, `IndexConfig.LinearScan`).
- Misure di riferimento con i profili rapido e completo (2026-10-04)
  salvate nel repository. Dopo la correzione del test del box vanno
  rifatte, con quelle di tutti gli altri indici: sarà il nuovo
  riferimento.

# Correlati

- [Grid uniforme](/indices/uniform-grid.md) — il primo indice spaziale
  confrontato con la scansione lineare.
