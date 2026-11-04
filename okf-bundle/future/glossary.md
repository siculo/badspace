---
type: Glossary
title: "Glossario degli sviluppi futuri"
description: "Termini dei protocolli ancora da implementare, da leggere dopo il glossario corrente."
tags: [badspace, documentation]
status: draft
timestamp: 2026-11-04T23:42:12Z
generated: { by: claude-code/claude-opus-5-5, at: 2026-11-02T16:49:34Z }
---

# Termini

Il [glossario corrente](/current/glossary.md) definisce le primitive già disponibili.

| Termine | Significato |
|---|---|
| Buffer delle scritture | Modifiche finali per ID accumulate nel writer e inviate insieme al commit; conserva anche cancellazioni e azzeramento dei metadati dopo rimozione e reinserimento. Vedi [scritture accumulate](/future/architecture/buffered-writes.md). |
| Set di un'entità | Operazione futura che fissa la posizione e inserisce l'entità se assente, anche con un ID già rimosso; sostituisce `update`. Vedi [scritture accumulate](/future/architecture/buffered-writes.md#semantica-delle-operazioni). |
| Gruppo di partizioni | Raggruppamento futuro per semplificare configurazione e gestione degli insiemi da interrogare, eventualmente con sottogruppi; semantica ancora da progettare sopra l’API delle partizioni. Vedi [estensioni dell’API](/future/decisions/api-surface.md#gruppi-di-partizioni). |
| Servizio che ospita nodi | Processo remoto che potrà ospitare più nodi, ciascuno riservato a un solo Space; identità e ciclo di vita da progettare. Vedi [nodi e Space](/future/architecture/nodes-and-spaces.md). |
| Epoca | Contatore di sistema che cresce a ogni migrazione e stabilisce quale copia è valida. Vedi [metadati delle entità](/future/architecture/entity-metadata.md). |
| Tombstone | Metadati di sistema che restano dopo la rimozione e impediscono le resurrezioni. Vedi [tombstone](/future/architecture/tombstones.md). |
