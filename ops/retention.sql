-- Rétention des données Traccar sur TimescaleDB.
-- Idempotent : rejouable sans erreur si la policy existe déjà.
--
-- À lancer UNE SEULE FOIS, APRÈS le premier démarrage de Traccar
-- (Traccar crée le schéma et convertit lui-même tc_positions / tc_events /
--  tc_actions en hypertables via ses migrations Liquibase).
--
--   docker exec -i <conteneur-db> psql -U tracker -d tracker < ops/retention.sql
--
-- Vérification :
--   SELECT hypertable_name, proc_name, config
--     FROM timescaledb_information.jobs WHERE proc_name = 'policy_retention';

DO $$
DECLARE
    v_retention CONSTANT interval := INTERVAL '30 days';
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM timescaledb_information.jobs
        WHERE proc_name = 'policy_retention' AND hypertable_name = 'tc_positions'
    ) THEN
        PERFORM add_retention_policy('tc_positions', v_retention);
        RAISE NOTICE 'retention tc_positions = %', v_retention;
    ELSE
        RAISE NOTICE 'retention tc_positions deja en place';
    END IF;

    -- Les événements (entrées/sorties de zone, SOS) sont plus légers :
    -- on les garde 90 jours pour pouvoir expliquer un incident passé.
    IF NOT EXISTS (
        SELECT 1 FROM timescaledb_information.jobs
        WHERE proc_name = 'policy_retention' AND hypertable_name = 'tc_events'
    ) THEN
        PERFORM add_retention_policy('tc_events', INTERVAL '90 days');
        RAISE NOTICE 'retention tc_events = 90 days';
    END IF;
END
$$;
