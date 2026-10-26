---
type: Design Decision
title: "Evoluzioni della conservazione degli snapshot"
description: "Proposte su copia pubblica degli snapshot, scadenze configurabili, diagnostica, pulizia periodica e acquisizione senza eccezioni."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

# Punti aperti

- Costo in memoria degli slot con k alto: una copia per commit nella
  finestra, da misurare.
- Scadenza massima: oggi è una costante; diventerà una configurazione
  del nodo.
- Copia di uno snapshot per chi deve tenerlo oltre la scadenza
  massima, da progettare.
- Pochi metadati diagnostici (acquisizioni attive, snapshot pinnati,
  tick pinnato più vecchio) solo se servono alla verifica.
- Pulizia periodica delle acquisizioni scadute, se servirà un tempo di
  recupero garantito.
- **Verifica e handle in un'unica operazione.** L'acquisizione al tick n
  verifica già tutte le condizioni e restituisce l'handle in un solo
  passo. `SnapshotNotAvailableException.reason()` è già implementato
  (`NOT_YET_PUBLISHED`, `OUT_OF_RETENTION`, `BEFORE_CREATION`,
  `AFTER_REMOVAL`); resta da valutare nelle [letture
  coerenti](/future/mechanisms/consistent-reads.md) una forma senza eccezione,
  per esempio `tryAcquire` con un risultato sealed (acquisito / non
  disponibile con il motivo), dove "fuori dalla finestra, riprova" è un
  caso normale.
- **`partitionExistsAt` e `canAcquireSnapshot`**: rimandate alle letture
  coerenti, e forse sostituite dalla forma sopra. `canAcquireSnapshot`
  sarebbe solo indicativa, perché tra la verifica e l'acquisizione il
  tick può avanzare. Senza tombstone delle partizioni, per un ID che il
  nodo non conosce più la risposta è "sconosciuto": non si distingue una
  partizione mai esistita da una distrutta.
