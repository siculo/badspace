---
type: Reference
title: "Casi d’uso attuali"
description: "Scenari realizzabili con il prototipo corrente ed esempi eseguibili per esplorare l’API."
tags: [badspace, documentation]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-27T20:31:26Z }
---

# Dal componente al caso d’uso

Questi scenari usano i [componenti dell’API](/current/api/components.md)
già presenti. Il software sceglie le politiche e costruisce il coordinamento.

| Esigenza | Come si realizza oggi | Esempio in `prototype/samples` |
|---|---|---|
| Aggiornare posizioni e leggerne lo stato confermato | Writer, commit, avanzamento del tick e acquisizione dell’ultimo snapshot | `getting-started`, `commits-and-ticks`, `snapshot-queries`, `snapshot-isolation`, `concurrent-reader-writer` |
| Aggiornare partizioni in parallelo | Un writer per partizione su thread diversi, con tick comune | `multi-writer` |
| Far avanzare in modo indipendente più simulazioni nello stesso processo | Uno spazio per simulazione, ciascuno con i propri nodi creati dalla factory e il proprio tick | Nessun esempio; lo verificano i test di integrazione `SpaceNodes2EndToEndTest` e `SpaceNodes3EndToEndTest` |
| Passare una partizione a un altro writer | `claim` sulla generazione attesa, consegna del token nuovo | `ownership-handoff` |
| Sostituire un writer che non risponde | Il coordinatore prende la proprietà; il nodo rifiuta il token vecchio | `writer-failover` |
| Leggere un tick precedente | Configurare `retainedTicks`, acquisire il tick nella finestra, rilasciare la view | `snapshot-retention`, `lagged-reader` |
| Interrogare insieme più partizioni, per esempio entità statiche e dinamiche con indici diversi | Insieme di snapshot dello stesso tick, query aggregate e decisione del software sui fallimenti | `multi-partition-queries` |
| Limitare quanto un reader trattiene memoria | Scadenza obbligatoria delle acquisizioni e rilascio esplicito | `acquisition-timeouts` |
| Rimuovere partizioni lasciando finire i reader | Richiesta del writer, ritiro al tick successivo e distruzione dopo gli ultimi utilizzi | `partition-removal` |
| Usare valori applicativi e scritture condizionate | Metadati per entità, confronto dei valori attesi e modifica atomica | `entity-metadata`, `conditional-writes` |
| Salvare e ricaricare dati | Copia dello snapshot, salvataggio sul nodo e creazione di una nuova partizione dai dati salvati | `save-and-load`, `save-management`, `periodic-saves` |

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

`getting-started` mostra il percorso completo più breve, dalla creazione
della partizione alla lettura di uno snapshot. Gli altri esempi rispondono
ciascuno a una domanda sull’API e si eseguono da soli; il README di
`prototype/samples` li ordina per tema e ne indica i prerequisiti
concettuali. I test di integrazione verificano le garanzie mostrate
insieme ai nodi locali. Il metodo di sviluppo è
descritto nel [processo](/process/development-approach.md).
