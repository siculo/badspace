---
type: Spatial Index
title: "Ottimizzazioni della scansione lineare"
description: "Test del box senza salti e aggiornamento del riferimento dei benchmark; attività in pausa."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Stato

Il lavoro sugli indici è in pausa. Il [comportamento corrente](/current/indices/linear-scan.md#problematiche)
descrive il costo misurato del test del box con salti condizionali.

# Problematiche

La proposta è sostituire `&&` con `&` in `Box2.contains` e
`Box3.contains`, per evitare salti difficili da prevedere con risultati
mescolati. Il cambiamento riguarda tutti gli indici e il riferimento.

# Verifica prevista

Dopo la modifica vanno rifatte le misure di tutti gli indici, con i
profili rapido e completo: i rapporti rispetto alla scansione lineare
devono usare lo stesso test del box. Il vantaggio va misurato, non assunto.
