---
type: Design Decision
title: Supporto a 2D e 3D
description: BADSPACE supporta 2D e 3D tramite implementazioni parallele (per esempio Point2/Point3, Partition2/Partition3, GridQuadtreeIndex2/GridOctreeIndex3) che espongono un'interfaccia comune, indipendente dal linguaggio di implementazione.
tags: [badspace, design, 2d3d]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T16:41:33Z }
---

# Decisione

Soluzione scelta: **implementazioni parallele** che espongono
un'**[interfaccia](/decisions/language.md#interfaccia) comune** per le
operazioni condivise, invece di forzare una genericità unica su N
dimensioni.

Nel prototipo ogni tipo esiste in due versioni, per esempio:

- coordinate e regioni: `Point2`/`Point3`, `Region2`/`Region3`;
- spazi e partizioni: `Space2`/`Space3` e `Partition2`/`Partition3`,
  con le interfacce comuni `Space` e `Partition`;
- contratto dei nodi: `PartitionNode2`/`PartitionNode3`;
- indici spaziali: `SpatialIndex2`/`SpatialIndex3`, con
  implementazioni come `UniformGridIndex2`/`UniformGridIndex3` e
  `GridQuadtreeIndex2`/`GridOctreeIndex3`.

# Correlati

- L'[indicizzazione spaziale](/decisions/spatial-indexing.md) — ogni
  indice ha una versione 2D e una 3D.
- Il [linguaggio](/decisions/language.md) — definisce il termine
  *interfaccia* e come si realizza nei linguaggi candidati.
- Il [confronto tra C e Rust](/decisions/c-vs-rust.md#astrazione-sulla-dimensione-2d3d)
  — in Rust i const generics permettono anche codice unico sulla
  dimensione, con limiti per i nodi con 2^D figli.
- Il [tipo delle coordinate](/decisions/coordinate-type.md) — `double`
  nei prototipi, scelta finale rimandata; si combina con le varianti
  2D/3D.
