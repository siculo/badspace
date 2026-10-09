---
type: Architecture Primitive
title: Conservazione delle versioni
description: Ogni partizione può conservare lo stato degli ultimi k commit (k configurabile, default 0) tenendo vivi gli snapshot dei commit, base delle letture coerenti su più partizioni.
tags: [badspace, architecture, partitioning, consistency]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-09T16:24:01Z }
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
- **Vita delle versioni tenute dai reader.** Oggi nel prototipo un
  reader può tenere una versione (`lastVersion()`) senza limiti: ogni
  versione tenuta costa una copia degli slot, e nel [linguaggio
  finale](/decisions/language.md), senza GC, una versione tenuta a lungo
  è memoria che non si libera. Direzione: misurare la vita in commit e
  non in tempo, perché il costo è memoria per versione. Sono garantite
  le ultime k+1 versioni; il reader legge al commit N invece di tenere
  l'oggetto, anche attraverso gRPC, e fuori dalla finestra la lettura
  fallisce con un errore "versione non disponibile". Non serve un
  rilascio esplicito: è scartato il pin con rilascio, che richiederebbe
  comunque una scadenza per i reader che non rilasciano. Le chiamate in
  corso restano protette dal conteggio dei reader o dall'epoch-based
  reclamation (vedi [isolamento delle
  letture](/decisions/read-isolation.md#il-commit)). Da decidere come si
  chiude la finestra dopo la rimozione della partizione, il cui
  contatore si ferma: la rimozione chiude la finestra, oppure si usa un
  contatore che continua, come il tick globale. Una versione letta dopo
  la rimozione non è sbagliata in sé: mostra la partizione al suo
  commit.

# Correlati

- [Architettura minima](/architecture/minimal-core.md) — primitiva 5.
