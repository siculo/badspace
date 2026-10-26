# 1. Filosofia d’uso

* [Filosofia d’uso di BADSPACE](./overview.md) - Che cosa offre il prototipo Java di BADSPACE, quali decisioni prende il software e come iniziare a leggerne la documentazione.

# 2. Architettura generale

* [Architettura a livelli](./architecture/layers.md) - Software, API pubblica e nodi locali del prototipo Java: responsabilità, dipendenze e organizzazione dei moduli.

# 3. Componenti principali dell’API

* [Componenti dell’API](./api/components.md) - Spazio, partizione, writer, snapshot acquisito e insieme di snapshot: ruolo, problemi risolti e sequenza d’uso nell’API corrente.
* [Casi d’uso attuali](./use-cases.md) - Scenari realizzabili con il prototipo corrente ed esempi eseguibili per esplorare l’API.
* [Contratto dell’API corrente](./api/operations.md) - Firme, risultati ed errori delle operazioni implementate: entità, metadati, query, partizioni, proprietà, tick, snapshot, query su più partizioni e salvataggi.

# 4. Problemi e scelte

* [Decisioni correnti](./decisions/index.md) - Partizionamento, concorrenza, rappresentazione dei dati, isolamento e persistenza.

# 5. Meccanismi e strutture interne

* [Approfondimenti delle partizioni](./architecture/index.md) - Proprietà, commit, tempo logico, conservazione, rimozione, metadati e query su più partizioni.
* [Indici implementati e misure](./indices/index.md) - Scansione lineare, grid e griglia di alberi, con limiti e benchmark.

# Consultazione

* [Glossario dell’implementazione corrente](./glossary.md) - Definizioni dei termini usati dal prototipo corrente: spazi, partizioni, writer, tick, snapshot, acquisizioni, insiemi di snapshot, metadati e salvataggi.
