---
type: Design Decision
title: "Evoluzioni del partizionamento"
description: "Problemi di confine, query e migrazione tra partizioni, ribilanciamento e supporto a più indici ancora da valutare."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Perimetro

Questa pagina raccoglie proposte e decisioni ancora da attuare. Le operazioni già disponibili sono descritte in [Implementazione corrente](/current/index.md).

# Problemi spostati fuori dal livello base

- **Strategie con zone disgiunte.** Se il software
  partiziona per zone, tornano i problemi di confine: entità a cavallo
  di due zone, query da allargare, migrazioni. Sono conseguenza della
  strategia scelta, non del livello base. Strategie pronte potranno
  essere offerte come componenti opzionali.
- **Migrazione non atomica.** Tra partizioni con writer diversi,
  rimozione e inserimento non sono atomici: risolto dal protocollo di
  [migrazione](/future/mechanisms/entity-migration.md).
- **Consistenza tra partizioni.** Letture su partizioni diverse possono
  riferirsi a istanti diversi: vedi
  [letture coerenti](/future/mechanisms/consistent-reads.md).
- **Sbilanciamento del carico.** Una partizione "calda" resta un
  problema della strategia scelta; lo strumento è il
  [ribilanciamento](/future/mechanisms/rebalancing-and-split.md).

# Punti aperti

- Se una partizione può avere più di un indice.
- Forma delle query su insiemi espliciti di partizioni e delle chiamate
  di migrazione. Le scritture e le query sulla singola partizione sono
  già nel [contratto corrente](/current/api/operations.md).

# Verifica proposta

Un prototipo mirato, secondo l'[approccio di
sviluppo](/process/development-approach.md): più partizioni con indici
diversi, query range, k-nearest e raycast su più partizioni con
aggregazione, migrazione di entità tra partizioni con writer diversi.
