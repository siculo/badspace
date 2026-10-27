---
type: Reference
title: Casi d'uso
description: I casi d'uso che BADSPACE vuole supportare, ciascuno con la sua configurazione delle partizioni e i soli meccanismi che gli servono.
tags: [badspace, use-cases, partitioning]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-27T15:24:41Z }
---

# Stato e prerequisiti

Questo documento descrive uno sviluppo futuro, non una funzionalità disponibile nel prototipo. Le primitive già implementate si consultano nella [documentazione corrente](/current/index.md).

# Principio

Lo stesso nucleo regge configurazioni molto diverse, perché ogni caso
attiva solo i meccanismi che gli servono (vedi i principi
dell'[architettura minima](/future/architecture/minimal-core.md)). La tabella
conserva gli scenari che richiedono sviluppi futuri. I
[casi già realizzabili](/current/use-cases.md) sono nella parte corrente.
Gli scenari specifici dei giochi sono indicati con "Gioco".

# Casi d'uso

| Caso d'uso | Come si configura | Meccanismi attivi |
|---|---|---|
| Gioco: aree non contigue, per esempio sistemi solari separati da distanze enormi | Una o più partizioni per area, tutte con il tick comune dello spazio; k = 1; insiemi scelti dal software, eventualmente aiutato da futuri gruppi; il viaggio di una nave tra sistemi è una migrazione | Solo [migrazione](/future/mechanisms/entity-migration.md) |
| Gioco: mondo a zone contigue con confini | Partizioni per zona, gTick, k > 1 sulle partizioni di confine; le query vicino al confine interrogano più zone | Migrazione, [letture coerenti al tick N](/future/mechanisms/consistent-reads.md), [query su più partizioni](/current/architecture/multi-partition-queries.md) |
| Gioco: strategia mista per tipo di entità | Geometria statica divisa per zone con indice build-once; proiettili (molte scritture, poche query, densità regolare) in un'unica partizione globale con una grid uniforme a celle grandi, l'indice più economico nelle scritture; giocatori (molte query, spesso raccolti in gruppi densi) con una griglia di quadtree, che non crolla nelle query con i dati a cluster ma costa di più nelle scritture | Aggregazione tra partizioni con indici diversi |
| Carico sbilanciato tra zone | Si cede un'intera partizione calda a un altro writer, o la si divide | [Ribilanciamento e split](/future/mechanisms/rebalancing-and-split.md) |
| Server distribuito o cluster | Partizioni su [nodi](/current/architecture/layers.md#nodi) separati (processi o macchine), contratto dei nodi esposto via gRPC; l'API resta nel processo del software | Tutti, con coordinamento esterno del gTick |

# Space indipendenti e risorse condivise

Un caso distinto sono simulazioni con tempi indipendenti, rappresentate da
Space diversi. L'eventuale hosting dei loro nodi nello stesso servizio
remoto e il trasferimento di entità tra simulazioni sono evoluzioni
separate: vedi [nodi, servizi e Space](/future/architecture/nodes-and-spaces.md).
Non sono prerequisiti del caso dei sistemi solari con un tick comune.

# Ricerche di base

L’obiettivo per le ricerche comprende range per
sapere cosa c'è in un'area, k-nearest per trovare le entità più vicine,
raycast per linee di vista e intersezioni. In un gioco, per esempio,
servono a sapere chi è in un'area, a trovare i bersagli più vicini e a
calcolare linee di tiro e visibilità.

# Correlati

- [Partizionamento del DB](/current/decisions/partitioning.md).
- [Panoramica](/current/overview.md).
