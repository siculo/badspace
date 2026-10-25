---
type: Design Decision
title: "Generazione degli ID nel cluster"
description: "Assegnazione dei generatorId tra processi e riavvii, lease e possibili modifiche della divisione dei bit Snowflake."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

## Assegnazione del generatorId

Il `generatorId` arriva **dall'esterno** del generatore, e ogni istanza
ne riceve **uno nuovo a ogni avvio**. Lo assegna il **livello che
gestisce le istanze**, con uno stato persistente che dice quali
`generatorId` sono in uso e quando sono stati rilasciati. Il generatore
offre il meccanismo; l'assegnazione è una decisione presa più in alto
(vedi il principio "Istanze dentro un sistema"
nell'[architettura corrente](/current/architecture/minimal-core.md#principi)).

- Un `generatorId` rilasciato si riusa solo dopo un **periodo di
  attesa** almeno pari al limite di anticipo: così il nuovo proprietario
  parte da un tempo successivo a qualunque timestamp usato dal vecchio.
- Nel cluster l'assegnazione passa per un **lease** con scadenza.

Questo schema risolve insieme due problemi: i **riavvii**, perché
un'istanza riavviata non riusa il `generatorId` di prima con un tempo
che potrebbe essere indietro, e l'**assegnazione nel cluster**.

Con il riuso, i `generatorId` necessari sono quelli delle istanze
attive più quelli rilasciati da meno del periodo di attesa: 1024 bastano
per il numero di istanze previsto.

# Punti aperti

- Snowflake: divisione dei bit tra `generatorId` e sequenza. L'alternativa
  principale è 41/12/10: 4096 generatori e 1.024.000 ID al secondo
  per generatore. La decisione è rimandata alla fase di chiusura
  (implementazione finale e forma a cluster), perché dipende dal
  numero di processi API e dagli ID al secondo richiesti a ogni
  generatore; cambiarla dopo non crea collisioni (vedi [Formato
  dell'ID](/current/decisions/entity-ids.md#formato-dellid)).

# Compatibilità del formato

La **divisione dei bit bassi** tra `generatorId` e sequenza si può
invece cambiare anche più avanti, per esempio da 41/10/12 a 41/12/10.
Gli ID generati dopo il cambio hanno un timestamp maggiore di tutti
quelli vecchi e quindi non coincidono con nessuno di loro, purché il
timestamp resti negli stessi bit e il tempo del generatore non torni
indietro al momento del cambio. La scelta è rimandata (vedi [Punti
aperti](#punti-aperti)).
