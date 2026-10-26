---
type: Design Decision
title: "Proprietà e recupero dei nodi"
description: "Assegnazione dei writer e validità dei token durante il recupero dei nodi; alternative sulla persistenza della generazione."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

# Punti aperti

- Ripresa di un nodo mentre gli altri continuano: verificare il rapporto
  tra partizioni ricreate, assegnazione dei writer e continuità del tempo
  logico nel [recupero](/future/decisions/persistence.md#recupero-ricostruttivo).

# Direzione scelta per il recupero

Il [recupero ricostruttivo](/future/decisions/persistence.md#recupero-ricostruttivo)
ricrea partizioni con ID nuovi: i token precedenti non tornano validi
anche senza persistere la generazione. La conservazione della generazione
è necessaria solo se si decide di riprendere la stessa identità di runtime.
