---
type: Design Decision
title: "Java nel prototipo"
description: "Perché il prototipo usa Java, come si interpretano le misure e che cosa significa interfaccia nella documentazione."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Decisione

Il prototipo usa Java, un linguaggio familiare che permette di iterare
rapidamente e offre supporto a concorrenza e test. Il codice serve a
validare le decisioni e può essere sostituito. Storage in array primitivi
e confronti relativi dei benchmark aiutano a distinguere i costi delle
strutture da quelli dell’ambiente Java.

Le misure descrivono questo prototipo: riferimenti, allocazioni e GC ne
influenzano i risultati. Il [processo](/process/development-approach.md#benchmark)
spiega come usare i confronti per prendere decisioni.

# Terminologia

## Interfaccia

Nel bundle un’interfaccia è un contratto astratto di operazioni con la
stessa semantica. Nel codice Java può essere espresso da una `interface`
e dalle classi che la implementano. Le varianti 2D e 3D condividono le
operazioni indipendenti dalla dimensione e hanno tipi separati per le altre.
