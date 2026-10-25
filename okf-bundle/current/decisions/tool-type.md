---
type: Design Decision
title: "Framework e libreria embedded"
description: "Ruolo del framework, distribuzione locale del prototipo e responsabilità del software sugli accessi."
tags: [badspace, design, architecture]
status: stable
generated: { by: codex/gpt-6, at: 2026-10-25T09:04:35Z }
---

# Ruolo del framework

BADSPACE è un **framework per costruire lo strato di gestione della
base dati del proprio software**. Offre il supporto per basi dati
real-time di entità spaziali in 2D o 3D: primitive delle partizioni e
meccanismi comuni, accessibili attraverso l'API. Gli sviluppatori li
compongono e decidono le politiche e il coordinamento necessari al
proprio caso d'uso, come descritto nell'[architettura a
livelli](/current/architecture/layers.md).

Questo ruolo è distinto dalla forma di distribuzione: libreria
embedded e nodi remoti sono modi di realizzare e distribuire i
componenti del framework.

# Distribuzione attuale

BADSPACE è una libreria embedded: API e nodi locali sono nello stesso
processo del software. Il contratto dei nodi è interno alla libreria.
Le chiamate locali evitano trasporto e latenza di rete.

# Accesso

BADSPACE non controlla gli accessi: non c'è autenticazione né
autorizzazione. È un componente che si integra dentro un software, e
quel software è il suo unico utilizzatore. L'API e il contratto dei
nodi si fidano di chi li chiama: un chiamante può sbagliare per un
ritardo o un guasto, ma non agisce contro il sistema.

I client esterni, che non sono affidabili, non parlano mai direttamente
con BADSPACE. Passano dal software, che decide cosa possono fare e lo
traduce in chiamate all'API.

Il [fencing token](/current/architecture/partition-ownership.md) e le altre
protezioni servono contro errori e ritardi, non contro chiamanti
malevoli.
