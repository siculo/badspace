---
type: Spatial Index
title: "Evoluzioni della griglia di quadtree"
description: "Margini loose, capacità delle foglie, compressione e ottimizzazioni del writer ancora da valutare; attività in pausa."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato

Il lavoro sugli indici è in pausa. Comportamento, modello della
frequenza dei cambi di foglia e misure sono nella
[griglia corrente](/current/indices/grid-quadtree.md).

# Problematiche aperte

Le proposte rispondono soprattutto al costo delle scritture nei cluster
densi e ai percorsi nelle strutture copy-on-write:

- **Quadtree loose:** la foglia accetta punti entro un margine esterno
  al proprio box; chi si muove di poco resta nella foglia, e le query
  allargano i box del margine. È una modifica dell’algoritmo, applicabile
  indipendentemente dal linguaggio.
- **Capacità della foglia 32 come default:** da confermare con misure.
  Il default corrente resta 16. Le misure con 32 e 64, già presenti nella
  pagina corrente, mostrano il compromesso tra scritture e query.
- **Compressione:** potrebbe eliminare catene di punti quasi coincidenti;
  oggi le limitano profondità massima e precisione.
- **Riferimento `leafOf` privato del writer:** valido per le foglie del
  commit corrente, per evitare discese ripetute dalla radice. Richiede
  anche una risalita per aggiornare i contatori.

Si può anche accettare il costo attuale e usare indici diversi per
partizioni con molte scritture o molte query. Questa è una politica
possibile con l’API corrente, non una nuova funzionalità dell’indice.

# Modello del margine

- **Quadtree loose:** con un margine `m` su ogni lato, un'entità appena
  inserita percorre circa `m` in più prima di uscire, e il rapporto
  efficace diventa circa `d / (L + 2m)`. Con `m` dell'ordine di `v · DT`,
  `P` scende sotto 1/2 anche con foglie molto piccole.
- **Il margine viene dal software:** `v · DT` lo conosce il software,
  non l'indice, quindi il margine potrebbe essere un parametro della
  partizione, scelto dalla velocità massima delle entità e dalla durata
  del tick; per le partizioni statiche è zero.

## Relazione con il quadtree allineato

Con `cellSize` potenza di 2, le celle sono esattamente i box di un
livello `k` del quadtree infinito con box allineati alle potenze di 2
(vedi [posizione della radice](/future/indices/quadtree.md#posizione-della-radice)).
La mappa delle celle sostituisce tutti i livelli sopra `k` con un salto
diretto in O(1): è una compressione dei livelli alti, senza il codice
del compressed quadtree.
