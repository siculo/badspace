---
type: Spatial Index
title: "Ottimizzazioni della grid uniforme"
description: "Proposte per evitare scansioni inutili nelle query range della grid; attività in pausa."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato

Il lavoro sugli indici è in pausa. I limiti misurati sono descritti nella
[grid corrente](/current/indices/uniform-grid.md#problematiche).

# Problematiche

Tre ottimizzazioni restano da valutare:

1. Usare il test del box senza salti, insieme alla
   [scansione lineare](/future/indices/linear-scan.md).
2. Aggiungere tutte le entità delle celle completamente interne al box
   senza verificarle una a una, come avviene già per i sottoalberi della
   griglia di quadtree. Il guadagno atteso è maggiore con selettività alte.
3. Scartare le celle esterne al cerchio o alla sfera prima di leggere i
   punti: il box circoscritto può coprire un cluster quasi intero anche
   quando la regione ne tocca solo un bordo.

Le modifiche devono preservare i risultati del riferimento e il
comportamento sui limiti delle coordinate e sugli arrotondamenti.
