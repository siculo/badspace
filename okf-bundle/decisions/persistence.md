---
type: Design Decision
title: Persistenza
description: Strategia di persistenza per BADSPACE — snapshot periodici e indipendenti per partizione, senza scritture sincrone nel game loop — rimandata; le partizioni devono esporre l'ultimo commit persistito.
tags: [badspace, design, persistence, partitioning]
status: draft
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-06T16:41:33Z }
---

# Stato

Aspetto importante ma rimandato: snapshot periodici dello stato,
eventualmente verso un DB esterno, senza scritture sincrone nel game
loop.

# Vincoli dal partizionamento

- Con il [partizionamento del DB](/decisions/partitioning.md) gli
  snapshot sono **indipendenti per partizione**.
- Ogni partizione deve esporre l'ultimo commit persistito (vedi
  [durabilità osservabile](/architecture/observable-durability.md)):
  la [migrazione](/mechanisms/entity-migration.md) conferma i passaggi
  solo su scritture durevoli, quindi la frequenza della persistenza
  determina la latenza delle migrazioni.
- Anche le [tombstone](/architecture/tombstones.md) fanno parte dello
  stato persistito.

# Vincoli dall'isolamento delle letture

Ogni commit pubblica uno snapshot della partizione (vedi [isolamento
delle letture](/decisions/read-isolation.md)). Uno snapshot pubblicato è
**immutabile**: si può salvare senza fermare il writer, che intanto
prepara i commit successivi. Basta che il processo di salvataggio tenga
lo snapshot come un qualunque reader, finché non ha finito.

# Correlati

- Il [tipo di strumento](/decisions/tool-type.md) — una futura forma a
  servizio potrebbe centralizzare dove vengono scritti gli snapshot.
