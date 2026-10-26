---
type: Design Decision
title: "Evoluzioni dell’indicizzazione spaziale"
description: "Indici non implementati, limiti espliciti delle partizioni e confronti ancora aperti; lavoro in pausa."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Opzioni considerate

Più strutture possibili, con trade-off diversi:

- **[Grid uniforme](/current/indices/uniform-grid.md)** — semplice, O(1) su
  densità regolare.
- **[Quadtree / octree](/future/indices/quadtree.md)** — si adatta a densità
  non uniformi.
- **[Griglia di quadtree](/current/indices/grid-quadtree.md)** — grid in cui
  ogni cella è la radice di un quadtree o di un octree.
- **[R-tree](/future/indices/r-tree.md)** — buono per bounding box eterogenei.

Ogni indice ha un suo documento nella sezione [indici
spaziali](/current/indices/index.md), con il funzionamento, le problematiche e le
decisioni prese o da prendere. La [scansione
lineare](/current/indices/linear-scan.md) è il riferimento per i test e i
benchmark.

# Indice per partizione

Sono aperti il supporto a più indici nella stessa partizione e indici
build-once per geometria statica. Il quadtree con una sola radice è
rimandato; l’R-tree non è prototipato. Il lavoro sugli indici resta in pausa.

# Limiti più stretti

Il software potrebbe dichiarare alla creazione limiti più stretti di
quelli dell’indice. Per un quadtree con una sola radice potrebbero
definire il box iniziale. È una proposta, non un parametro attuale.

# Confronto e misure da completare

Le misure presenti descrivono le implementazioni già realizzate. Restano
la valutazione dei compromessi e le misure dopo le ottimizzazioni delle
query range; le attività restano nel TODO degli indici, in pausa.
