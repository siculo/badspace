---
type: Design Decision
title: "Misure future della capacità per tick"
description: "Misure al variare del passo, benchmark a tick e regole di dimensionamento delle partizioni ancora da completare."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

# Misure da fare

- **Update al variare del passo:** il passo del movimento è un
  parametro dei benchmark (`step`); il piano `plans/update-step.json`
  misura `c0 + P(r) · c1` al variare di `r`, con i passi dello [scenario
  del sistema solare](/current/indices/solar-system-scenario.md), per ricavare
  `c0` e `c1` di ogni indice da più punti.
- **Benchmark a tick degli aggiornamenti:** dati indice, distribuzione,
  velocità `v` e tick al secondo `f`, aggiorna tutte le `N` entità a
  ogni tick e misura il tempo per tick (p50, p99, massimo) al variare
  di `N`, anche con moti correlati. Dà direttamente quante entità per
  partizione restano entro `β · DT` al p99.
- **Regola di dimensionamento per l'utente:** `N_max = β / (f · (c0 +
  c1))` con i valori di `c0` e `c1` per ogni indice.
