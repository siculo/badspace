---
type: Glossary
title: "Glossario degli sviluppi futuri"
description: "Termini dei protocolli ancora da implementare, da leggere dopo il glossario corrente."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Termini

Il [glossario corrente](/current/glossary.md) definisce le primitive già disponibili.

| Termine | Significato |
|---|---|
| Gruppo di partizioni | Insieme di partizioni con una configurazione condivisa (indice, k, politica di rimozione), distinto dall'insieme di partizioni da interrogare; la sua API sta sopra quella delle singole partizioni. Punto aperto: vedi [API da esporre](/future/decisions/api-surface.md#gruppi-di-partizioni). |
| Epoca | Contatore di sistema che cresce a ogni migrazione e stabilisce quale copia è valida. Vedi [metadati delle entità](/future/architecture/entity-metadata.md). |
| Tombstone | Metadati di sistema che restano dopo la rimozione e impediscono le resurrezioni. Vedi [tombstone](/future/architecture/tombstones.md). |
