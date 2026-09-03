CREATE OR REPLACE FUNCTION sync_ai_image_availability() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE ai_review_task SET file_available=NEW.file_available,
        cloud_available_at=CASE WHEN NULLIF(BTRIM(NEW.cloud_object_key),'') IS NOT NULL THEN NEW.cloud_uploaded_at END
    WHERE image_id=NEW.id AND (file_available,cloud_available_at) IS DISTINCT FROM
        (NEW.file_available,CASE WHEN NULLIF(BTRIM(NEW.cloud_object_key),'') IS NOT NULL THEN NEW.cloud_uploaded_at END);
    RETURN NEW;
END $$;
CREATE OR REPLACE TRIGGER trg_ai_image_availability
AFTER UPDATE OF file_available, cloud_object_key, cloud_uploaded_at ON image_asset
FOR EACH ROW WHEN ((OLD.file_available,OLD.cloud_object_key,OLD.cloud_uploaded_at) IS DISTINCT FROM
                  (NEW.file_available,NEW.cloud_object_key,NEW.cloud_uploaded_at))
EXECUTE FUNCTION sync_ai_image_availability();
