---
type: Design Decision
title: "Evoluzioni dello storage degli snapshot"
description: "Possibili ottimizzazioni delle pagine e della mappa ID verso slot; gestione della memoria senza GC nella implementazione finale."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

# Punti aperti

- Pagine con poche entità in movimento sparse negli slot: pagine più
  piccole, oppure le entità in movimento raccolte in slot vicini (per
  esempio separando entità statiche e dinamiche, vedi [entità statiche
  e dinamiche](/future/decisions/static-vs-dynamic-entities.md)). Le misure
  dicono che il costo assoluto è piccolo, quindi non è urgente.
- Copia dei nodi negli indici ad albero: è la parte più grande del
  costo degli snapshot della griglia di quadtree (vedi le misure in
  [Costi](/current/decisions/read-isolation.md#costi)); da rivedere con le ottimizzazioni degli indici.
- Mappa ID → slot: nel prototipo tabella copiata; da rivedere con le
  misure se la copia pesa con molte entità e inserimenti o rimozioni a
  ogni commit (alternativa HAMT).
- Contatori dei reader per snapshot o epoch-based reclamation nel
  [linguaggio finale](/current/decisions/language.md), senza GC.
