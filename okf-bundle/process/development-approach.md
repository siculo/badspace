---
type: Playbook
title: Approccio di sviluppo
description: BADSPACE viene sviluppato per prototipi minimali, verificati da test di integrazione e accompagnati da client d'esempio costruiti man mano, per validare le decisioni di progettazione sul campo; lo sviluppo è incrementale, ogni concetto nuovo porta con sé le estensioni che richiede e i meccanismi si implementano una volta e si estendono quando servono nuovi dati; le scelte che dipendono dalle prestazioni si basano su benchmark relativi a un riferimento.
tags: [badspace, methodology, prototyping]
status: stable
generated: { by: claude-code/claude-opus-5-5, at: 2026-10-15T20:26:50Z }
---

# Approccio

Procedere per **prototipi minimali** su cui ragionare e rivalutare le
scelte fatte, invece di progettare tutto a priori. Ogni prototipo è
verificato da **test di integrazione** e accompagnato da **client
d'esempio** che ne mostrano l'uso, per validare le decisioni sul campo.
I client non devono essere necessariamente un gioco.

Ogni decisione resta rivalutabile quando un prototipo porta
informazioni nuove.

# Sviluppo incrementale

Lo sviluppo è **incrementale**: ogni passo introduce solo quello che
serve ai concetti già presenti, e un concetto nuovo porta con sé le
estensioni che richiede, anche in parti del codice già scritte
(configurazione, API, contratto del nodo, storage, persistenza).
Un passo non anticipa parametri, campi o chiamate che riguardano
concetti che ancora non esistono.

Per esempio, alla creazione di una partizione si sceglie l'indice; il
numero k di commit passati da conservare si aggiunge alla
configurazione della partizione solo quando arrivano i
[commit](/architecture/partition-commit.md) e quindi gli snapshot,
perché prima k non ha significato.

## Meccanismi prima, estesi man mano

Un meccanismo si implementa una volta, appena serve, con l'insieme
minimo di dati che esistono in quel momento, e poi si estende quando
arrivano nuovi dati da gestire, invece di aspettare che ci siano
tutti. Per esempio la [persistenza](/decisions/persistence.md) si
implementa dopo i metadati, così salva già un insieme minimo ma
significativo di informazioni, e si estende alle tombstone quando
arrivano.

L'ordine dei prototipi tiene conto solo dei prerequisiti veri, cioè di
ciò che senza un altro pezzo non può funzionare.

# Test di integrazione e client d'esempio

Le due cose hanno ruoli diversi e stanno in moduli separati del
prototipo:

- **Test di integrazione.** Verificano l'API insieme ai nodi del
  servizio, dal punto di vista del software che usa BADSPACE. Stanno in
  un modulo che contiene solo test e dipende da common, service e api,
  così l'API non dipende dal servizio nemmeno nei suoi test. La
  semantica in dettaglio resta nei test unitari di ogni modulo.
- **Client d'esempio.** Mostrano come si usa BADSPACE. Ogni esempio è
  un modulo a sé, con un solo programma, invece di più programmi nello
  stesso modulo: per esempio un giro dell'API (spazi, partizioni,
  operazioni batch, query, tick e rimozione) e un writer che muove le
  entità a ogni tick mentre un reader mostra l'ultimo snapshot.

Quanti e quali client servano non è deciso: si costruiscono man mano,
accanto ai prototipi che li richiedono, per esempio uno scenario con
aree non contigue per la [migrazione](/mechanisms/entity-migration.md)
e uno con zone contigue per le [letture
coerenti](/mechanisms/consistent-reads.md).

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
