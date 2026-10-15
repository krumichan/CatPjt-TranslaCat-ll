-- 기존 원문·학습 언어와 별도로 설명/피드백 언어 및 다음 학습일 예약값을 저장한다.
ALTER TABLE language_learning_user_setting
    ADD COLUMN feedback_language VARCHAR(20) NULL AFTER learning_language,
    ADD COLUMN pending_feedback_language VARCHAR(20) NULL AFTER pending_learning_language;

-- 기존 사용자의 기본 설명 언어를 보존하며 원문 언어가 없는 미설정 행은 그대로 둔다.
UPDATE language_learning_user_setting
SET feedback_language = origin_language
WHERE feedback_language IS NULL AND origin_language IS NOT NULL;
