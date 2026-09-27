---
type: Architecture Primitive
title: Tombstone
description: Alla rimozione di un'entità ne restano i metadati di sistema con l'epoca, che impediscono a messaggi vecchi di farla rinascere; vengono eliminate quando il predecessore conferma di non avere più copie in uscita.
tags: [badspace, architecture, partitioning, metadata, migration]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-09-27T18:48:33Z }
---

# Primitiva

Quando un'entità viene rimossa, la partizione non la cancella del tutto:
tiene i suoi [metadati di sistema](/architecture/entity-metadata.md),
nello stesso [commit](/architecture/partition-commit.md) della
rimozione.

```
tombstone(ID, epoca, motivo: migrata verso X | distrutta, tick di rimozione)
```

Niente dati, niente posto nell'indice spaziale, solo una voce nella
mappa ID → metadati. I metadati applicativi sono esclusi, a meno che
non venga richiesto al momento della rimozione.

# Regola di inserimento

Arriva E con epoca `e_in`. La partizione cerca l'ID tra le entità vive
**e** tra le tombstone:

- non trova niente → inserisce;
- trova un record con epoca `e_loc`:
  - se `e_in > e_loc` inserisce, sostituendo entità viva o tombstone;
  - altrimenti rifiuta il messaggio **e risponde comunque con un ACK
    "superato"**, così il mittente smette di reinviare e può rimuovere
    la sua copia.

# Esempio: resurrezione evitata

| Passo | A | B | C |
|---|---|---|---|
| 1 | spedisce E (e+1), l'ACK si perde | inserisce E (e+1) | — |
| 2 | reinvio in ritardo… | spedisce E a C (e+2), riceve l'ACK, rimuove e lascia tombstone(e+2) | inserisce E (e+2) |
| 3 | …il reinvio (e+1) arriva a B | e+1 ≤ e+2: rifiuta e risponde con ACK "superato" | — |
| 4 | riceve l'ACK: E è ancora in uscita con e+1, la rimuove | — | — |

Nel rimbalzo A→B→A, A ha tombstone(e+1) e B rimanda E con e+2: A la
reinserisce. La stessa regola protegge le entità **distrutte**, che
lasciano una tombstone con l'epoca corrente.

# Eliminazione (GC)

Un messaggio vecchio può arrivare solo dal **predecessore**, cioè da
chi aveva spedito E alla partizione, e il predecessore reinvia solo
finché possiede una copia in uscita. B può quindi eliminare la
tombstone di E quando sa che A **non ha più una copia in uscita di E
con epoca ≤ quella della tombstone**:

- **conferma spontanea:** dopo la rimozione A manda `REMOVED(ID, epoca)`;
- **verifica su richiesta:** se la conferma si perde, B chiede ad A se
  ha ancora E in uscita. La risposta è sempre sicura: senza copia in
  uscita, A non reinvierà più.

Per l'ordinamento sulla coppia A→B, la conferma arriva dopo qualunque
reinvio precedente. Se la conferma arriva mentre B ha ancora E viva,
quando B sposterà E potrà rimuoverla senza lasciare tombstone.

Un TTL puro non basta: ogni reinvio è un messaggio nuovo, quindi se la
tombstone scade mentre A reinvia ancora, E resuscita. Il TTL può
servire solo come rete di sicurezza insieme alla verifica.

La politica di GC spetta alla [migrazione](/mechanisms/entity-migration.md);
il livello base offre solo la primitiva di eliminazione. Chi non usa
le migrazioni di fatto non ha tombstone.

# Puntatore di inoltro

Una tombstone con la destinazione funziona anche da puntatore di
inoltro: una lettura per ID su una partizione non aggiornata riceve
"spostata verso C" invece di "non trovata".

# Correlati

- [Architettura minima](/architecture/minimal-core.md).
- [ID delle entità](/decisions/entity-ids.md) — mai riusati, quindi
  una tombstone non collide con un'entità nuova.
