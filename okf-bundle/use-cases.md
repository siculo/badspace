---
type: Reference
title: Casi d'uso
description: I casi d'uso che BADSPACE vuole supportare, ciascuno con la sua configurazione delle partizioni e i soli meccanismi che gli servono.
tags: [badspace, use-cases, partitioning]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-28T17:14:44Z }
---

# Principio

Lo stesso nucleo regge configurazioni molto diverse, perché ogni caso
attiva solo i meccanismi che gli servono (vedi i principi
dell'[architettura minima](/architecture/minimal-core.md)). Sono tutti
casi d'uso che si vuole supportare; quelli specifici dei giochi sono
indicati con "Gioco".

# Casi d'uso

| Caso d'uso | Come si configura | Meccanismi attivi |
|---|---|---|
| Gioco: aree non contigue, per esempio sistemi solari separati da distanze enormi | Una partizione per area, ognuna col proprio tick; k = 0; il viaggio di una nave tra sistemi è una migrazione | Solo [migrazione](/mechanisms/entity-migration.md) |
| Gioco: mondo a zone contigue con confini | Partizioni per zona, tick globale, k ≥ 1 sulle partizioni di confine; le query vicino al confine interrogano più zone | Migrazione, [letture coerenti al tick N](/mechanisms/consistent-reads.md), [aggregazione](/mechanisms/query-aggregation.md) |
| Gioco: strategia mista per tipo di entità | Geometria statica divisa per zone con indice build-once; proiettili in un'unica partizione globale con grid; giocatori con quadtree | Aggregazione tra partizioni con indici diversi |
| Gioco: interazioni tra giocatori | Metadati applicativi come riserve: un processo prenota un'entità per un'operazione a due | Scritture condizionate |
| Gioco: entità effimere, come i proiettili | Metadato di scadenza: l'entità scade dopo un certo tick | [Metadati applicativi](/architecture/entity-metadata.md) |
| Modifiche da processi che non sono il writer | Versione applicativa per entità: "applica se la versione è ancora v", il writer accetta o rifiuta senza lock | Concorrenza ottimistica sui metadati |
| Carico sbilanciato tra zone | Si cede un'intera partizione calda a un altro writer, o la si divide | [Ribilanciamento e split](/mechanisms/rebalancing-and-split.md) |
| Server distribuito o cluster | Partizioni come processi o nodi separati, API esposta via gRPC | Tutti, con coordinamento esterno del tick |
| Uso non legato al gioco | Commit a batch invece che a tick, per esempio per dati di posizione aggiornati a intervalli | A scelta |

# Ricerche di base

In ogni applicazione le ricerche di base restano le stesse: range per
sapere cosa c'è in un'area, k-nearest per trovare le entità più vicine,
raycast per linee di vista e intersezioni. In un gioco, per esempio,
servono a sapere chi è in un'area, a trovare i bersagli più vicini e a
calcolare linee di tiro e visibilità.

# Correlati

- [Partizionamento del DB](/decisions/partitioning.md).
- [Panoramica](/overview.md).
