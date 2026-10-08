---
type: Playbook
title: Approccio di sviluppo
description: BADSPACE viene sviluppato per prototipi minimali, verificati da client di test costruiti man mano, per validare le decisioni di progettazione sul campo; i meccanismi si implementano una volta e si estendono quando servono nuovi dati; le scelte che dipendono dalle prestazioni si basano su benchmark relativi a un riferimento.
tags: [badspace, methodology, prototyping]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-08T07:45:42Z }
---

# Approccio

Procedere per **prototipi minimali** su cui ragionare e rivalutare le
scelte fatte, invece di progettare tutto a priori. Ogni prototipo è
accompagnato da **uno o più client di test** che ne verificano le
funzionalità, per validare le decisioni sul campo. I client non devono
essere necessariamente un gioco.

Ogni decisione resta rivalutabile quando un prototipo porta
informazioni nuove.

# Meccanismi prima, estesi man mano

Un meccanismo si implementa una volta, appena serve, e poi si estende
quando arrivano nuovi dati da gestire, invece di aspettare che ci
siano tutti. Per esempio la [persistenza](/decisions/persistence.md) si
implementa dopo i metadati e si estende alle tombstone quando
arrivano. L'ordine dei prototipi tiene conto solo dei prerequisiti
veri, cioè di ciò che senza un altro pezzo non può funzionare.

# Client di test

I client di test sono un tema trasversale: non è deciso quanti e quali
servano. Si costruiscono man mano, accanto ai prototipi che li
richiedono, per esempio uno scenario con aree non contigue per la
[migrazione](/mechanisms/entity-migration.md) e uno con zone contigue
per le [letture coerenti](/mechanisms/consistent-reads.md).

# Benchmark

Le scelte che dipendono dalle prestazioni, come quella degli [indici
spaziali](/indices/), si prendono sulla base di misure:

- **Strumento.** Un modulo di benchmark con JMH misura velocità e
  memoria attraverso il contratto del nodo, quindi anche il costo dello
  storage e non solo quello dell'indice.
- **Misure relative.** Il prototipo Java non dà tempi rappresentativi
  dell'implementazione finale, quindi ogni indice si confronta con un
  riferimento, la [scansione lineare](/indices/linear-scan.md), e si
  ragiona sui rapporti.
- **Profili.** Un profilo rapido (circa 15 minuti) per controllare
  durante lo sviluppo, e un profilo completo (alcune ore) per le misure
  da tenere.
- **Piani.** Le serie di misure si descrivono in file JSON, con gruppi
  di parametri per profilo; tutto il piano si controlla prima di
  partire.
- **Macchina dedicata.** Le misure da confrontare si fanno sulla stessa
  macchina, dedicata ai benchmark.
- **Risultati tracciabili.** Ogni risultato riporta il commit del codice
  misurato, e un report HTML accompagna i dati.
- **Scenari di riferimento.** I parametri si ricavano da scenari
  concreti, come lo [scenario del sistema
  solare](/indices/solar-system-scenario.md), e da modelli come la
  [capacità degli aggiornamenti per tick](/indices/update-capacity.md).

# Correlati

- Tutte le [decisioni](/decisions/) sono destinate a essere rivalutate
  man mano che i prototipi portano nuove informazioni.
- Il [linguaggio](/decisions/language.md) dei prototipi è scelto per
  iterare in fretta.
