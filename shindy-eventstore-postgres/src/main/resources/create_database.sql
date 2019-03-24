CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

DROP TABLE IF EXISTS event;
DROP TABLE IF EXISTS aggregate_schema;

CREATE TABLE IF NOT EXISTS aggregate_schema
(
  aggregate_id     UUID      NOT NULL PRIMARY KEY,
  aggregate_type   VARCHAR   NOT NULL,
  current_version  INTEGER   NOT NULL,
  last_modified_on TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS event
(
  serial_num        bigserial primary key,
  aggregate_id      UUID      NOT NULL,
  aggregate_type    VARCHAR   NOT NULL,
  aggregate_version integer   NOT NULL,
  event_body        JSONB     NOT NULL,
  event_time        TIMESTAMP NOT NULL DEFAULT NOW(),
  FOREIGN KEY (aggregate_id)
    REFERENCES aggregate_schema (aggregate_id)
);

-- this will ensure there is no branching occur for the aggregate and also provides ordering
-- when query for specific aggregate events
CREATE UNIQUE INDEX IF NOT EXISTS event_ordering on event (aggregate_id, aggregate_version);

CREATE OR REPLACE FUNCTION trg_before_insert_event() RETURNS TRIGGER AS
$$
DECLARE
  _current_aggregate_version int;
BEGIN
  SELECT s.current_version INTO _current_aggregate_version
  FROM aggregate_schema s
  WHERE s.aggregate_id = NEW.aggregate_id
  LIMIT 1;

  IF _current_aggregate_version IS NULL THEN
    -- first event inserted. create a record in aggregate schema
    INSERT INTO aggregate_schema (aggregate_id, aggregate_type, current_version)
    VALUES (NEW.aggregate_id, NEW.aggregate_type, NEW.aggregate_version);
  ELSEIF NEW.aggregate_version = _current_aggregate_version + 1 THEN
    -- new event produced. increment aggregate version
    UPDATE aggregate_schema
    SET current_version = NEW.aggregate_version
    WHERE aggregate_id = NEW.aggregate_id;
  ELSE
    RAISE EXCEPTION 'Version conflict. Possible concurrent insert of events to the same aggregate.';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER event_before_insert
  BEFORE INSERT
  ON event
  FOR EACH ROW
EXECUTE PROCEDURE trg_before_insert_event();

CREATE RULE prevent_deletes_from_schema AS ON DELETE TO aggregate_schema DO INSTEAD NOTHING;
CREATE RULE prevent_deletes_from_events AS ON DELETE TO event DO INSTEAD NOTHING;
CREATE RULE prevent_updates_on_events AS ON UPDATE TO event DO INSTEAD NOTHING;
