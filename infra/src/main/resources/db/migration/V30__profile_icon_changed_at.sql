ALTER TABLE users
    ADD COLUMN profile_icon_changed_at TIMESTAMPTZ NULL,
    ADD COLUMN profile_icon_managed BOOLEAN NOT NULL DEFAULT FALSE;
