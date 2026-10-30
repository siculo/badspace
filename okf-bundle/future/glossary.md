---
type: Glossary
title: "Glossario degli sviluppi futuri"
description: "Termini dei protocolli ancora da implementare, da leggere dopo il glossario corrente."
tags: [badspace, documentation]
status: draft
generated: { by: codex/gpt-6, at: 2026-10-30T14:51:33Z }
---

# Termini

Il [glossario corrente](/current/glossary.md) definisce le primitive già disponibili.

| Termine | Significato |
|---|---|
| Gruppo di partizioni | Raggruppamento futuro per semplificare configurazione e gestione degli insiemi da interrogare, eventualmente con sottogruppi; semantica ancora da progettare sopra l’API delle partizioni. Vedi [estensioni dell’API](/future/decisions/api-surface.md#gruppi-di-partizioni). |
| Servizio che ospita nodi | Processo remoto che potrà ospitare più nodi, ciascuno riservato a un solo Space; identità e ciclo di vita da progettare. Vedi [nodi e Space](/future/architecture/nodes-and-spaces.md). |
| Epoca | Contatore di sistema che cresce a ogni migrazione e stabilisce quale copia è valida. Vedi [metadati delle entità](/future/architecture/entity-metadata.md). |
| Tombstone | Metadati di sistema che restano dopo la rimozione e impediscono le resurrezioni. Vedi [tombstone](/future/architecture/tombstones.md). |
| Componente transazionale | Struttura della partizione che prepara e accetta snapshot immutabili e ripristina il proprio stato di lavoro secondo un contratto comune. Vedi [strutture transazionali](/future/architecture/transactional-components.md#contratto-dei-componenti). |
| Rollback esplicito | Operazione del writer che annulla tutte le scritture successive all'ultimo commit riuscito, senza annullare il pending confermato. Vedi [rollback e recupero](/future/architecture/transactional-components.md#rollback-e-recupero-espliciti). |
| Recupero necessario | Condizione della partizione dopo un ripristino fallito: scritture e commit bloccati fino al successo di un recupero esplicito; distinta dagli stati del ciclo di vita. Vedi [fallimenti](/future/architecture/transactional-components.md#fallimenti-e-segnalazioni). |
