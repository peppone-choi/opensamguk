-- Every stored reservation has a write identity, including legacy rows without request_id.
ALTER TABLE general_turn
    ADD COLUMN reservation_revision uuid NOT NULL DEFAULT gen_random_uuid();

CREATE FUNCTION refresh_general_turn_reservation_revision() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    NEW.reservation_revision := gen_random_uuid();
    RETURN NEW;
END;
$$;

CREATE TRIGGER general_turn_reservation_revision_before_update
    BEFORE UPDATE ON general_turn
    FOR EACH ROW EXECUTE FUNCTION refresh_general_turn_reservation_revision();
