---
type: Reference
title: "Nucleo delle partizioni"
description: "Garanzie e principi del livello base già presenti: proprietà, scritture, commit, tempo logico, snapshot, rimozione e persistenza."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Criterio

Nel livello base stanno le operazioni che richiedono accesso atomico
allo stato della singola partizione. Il software sceglie politiche e
coordinamento usando l’[API](/current/api/components.md).

# Principi

- **Nucleo minimo.** Le decisioni applicative restano sopra la partizione.
- **Una partizione, un writer.** La proprietà si controlla con un token;
  non ci sono conflitti tra writer validi sulla stessa partizione.
- **Nessuna transazione tra partizioni.** Batch e commit proteggono solo
  lo stato di una partizione.
- **Costi per partizione.** Indice e finestra di conservazione si scelgono
  separatamente; i contenuti immutabili condivisi evitano copie inutili.
- **Istanze dentro un sistema.** Il software decide quante partizioni
  servono, dove ospitarle e a chi assegnarle. I meccanismi restano corretti
  se un writer riprende in ritardo: il nodo rifiuta il token superato.

# Operazioni e garanzie disponibili

| Componente | Garanzia | Approfondimento |
|---|---|---|
| Proprietà | Al più un token di scrittura valido per partizione | [Proprietà](/current/architecture/partition-ownership.md) |
| Scritture e metadati | Batch tutto-o-niente; condizioni verificate insieme alle modifiche | [Metadati](/current/architecture/entity-metadata.md) |
| Commit | Conferma cumulativa del writer; nessuna lettura dello stato non confermato | [Commit](/current/architecture/partition-commit.md) |
| Tempo logico | Pubblicazione al termine del tick; transizione atomica sulla singola partizione | [Tick](/current/architecture/logical-time.md) |
| Letture | Snapshot immutabili, conservazione per tick, acquisizione e rilascio con scadenza | [Snapshot](/current/architecture/snapshot-retention.md) |
| Rimozione | Letture storiche conservate fino alla fine della finestra e degli utilizzi | [Rimozione](/current/architecture/partition-removal.md) |
| Persistenza | Copia autonoma e salvataggio di uno snapshot; caricamento in una nuova partizione | [Persistenza](/current/decisions/persistence.md) |

Le scritture, i commit e l’avanzamento del tick della stessa partizione
sono ordinati da un lock per partizione. I reader non prendono quel lock
e non bloccano il writer. Writer di partizioni diverse lavorano indipendentemente.
