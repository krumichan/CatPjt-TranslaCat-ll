-- V009 데이터를 보존하면서 LL 생성기가 검증한 band/분산 분류를 문항과 함께 기록한다.
-- 기존 V009 행은 증거가 없으므로 NULL이며, 새 생성 worker는 게시 전에 값을 검증한다.
ALTER TABLE language_learning_daily_item
    ADD COLUMN language_complexity_band INT NULL,
    ADD COLUMN diversity_metadata_json TEXT NULL,
    ADD CONSTRAINT ck_ll_w_item_band CHECK (language_complexity_band IS NULL OR language_complexity_band BETWEEN 1 AND 5);
