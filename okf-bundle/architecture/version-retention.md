---
type: Architecture Primitive
title: Conservazione delle versioni
description: Ogni partizione può conservare lo stato degli ultimi k commit (k configurabile, default 0) tenendo vivi gli snapshot dei commit, base delle letture coerenti su più partizioni.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T07:42:53Z }
---

# Primitiva

Ogni partizione può conservare gli stati degli ultimi **k
[commit](/architecture/partition-commit.md)**, con k configurabile per
partizione e **zero come default**. Ogni commit pubblica già uno
snapshot per l'[isolamento delle letture](/decisions/read-isolation.md),
e conservare k versioni significa tenere vive le ultime k radici. Gli
indici condividono i nodi non cambiati (copy-on-write); gli slot
costano una copia per versione.

# Perché sta nel livello base

Senza questa primitiva le [letture coerenti](/mechanisms/consistent-reads.md)
su più partizioni non si possono costruire sopra in modo efficiente.
Per leggere lo stato al tick N da un writer che è già oltre, le sole
strade sono conservare le versioni passate oppure limitare il ritardo
tra i writer con una barriera: non esiste una terza via.

Essendo configurabile per partizione, la pagano solo le partizioni che
ne hanno bisogno (per esempio quelle di confine tra zone contigue).

# Punti aperti

- Costo in memoria degli slot con k alto: una copia per versione, da
  misurare.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 5.
