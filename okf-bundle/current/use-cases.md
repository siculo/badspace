---
type: Reference
title: "Casi d’uso attuali"
description: "Scenari realizzabili con il prototipo corrente ed esempi eseguibili per esplorare l’API."
tags: [badspace, documentation]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Dal componente al caso d’uso

Questi scenari usano i [componenti dell’API](/current/api/components.md)
già presenti. Il software sceglie le politiche e costruisce il coordinamento.

| Esigenza | Come si realizza oggi | Esempio in `prototype/samples` |
|---|---|---|
| Aggiornare posizioni e leggerne lo stato confermato | Writer, commit, avanzamento del tick e acquisizione dell’ultimo snapshot | `random-updater`, `writes-and-commits`, `snapshot-reads` |
| Aggiornare partizioni in parallelo | Un writer per partizione su thread diversi, con tick comune | `multi-writer` |
| Passare una partizione a un altro writer | `claim` sulla generazione attesa, consegna del token nuovo | `ownership-handoff` |
| Sostituire un writer che non risponde | Il coordinatore prende la proprietà; il nodo rifiuta il token vecchio | `writer-failover` |
| Leggere un tick precedente | Configurare `retainedTicks`, acquisire il tick nella finestra, rilasciare la view | `lagged-reader`, `ticks-and-retention` |
| Limitare quanto un reader trattiene memoria | Scadenza obbligatoria delle acquisizioni e rilascio esplicito | `acquisition-timeouts` |
| Rimuovere partizioni lasciando finire i reader | Richiesta del writer, ritiro al tick successivo e distruzione dopo gli ultimi utilizzi | `partition-removal` |
| Usare valori applicativi e scritture condizionate | Metadati per entità, confronto dei valori attesi e modifica atomica | `entity-metadata` |
| Salvare e ricaricare dati | Copia dello snapshot, salvataggio sul nodo e creazione di una nuova partizione dai dati salvati | `partition-saves`, `periodic-saves`, `my-saves` |

# Politiche costruibili sui dati attuali

I metadati applicativi permettono di rappresentare una versione, una
riserva, un responsabile o una scadenza. Il software ne sceglie il
significato e applica le scritture con il token del writer. Per esempio,
una modifica proposta da un altro componente può essere accettata solo
se il metadato di versione è ancora quello atteso. BADSPACE verifica la
condizione atomicamente nella partizione; il software gestisce la proposta.

Il software può distribuire un batch di scritture su più tick e fare
commit alla fine. I tick senza commit ripetono lo stato confermato
precedente. Questo uso non richiede un ciclo a frame di un gioco.

Per la persistenza il software sceglie quando salvare, quali file
conservare e quale salvataggio caricare. Il prototipo offre le operazioni
sulla singola partizione; le [misure](/current/decisions/persistence.md#misure)
ne descrivono costi e limiti.

# Come leggere gli esempi

`spaces-and-partitions` introduce creazione e configurazione. Gli esempi
successivi mostrano aspetti separati dell’API; i test di integrazione ne
verificano le garanzie insieme ai nodi locali. Il metodo di sviluppo è
descritto nel [processo](/process/development-approach.md).
