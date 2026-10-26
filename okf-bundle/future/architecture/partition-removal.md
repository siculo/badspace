---
type: Design Decision
title: "Verifiche storiche delle partizioni"
description: "Verifiche di esistenza e acquisibilità a un tick da valutare con le letture coerenti su più partizioni."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

# Punti aperti

- Verifica dell'esistenza a un tick (`partitionExistsAt`) e
  dell'acquisibilità (`canAcquireSnapshot`): rimandate alle [letture
  coerenti](/future/mechanisms/consistent-reads.md). Senza tombstone delle
  partizioni, per un ID sconosciuto la risposta può essere solo
  "sconosciuto" (vedi [conservazione degli
  snapshot](/future/architecture/snapshot-retention.md#punti-aperti)).
