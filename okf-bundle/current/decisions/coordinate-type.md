---
type: Design Decision
title: "Coordinate double e limiti"
description: "Coordinate double del prototipo Java: rappresentazione, precisione e limiti delle entità rispetto a quelli delle query."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Decisione

Le coordinate del prototipo sono `double`: record `Point2`/`Point3`
nell’API e array primitivi privati nello storage. Nei benchmark l’unità
è il metro; il tipo non codifica un’unità di misura.

Un’entità occupa 16 byte di coordinate in 2D o 24 in 3D, più 8 byte di
ID, prima dei costi di indice, metadati e snapshot. La serializzazione
dei [salvataggi](/current/decisions/persistence.md#formato) fissa l’ordine dei byte.

# Limiti del double

Un `double` (IEEE 754 binary64) ha 53 bit di mantissa. La distanza tra
due valori consecutivi (ulp) cresce con il valore: tra 2ᵉ e 2ᵉ⁺¹ vale
2ᵉ⁻⁵².

Con una precisione minima richiesta di **1 cm**, la potenza di 2 più
grande che non supera 0,01 m è 2⁻⁷ m (≈ 0,78 cm): serve e ≤ 45, cioè
**|x| < 2⁴⁶ m ≈ 7,04·10¹³ m ≈ 470 UA** per ogni coordinata.

| Distanza | Metri | Rispetto al limite |
|---|---|---|
| Orbita di Nettuno (~30 UA) | 4,5·10¹² | dentro, margine ×15 |
| Eliopausa (~120 UA) | 1,8·10¹³ | dentro |
| **Limite double al cm** | **7,0·10¹³** | ~470 UA |
| Proxima Centauri (~4,2 anni luce) | 4,0·10¹⁶ | fuori, ×570 |

Per confronto, un `float` (24 bit di mantissa) al centimetro arriva
solo a ±131 km (2¹⁷ m).

Due avvertenze:

- **La precisione è relativa, non assoluta**: altissima vicino
  all'origine, peggiora allontanandosi. Il centimetro vale al bordo.
- **Gli errori si accumulano**: integrando posizioni a ogni tick,
  lontano dall'origine gli spostamenti piccoli vengono arrotondati male
  o persi. Il limite pratico è più basso di quello teorico. Per
  esempio, a 200 UA l'ulp è circa 3,9 mm: un'entità a 10 km/h con 30
  tick al secondo si sposta di circa 24 ulp per tick, a 1 km/h di circa
  2 (vedi lo [scenario del sistema
  solare](/current/indices/solar-system-scenario.md#precisione)).

# Limiti nei prototipi

Le entità di una partizione hanno coordinate dentro i limiti fissati
dal suo indice (vedi [limiti delle
coordinate](/current/decisions/spatial-indexing.md#limiti-delle-coordinate)),
mai oltre un tetto globale di `|c| ≤ 2^60`. `NaN` è sempre rifiutato.
Con interi a 64 bit i limiti ci sarebbero comunque: il contratto dei
limiti vale per entrambe le scelte.
